package ai.beatmind.generation;

import ai.beatmind.provenance.ProvenanceLedger;
import ai.beatmind.provenance.ProvenanceLedger.Actor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rights and abuse reports, takedowns and appeals (US-032, REQ-F-54, REQ-F-59, API-013,
 * Copyright and Takedown Policy). Every report gets two clocks when it arrives: acknowledge within
 * 24 hours, and act within 3 hours for an Indian government or court order or 15 days otherwise.
 * A valid report disables the song (page, share links, downloads and certificate); the owner can
 * appeal once, and a person answers within 72 hours. Every admin step is audited. Admin decisions
 * are written to the song's ledger as BeatMind (system), not as the owner.
 */
@Service
class Takedowns {

    static final Duration ACKNOWLEDGE_WITHIN = Duration.ofHours(24);
    static final Duration ORDER_ACT_WITHIN = Duration.ofHours(3);
    static final Duration REPORT_ACT_WITHIN = Duration.ofDays(15);
    static final Duration APPEAL_ANSWER_WITHIN = Duration.ofHours(72);
    static final int REPORTS_PER_IP_PER_HOUR = 10;

    private static final Logger log = LoggerFactory.getLogger(Takedowns.class);
    private static final Pattern SONG_ID = Pattern.compile(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
    private static final Pattern CERTIFICATE = Pattern.compile("(BM-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4})");
    private static final Pattern SHARE = Pattern.compile("/s/([A-Za-z0-9_-]{20,64})");

    /** A report as it arrives from the public form or the grievance mailbox. */
    record NewReport(String target, String reporterKind, String reporterName, String reporterEmail,
                     String rightClaimed, String description, String evidenceUrl, String orderReference) {}

    /**
     * A report as admins see it. {@code dueAt} is the next deadline that applies, {@code clock}
     * says which one (acknowledge, act or appeal), and {@code overdue} is true once it has passed.
     */
    record Takedown(UUID id, UUID songId, String songTitle, String certificateId, String reporterKind,
                    String reporterName, String reporterEmail, String rightClaimed, String description,
                    String evidenceUrl, String orderReference, String status, OffsetDateTime ackDueAt,
                    OffsetDateTime actionDueAt, OffsetDateTime acknowledgedAt, OffsetDateTime decidedAt,
                    String decisionNote, String appealNote, OffsetDateTime appealedAt, OffsetDateTime appealDueAt,
                    OffsetDateTime appealDecidedAt, String appealDecisionNote, OffsetDateTime createdAt,
                    String clock, OffsetDateTime dueAt, boolean overdue) {}

    /** What the song's owner sees: why it was removed and where the appeal stands. No reporter contact. */
    record OwnerView(UUID id, String rightClaimed, String reporterKind, String status, String decisionNote,
                     OffsetDateTime decidedAt, String appealNote, OffsetDateTime appealedAt,
                     OffsetDateTime appealDueAt, String appealDecisionNote, OffsetDateTime appealDecidedAt) {}

    record Overdue(long acknowledge, long act, long appeal) {
        long total() {
            return acknowledge + act + appeal;
        }
    }

    private final JdbcClient jdbc;
    private final ProvenanceLedger ledger;

    Takedowns(JdbcClient jdbc, ProvenanceLedger ledger, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        // Alert when any deadline has passed (SRE handbook RB-05).
        Gauge.builder("beatmind.takedowns.overdue", this, t -> t.safeOverdue().total())
                .description("Takedown reports and appeals past their deadline").register(meters);
        Gauge.builder("beatmind.takedowns.open", this, Takedowns::safeOpen)
                .description("Takedown reports and appeals waiting for a person").register(meters);
    }

    static boolean isOrder(String reporterKind) {
        return "government_order".equals(reporterKind) || "court_order".equals(reporterKind);
    }

    /** Records a report and starts its clocks. Returns the report id the reporter can quote. */
    @Transactional
    UUID report(NewReport r, UUID reporterUserId, String ipHash) {
        if (ipHash != null) {
            long recent = jdbc.sql("""
                    SELECT count(*) FROM takedown_request WHERE client_ip_hash = :ip AND created_at > now() - interval '1 hour'
                    """).param("ip", ipHash).query(Long.class).single();
            if (recent >= REPORTS_PER_IP_PER_HOUR) {
                throw new SongRequests.Refusal(HttpStatus.TOO_MANY_REQUESTS, "velocity",
                        "Too many reports from your network in the last hour. Please email the grievance officer instead.");
            }
        }
        Resolved target = resolve(r.target());
        Duration act = isOrder(r.reporterKind()) ? ORDER_ACT_WITHIN : REPORT_ACT_WITHIN;
        // An order is acknowledged and acted on inside its 3 hours.
        Duration ack = act.compareTo(ACKNOWLEDGE_WITHIN) < 0 ? act : ACKNOWLEDGE_WITHIN;
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO takedown_request (id, song_id, certificate_id, reporter_kind, reporter_name, reporter_email,
                                              reporter_user_id, right_claimed, description, evidence_url, order_reference,
                                              client_ip_hash, ack_due_at, action_due_at)
                VALUES (:id, :song, :cert, :kind, :name, :email, :user, :right, :description, :evidence, :order, :ip,
                        now() + make_interval(secs => :ack), now() + make_interval(secs => :act))
                """)
                .param("id", id).param("song", target.songId()).param("cert", target.certificateId())
                .param("kind", r.reporterKind()).param("name", r.reporterName()).param("email", r.reporterEmail())
                .param("user", reporterUserId).param("right", r.rightClaimed()).param("description", r.description())
                .param("evidence", r.evidenceUrl()).param("order", r.orderReference()).param("ip", ipHash)
                .param("ack", ack.toSeconds()).param("act", act.toSeconds())
                .update();
        if (target.songId() != null) {
            ledger.append(target.songId(), "takedown_received", Actor.SYSTEM, Map.of(
                    "takedownId", id.toString(), "reporterKind", r.reporterKind(), "right", r.rightClaimed()));
        }
        if (isOrder(r.reporterKind())) {
            log.warn("takedown {}: {} received, act within 3 hours", id, r.reporterKind());
        }
        return id;
    }

    record Resolved(UUID songId, String certificateId) {}

    /** Finds the song from a song link, a share link or a certificate id. Unknown targets stay null. */
    Resolved resolve(String target) {
        if (target == null) {
            return new Resolved(null, null);
        }
        Matcher cert = CERTIFICATE.matcher(target.toUpperCase(java.util.Locale.ROOT));
        if (cert.find()) {
            UUID song = jdbc.sql("SELECT song_id FROM certificate WHERE id = :id").param("id", cert.group(1))
                    .query(UUID.class).optional().orElse(null);
            return new Resolved(song, cert.group(1));
        }
        Matcher share = SHARE.matcher(target);
        if (share.find()) {
            UUID song = jdbc.sql("SELECT song_id FROM share_link WHERE token_hash = :h")
                    .param("h", Shares.hash(share.group(1))).query(UUID.class).optional().orElse(null);
            return new Resolved(song, null);
        }
        Matcher id = SONG_ID.matcher(target);
        if (id.find()) {
            UUID candidate = UUID.fromString(id.group(1));
            UUID song = jdbc.sql("SELECT id FROM song WHERE id = :id").param("id", candidate)
                    .query(UUID.class).optional().orElse(null);
            return new Resolved(song, null);
        }
        return new Resolved(null, null);
    }

    /** The admin queue: everything waiting for a person, the nearest deadline first. */
    List<Takedown> queue(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.sql(SELECT + " WHERE t.status IN ('received', 'acknowledged', 'appealed') ORDER BY " + DUE + " LIMIT 200")
                    .query(Takedowns::row).list();
        }
        return jdbc.sql(SELECT + " WHERE t.status = :status ORDER BY t.created_at DESC LIMIT 200")
                .param("status", status).query(Takedowns::row).list();
    }

    Optional<Takedown> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE t.id = :id").param("id", id).query(Takedowns::row).optional();
    }

    /** A person has the report (the 24-hour clock stops). */
    @Transactional
    Optional<Takedown> acknowledge(UUID id, UUID adminId) {
        int n = jdbc.sql("UPDATE takedown_request SET status = 'acknowledged', acknowledged_at = now() WHERE id = :id AND status = 'received'")
                .param("id", id).update();
        if (n == 0) {
            return Optional.empty();
        }
        audit(adminId, "takedown_acknowledge", id, null);
        return find(id);
    }

    /**
     * Decides a report. {@code disable} takes the song down: rights become {@code disabled}, share
     * links are revoked, the certificate is cancelled and the ledger records it. Otherwise the
     * report is rejected. Both stop the action clock.
     */
    @Transactional
    Optional<Takedown> decide(UUID id, UUID adminId, boolean disable, String note) {
        Takedown t = find(id).filter(x -> "received".equals(x.status()) || "acknowledged".equals(x.status())).orElse(null);
        if (t == null) {
            return Optional.empty();
        }
        if (disable && t.songId() == null) {
            throw new SongRequests.Refusal(HttpStatus.CONFLICT, "no_song",
                    "This report does not name a song we could find. Reject it, or ask the reporter for the link.");
        }
        String previous = null;
        if (disable) {
            previous = jdbc.sql("SELECT rights_status FROM song WHERE id = :id FOR UPDATE").param("id", t.songId())
                    .query(String.class).single();
            jdbc.sql("UPDATE song SET rights_status = 'disabled', updated_at = now() WHERE id = :id")
                    .param("id", t.songId()).update();
            jdbc.sql("UPDATE share_link SET revoked_at = now() WHERE song_id = :id AND revoked_at IS NULL")
                    .param("id", t.songId()).update();
            jdbc.sql("""
                    UPDATE certificate SET revoked_at = now(), revoked_reason = 'takedown'
                     WHERE song_id = :id AND revoked_at IS NULL
                    """).param("id", t.songId()).update();
            ledger.append(t.songId(), "takedown_actioned", Actor.SYSTEM, Map.of(
                    "takedownId", id.toString(), "right", t.rightClaimed(), "reporterKind", t.reporterKind()));
        } else if (t.songId() != null) {
            ledger.append(t.songId(), "takedown_rejected", Actor.SYSTEM, Map.of("takedownId", id.toString()));
        }
        jdbc.sql("""
                UPDATE takedown_request
                   SET status = :status, decided_at = now(), decided_by = :admin, decision_note = :note,
                       previous_rights = :previous, acknowledged_at = coalesce(acknowledged_at, now())
                 WHERE id = :id
                """)
                .param("status", disable ? "actioned" : "rejected").param("admin", adminId)
                .param("note", strip(note)).param("previous", previous).param("id", id)
                .update();
        audit(adminId, disable ? "takedown_disable" : "takedown_reject", id, note);
        return find(id);
    }

    /** The latest takedown that removed this song, for its owner. */
    Optional<OwnerView> forOwner(UUID songId) {
        return jdbc.sql("""
                SELECT id, right_claimed, reporter_kind, status, decision_note, decided_at, appeal_note, appealed_at,
                       appeal_due_at, appeal_decision_note, appeal_decided_at
                  FROM takedown_request
                 WHERE song_id = :song AND status IN ('actioned', 'appealed', 'restored')
                 ORDER BY decided_at DESC LIMIT 1
                """)
                .param("song", songId)
                .query((rs, n) -> new OwnerView(rs.getObject("id", UUID.class), rs.getString("right_claimed"),
                        rs.getString("reporter_kind"), rs.getString("status"), rs.getString("decision_note"),
                        rs.getObject("decided_at", OffsetDateTime.class), rs.getString("appeal_note"),
                        rs.getObject("appealed_at", OffsetDateTime.class), rs.getObject("appeal_due_at", OffsetDateTime.class),
                        rs.getString("appeal_decision_note"), rs.getObject("appeal_decided_at", OffsetDateTime.class)))
                .optional();
    }

    /** The owner appeals a removal (REQ-F-59): once, with a 72-hour answer target. */
    @Transactional
    boolean appeal(UUID songId, String note) {
        int n = jdbc.sql("""
                UPDATE takedown_request
                   SET status = 'appealed', appeal_note = :note, appealed_at = now(),
                       appeal_due_at = now() + make_interval(secs => :due)
                 WHERE id = (SELECT id FROM takedown_request WHERE song_id = :song AND status = 'actioned'
                              ORDER BY decided_at DESC LIMIT 1)
                """)
                .param("note", strip(note)).param("due", APPEAL_ANSWER_WITHIN.toSeconds()).param("song", songId)
                .update();
        if (n == 1) {
            ledger.append(songId, "takedown_appealed", Actor.HUMAN, Map.of());
        }
        return n == 1;
    }

    /** A person answers the appeal: restore the song as it was, or keep it down. */
    @Transactional
    Optional<Takedown> decideAppeal(UUID id, UUID adminId, boolean restore, String note) {
        Takedown t = find(id).filter(x -> "appealed".equals(x.status())).orElse(null);
        if (t == null) {
            return Optional.empty();
        }
        String previous = jdbc.sql("SELECT previous_rights FROM takedown_request WHERE id = :id").param("id", id)
                .query(String.class).single();
        if (restore) {
            jdbc.sql("UPDATE song SET rights_status = :rights, updated_at = now() WHERE id = :id AND rights_status = 'disabled'")
                    .param("rights", previous == null ? "bm_owned" : previous).param("id", t.songId()).update();
            jdbc.sql("""
                    UPDATE certificate SET revoked_at = NULL, revoked_reason = NULL
                     WHERE song_id = :id AND revoked_reason = 'takedown'
                    """).param("id", t.songId()).update();
            ledger.append(t.songId(), "takedown_restored", Actor.SYSTEM, Map.of("takedownId", id.toString()));
        } else {
            ledger.append(t.songId(), "takedown_upheld", Actor.SYSTEM, Map.of("takedownId", id.toString()));
        }
        jdbc.sql("""
                UPDATE takedown_request
                   SET status = :status, appeal_decided_at = now(), appeal_decided_by = :admin, appeal_decision_note = :note
                 WHERE id = :id
                """)
                .param("status", restore ? "restored" : "actioned").param("admin", adminId).param("note", strip(note))
                .param("id", id).update();
        audit(adminId, restore ? "takedown_restore" : "takedown_uphold", id, note);
        return find(id);
    }

    Overdue overdue() {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'received' AND ack_due_at < now()) AS ack,
                       count(*) FILTER (WHERE status IN ('received', 'acknowledged') AND action_due_at < now()) AS act,
                       count(*) FILTER (WHERE status = 'appealed' AND appeal_due_at < now()) AS appeal
                  FROM takedown_request
                """)
                .query((rs, n) -> new Overdue(rs.getLong("ack"), rs.getLong("act"), rs.getLong("appeal")))
                .single();
    }

    /** Logs a warning every five minutes while anything is overdue, so the on-call person sees it. */
    @Scheduled(fixedDelayString = "${beatmind.takedowns.check-every:5m}", initialDelayString = "1m")
    void warnOverdue() {
        Overdue o = safeOverdue();
        if (o.total() > 0) {
            log.warn("takedowns past deadline: {} to acknowledge, {} to act on, {} appeals to answer",
                    o.acknowledge(), o.act(), o.appeal());
        }
    }

    private Overdue safeOverdue() {
        try {
            return overdue();
        } catch (RuntimeException e) {
            return new Overdue(0, 0, 0);
        }
    }

    private double safeOpen() {
        try {
            return jdbc.sql("SELECT count(*) FROM takedown_request WHERE status IN ('received', 'acknowledged', 'appealed')")
                    .query(Long.class).single();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void audit(UUID adminId, String action, UUID target, String note) {
        jdbc.sql("""
                INSERT INTO admin_audit (id, actor_user_id, action, target, detail)
                VALUES (:id, :actor, :action, :target, :detail)
                """)
                .param("id", UUID.randomUUID()).param("actor", adminId).param("action", action)
                .param("target", target.toString()).param("detail", strip(note))
                .update();
    }

    private static String strip(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Which deadline applies now, as SQL: appeals by their 72 hours, the rest by the nearer clock. */
    private static final String DUE = """
            CASE WHEN t.status = 'appealed' THEN t.appeal_due_at
                 WHEN t.status = 'received' THEN least(t.ack_due_at, t.action_due_at)
                 ELSE t.action_due_at END
            """;

    private static final String SELECT = """
            SELECT t.id, t.song_id, s.title AS song_title, t.certificate_id, t.reporter_kind, t.reporter_name,
                   t.reporter_email, t.right_claimed, t.description, t.evidence_url, t.order_reference, t.status,
                   t.ack_due_at, t.action_due_at, t.acknowledged_at, t.decided_at, t.decision_note, t.appeal_note,
                   t.appealed_at, t.appeal_due_at, t.appeal_decided_at, t.appeal_decision_note, t.created_at,
            """ + DUE + " AS due_at, now() AS now FROM takedown_request t LEFT JOIN song s ON s.id = t.song_id";

    private static Takedown row(ResultSet rs, int n) throws SQLException {
        String status = rs.getString("status");
        OffsetDateTime due = rs.getObject("due_at", OffsetDateTime.class);
        OffsetDateTime now = rs.getObject("now", OffsetDateTime.class);
        boolean open = "received".equals(status) || "acknowledged".equals(status) || "appealed".equals(status);
        String clock = switch (status) {
            case "received" -> rs.getObject("ack_due_at", OffsetDateTime.class)
                    .isAfter(rs.getObject("action_due_at", OffsetDateTime.class)) ? "act" : "acknowledge";
            case "acknowledged" -> "act";
            case "appealed" -> "appeal";
            default -> null;
        };
        return new Takedown(rs.getObject("id", UUID.class), rs.getObject("song_id", UUID.class),
                rs.getString("song_title"), rs.getString("certificate_id"), rs.getString("reporter_kind"),
                rs.getString("reporter_name"), rs.getString("reporter_email"), rs.getString("right_claimed"),
                rs.getString("description"), rs.getString("evidence_url"), rs.getString("order_reference"), status,
                rs.getObject("ack_due_at", OffsetDateTime.class), rs.getObject("action_due_at", OffsetDateTime.class),
                rs.getObject("acknowledged_at", OffsetDateTime.class), rs.getObject("decided_at", OffsetDateTime.class),
                rs.getString("decision_note"), rs.getString("appeal_note"),
                rs.getObject("appealed_at", OffsetDateTime.class), rs.getObject("appeal_due_at", OffsetDateTime.class),
                rs.getObject("appeal_decided_at", OffsetDateTime.class), rs.getString("appeal_decision_note"),
                rs.getObject("created_at", OffsetDateTime.class), open ? clock : null, open ? due : null,
                open && due != null && due.isBefore(now));
    }
}
