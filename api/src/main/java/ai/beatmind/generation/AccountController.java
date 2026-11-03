package ai.beatmind.generation;

import ai.beatmind.billing.Billing;
import ai.beatmind.identity.Accounts;
import ai.beatmind.identity.CurrentUser;
import ai.beatmind.moderation.ModerationCases;
import ai.beatmind.provenance.ProvenanceLedger;
import ai.beatmind.provenance.ProvenanceLedger.Actor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Data export and account deletion (US-011, REQ-NFR-05, DPIA sections 6 and 7, DPDP rights of
 * access and erasure).
 *
 * <p>The export is a ZIP with {@code data.json} (account, songs with settings, takes and full
 * history, credits, licences, share links, moderation cases, lyric drafts and removals) and the
 * audio files the plan may download. Deletion takes effect at once: songs leave the library and
 * their files are purged 30 days later by {@link SongPurge}; certificates stay valid for the people
 * who rely on them; the ledger keeps its hashes, linked only to a tombstone account.
 */
@RestController
class AccountController {

    record Deletion(@NotBlank @Size(max = 320) String confirmEmail) {}

    private final Accounts accounts;
    private final SongStore songs;
    private final AudioStore audio;
    private final ProvenanceLedger ledger;
    private final Credits credits;
    private final ModerationCases cases;
    private final Shares shares;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Billing billing;

    AccountController(Accounts accounts, SongStore songs, AudioStore audio, ProvenanceLedger ledger, Credits credits,
                      ModerationCases cases, Shares shares, JdbcClient jdbc, JsonMapper json, Billing billing) {
        this.billing = billing;
        this.accounts = accounts;
        this.songs = songs;
        this.audio = audio;
        this.ledger = ledger;
        this.credits = credits;
        this.cases = cases;
        this.shares = shares;
        this.jdbc = jdbc;
        this.json = json.rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();
    }

    /** A file in the export: where it goes in the ZIP and where it is stored. */
    record ExportFile(String path, String key, String sha256) {}

    @GetMapping("/v1/me/export")
    ResponseEntity<StreamingResponseBody> export() {
        CurrentUser me = CurrentUser.get().orElseThrow();
        Map<String, Object> data = new LinkedHashMap<>();
        List<ExportFile> files = new ArrayList<>();
        collect(me, data, files);
        String name = "beatmind-export-" + LocalDate.now(ZoneOffset.UTC) + ".zip";
        StreamingResponseBody body = out -> write(out, data, files);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    /** What goes into {@code data.json}, and the list of audio files. Package-private for tests. */
    void collect(CurrentUser me, Map<String, Object> data, List<ExportFile> files) {
        data.put("format", "beatmind-export/1");
        data.put("exportedAt", OffsetDateTime.now(ZoneOffset.UTC));
        data.put("about", "Everything BeatMind AI holds about your account. Every song here is AI-generated. "
                + "Files are the formats your plan can download.");
        data.put("account", accounts.profile(me.id()).orElseThrow());
        List<Map<String, Object>> songList = new ArrayList<>();
        List<SongStore.Song> all = songs.list(me.id(), null, null, 10_000);
        Map<UUID, List<SongStore.Take>> takes = songs.takes(all.stream().map(SongStore.Song::id).toList());
        for (SongStore.Song s : all) {
            Map<String, Object> song = new LinkedHashMap<>();
            song.put("id", s.id());
            song.put("title", s.title());
            song.put("mode", s.mode());
            song.put("languageTag", s.languageTag());
            song.put("prompt", s.prompt());
            song.put("lyrics", s.lyrics());
            song.put("vocalType", s.vocalType());
            song.put("instrumental", s.instrumental());
            song.put("durationSeconds", s.durationSeconds());
            song.put("beat", SongViews.beat(s.beatJson()));
            song.put("planAtCreation", s.planAtCreation());
            song.put("rightsStatus", s.rightsStatus());
            song.put("rights", SongViews.rights(s.rightsStatus()));
            song.put("label", SongViews.AI_LABEL);
            song.put("createdAt", s.createdAt());
            List<Map<String, Object>> takeList = new ArrayList<>();
            String folder = "songs/" + folder(s);
            for (SongStore.Take t : takes.getOrDefault(s.id(), List.of())) {
                Map<String, Object> take = new LinkedHashMap<>();
                take.put("index", t.index());
                take.put("status", t.status());
                take.put("watermarkId", t.watermarkId());
                take.put("tempoBpm", t.tempoBpm());
                List<String> paths = new ArrayList<>();
                if ("ready".equals(t.status()) && !"disabled".equals(s.rightsStatus())) {
                    for (String format : Entitlements.formats(me.plan(), s.rightsStatus())) {
                        SongStore.Asset a = t.assets().get(Entitlements.assetFor(me.plan(), s.rightsStatus(), format));
                        if (a == null || a.key().isEmpty()) {
                            continue;
                        }
                        String path = folder + "/take-" + t.index() + "." + format;
                        files.add(new ExportFile(path, a.key(), a.sha256()));
                        paths.add(path);
                    }
                }
                take.put("files", paths);
                takeList.add(take);
            }
            song.put("takes", takeList);
            song.put("history", ledger.events(s.id()));
            song.put("shareLinks", shares.list(s.id()));
            songList.add(song);
        }
        data.put("songs", songList);
        data.put("credits", credits.recent(me.id(), 100_000));
        data.put("licences", jdbc.sql("""
                SELECT id, song_id, scope, title, issued_at, revoked_at, revoked_reason FROM certificate
                 WHERE licensee_user_id = :user ORDER BY issued_at
                """).param("user", me.id()).query().listOfRows());
        data.put("lyricDrafts", jdbc.sql("""
                SELECT id, topic, mood, language, status, lyrics, model, created_at FROM lyric_draft
                 WHERE user_id = :user ORDER BY created_at
                """).param("user", me.id()).query().listOfRows());
        data.put("moderationCases", cases.mine(me.id(), 10_000));
        data.put("removals", jdbc.sql("""
                SELECT t.id, t.song_id, t.right_claimed, t.reporter_kind, t.status, t.decision_note, t.decided_at,
                       t.appeal_note, t.appealed_at, t.appeal_decision_note, t.appeal_decided_at
                  FROM takedown_request t JOIN song s ON s.id = t.song_id
                 WHERE s.owner_id = :user AND t.status IN ('actioned', 'appealed', 'restored')
                 ORDER BY t.created_at
                """).param("user", me.id()).query().listOfRows());
        data.put("payments", jdbc.sql("""
                SELECT p.starts_at, p.ends_at, p.plan, p.amount_minor, p.currency, p.payment_method, p.status,
                       p.reversal_reason, s.billing_country
                  FROM billing_period p JOIN subscription s ON s.id = p.subscription_id
                 WHERE p.user_id = :user ORDER BY p.starts_at
                """).param("user", me.id()).query().listOfRows());
        data.put("notIncluded", List.of(
                "Card, UPI and bank details: Razorpay holds them, not BeatMind.",
                "Text our safety check blocked: we keep only a fingerprint of it, not the text.",
                "Songs you deleted: they leave your library at once and their files are removed after 30 days.",
                "Security records such as sign-in codes and session tokens: we keep only hashes, briefly."));
    }

    private void write(OutputStream out, Map<String, Object> data, List<ExportFile> files) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("data.json"));
            zip.write(json.writeValueAsBytes(data));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write(README.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (ExportFile f : files) {
                var file = audio.open(f.key());
                if (file.isEmpty()) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(f.path()));
                try (InputStream in = file.get().body()) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
    }

    /**
     * Deletes the account (DPIA 6). The body must repeat the account's email, so a stray click or
     * a forged request cannot do it.
     */
    @DeleteMapping("/v1/me")
    @Transactional
    ResponseEntity<?> delete(@Valid @RequestBody Deletion req, HttpServletRequest http) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        if (!me.email().equalsIgnoreCase(req.confirmEmail().strip())) {
            return GenerationController.problem(HttpStatus.BAD_REQUEST, "confirm_email",
                    "Type the email address of this account to confirm.");
        }
        credits.lock(me.id());
        List<UUID> ids = jdbc.sql("SELECT id FROM song WHERE owner_id = :user AND deleted_at IS NULL")
                .param("user", me.id()).query(UUID.class).list();
        for (UUID id : ids) {
            songs.softDelete(id, me.id());
            shares.revokeAll(id);
            ledger.append(id, "deleted", Actor.HUMAN, Map.of("reason", "account_closed"));
        }
        // Jobs that have not started will not run; nothing to refund on a closed account.
        jdbc.sql("""
                UPDATE generation_job SET status = 'dead', error = 'account closed', finished_at = now(), finalized_at = now()
                 WHERE user_id = :user AND status IN ('queued', 'failed')
                """).param("user", me.id()).update();
        jdbc.sql("DELETE FROM lyric_draft WHERE user_id = :user").param("user", me.id()).update();
        cases.forget(me.id());
        // Stop any paid plan now, so nothing renews after the account is gone.
        billing.closeAccount(me.id());
        accounts.close(me.id());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", true);
        body.put("songs", ids.size());
        body.put("filesRemovedBy", OffsetDateTime.now(ZoneOffset.UTC).plusDays(30).toLocalDate());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, accounts.signOutCookie()).body(body);
    }

    private static String folder(SongStore.Song s) {
        String base = s.titleLatin().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.length() > 40) {
            base = base.substring(0, 40).replaceAll("-$", "");
        }
        return (base.isEmpty() ? "song" : base) + "-" + s.id().toString().substring(0, 8);
    }

    private static final String README = """
            BeatMind AI data export

            data.json holds your account, every song in your library with its settings, takes and full
            history (the provenance ledger), your credits, licences, share links, lyric drafts, moderation
            cases and any removals. The songs folder holds the audio files your plan can download.

            Every song here was generated with artificial intelligence. Keep the AI label when you publish
            one, as the beta terms require.

            Questions: write to the grievance officer named in the Copyright and Takedown Policy.
            """;
}
