package ai.beatmind.generation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What the web app sees of a generation job (API-002). Worker states are folded into four public
 * ones: a job waiting for a retry is still {@code queued}, and a dead-lettered job is
 * {@code failed}. A finished job reports {@code succeeded} or {@code failed} only once the API has
 * recorded its takes, ledger events and any refund.
 */
record Generation(
        UUID id,
        UUID songId,
        String kind,
        String status,
        String title,
        String prompt,
        String language,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt,
        List<Take> takes,
        int refundedCredits,
        String error) {

    /** A take the user can play. {@code status} is ready or withheld. */
    record Take(int index, String status, String audioUrl, String format) {}

    boolean finished() {
        return "succeeded".equals(status) || "failed".equals(status);
    }

    static String publicStatus(String dbStatus) {
        return switch (dbStatus) {
            case "queued", "running", "succeeded" -> dbStatus;
            // 'failed' in the table means "will be retried"; only 'dead' is final.
            case "failed" -> "queued";
            default -> "failed";
        };
    }
}
