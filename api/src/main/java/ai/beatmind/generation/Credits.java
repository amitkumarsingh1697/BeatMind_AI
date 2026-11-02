package ai.beatmind.generation;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credit ledger and allowances (US-027, US-030, REQ-F-55, REQ-F-56).
 *
 * <p>One credit is one take. A request debits two credits; each failed, timed-out or withheld take
 * is refunded automatically. Free accounts get a daily allowance that an admin sets; paid plans
 * get a monthly one. "Used" counts the net credits of requests made in the current window, so a
 * refunded take never counts against the allowance. The ledger itself is append-only.
 */
@Component
class Credits {

    /** What the account may still do in the current window. */
    record Allowance(String plan, String window, int limit, int used, int remaining, OffsetDateTime resetsAt,
                     int creditsPerRequest, int concurrency, List<String> formats) {}

    record Entry(long id, int delta, String reason, UUID songId, int balanceAfter, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;
    private final Entitlements entitlements;
    private final Settings settings;

    Credits(JdbcClient jdbc, Entitlements entitlements, Settings settings) {
        this.jdbc = jdbc;
        this.entitlements = entitlements;
        this.settings = settings;
    }

    Allowance allowance(UUID userId, String plan) {
        ZonedDateTime now = ZonedDateTime.now(entitlements.dayZone());
        boolean paid = Entitlements.paid(plan);
        ZonedDateTime start = paid
                ? now.toLocalDate().withDayOfMonth(1).atStartOfDay(now.getZone())
                : now.toLocalDate().atStartOfDay(now.getZone());
        ZonedDateTime resets = paid ? start.plusMonths(1) : start.plusDays(1);
        int limit = paid ? ("pro".equals(plan) ? entitlements.proMonthlyCredits() : entitlements.creatorMonthlyCredits())
                : dailyLimit(userId);
        int used = jdbc.sql("""
                SELECT coalesce(-sum(c.delta), 0) FROM credit_ledger c
                 WHERE c.user_id = :user AND c.job_id IN (
                       SELECT job_id FROM credit_ledger
                        WHERE user_id = :user AND reason = 'generation' AND created_at >= :start)
                """)
                .param("user", userId)
                .param("start", start.toOffsetDateTime())
                .query(Integer.class)
                .single();
        used = Math.max(0, used);
        return new Allowance(plan, paid ? "month" : "day", limit, used, Math.max(0, limit - used),
                resets.toOffsetDateTime(), Entitlements.CREDITS_PER_REQUEST, Entitlements.concurrency(plan),
                Entitlements.formats(plan));
    }

    private int dailyLimit(UUID userId) {
        Integer override = jdbc.sql("SELECT daily_cap_override FROM app_user WHERE id = :id")
                .param("id", userId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        return override != null ? override : settings.freeDailyCredits();
    }

    @Transactional
    void debit(UUID userId, UUID jobId, UUID songId, int credits) {
        append(userId, -credits, "generation", jobId, songId);
    }

    /**
     * Refunds up to {@code credits} for the job, never more than was charged for it. Returns how
     * many credits were refunded.
     */
    @Transactional
    int refund(UUID userId, UUID jobId, UUID songId, int credits, String reason) {
        lock(userId);
        int charged = jdbc.sql("SELECT coalesce(-sum(delta), 0) FROM credit_ledger WHERE job_id = :job AND user_id = :user")
                .param("job", jobId)
                .param("user", userId)
                .query(Integer.class)
                .single();
        int amount = Math.min(credits, Math.max(0, charged));
        if (amount > 0) {
            append(userId, amount, reason, jobId, songId);
        }
        return amount;
    }

    /** Credits refunded for a job so far. */
    int refunded(UUID jobId) {
        return jdbc.sql("SELECT coalesce(sum(delta), 0) FROM credit_ledger WHERE job_id = :job AND delta > 0")
                .param("job", jobId)
                .query(Integer.class)
                .single();
    }

    List<Entry> recent(UUID userId, int limit) {
        return jdbc.sql("""
                SELECT id, delta, reason, song_id, balance_after, created_at FROM credit_ledger
                 WHERE user_id = :user ORDER BY id DESC LIMIT :limit
                """)
                .param("user", userId)
                .param("limit", limit)
                .query((rs, n) -> new Entry(rs.getLong("id"), rs.getInt("delta"), rs.getString("reason"),
                        rs.getObject("song_id", UUID.class), rs.getInt("balance_after"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    /** Serialises ledger writes and request checks for one account. */
    void lock(UUID userId) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS locked")
                .param("key", "credits:" + userId)
                .query(Integer.class)
                .single();
    }

    private void append(UUID userId, int delta, String reason, UUID jobId, UUID songId) {
        lock(userId);
        int balance = jdbc.sql("SELECT balance_after FROM credit_ledger WHERE user_id = :user ORDER BY id DESC LIMIT 1")
                .param("user", userId)
                .query(Integer.class)
                .optional()
                .orElse(0);
        jdbc.sql("""
                INSERT INTO credit_ledger (user_id, delta, reason, job_id, song_id, balance_after)
                VALUES (:user, :delta, :reason, :job, :song, :balance)
                """)
                .param("user", userId)
                .param("delta", delta)
                .param("reason", reason)
                .param("job", jobId)
                .param("song", songId)
                .param("balance", balance + delta)
                .update();
    }
}
