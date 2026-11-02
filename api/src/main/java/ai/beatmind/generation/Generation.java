package ai.beatmind.generation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What the web app sees of a generation job (API-002). Worker states are folded into four public
 * ones: a job waiting for a retry is still {@code queued}, and a dead-lettered job is
 * {@code failed}.
 */
record Generation(
        UUID id,
        String kind,
        String status,
        String prompt,
        String language,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt,
        List<Take> takes,
        String error) {

    record Take(int index, String audioUrl, String format) {}

    boolean finished() {
        return "succeeded".equals(status) || "failed".equals(status);
    }

    static String publicStatus(String dbStatus) {
        return switch (dbStatus) {
            case "queued", "running", "succeeded" -> dbStatus;
            default -> "failed";
        };
    }
}
