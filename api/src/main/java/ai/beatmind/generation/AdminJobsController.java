package ai.beatmind.generation;

import ai.beatmind.identity.CurrentUser;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dead-letter view for operators (US-013, API-014). Admin role only (see SecurityConfig); every
 * retry is written to {@code admin_audit}. The full admin console arrives with US-031.
 */
@RestController
class AdminJobsController {

    record JobRow(UUID id, String kind, String status, int attempts, String error, UUID userId,
                  OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    private final JdbcClient jdbc;

    AdminJobsController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/admin/jobs")
    List<JobRow> list(@RequestParam(defaultValue = "dead") String status) {
        return jdbc.sql("""
                SELECT id, kind, status, attempts, error, user_id, created_at, updated_at
                  FROM generation_job WHERE status = :status ORDER BY updated_at DESC LIMIT 100
                """)
                .param("status", status)
                .query((rs, n) -> new JobRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getString("error"),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }

    /** Puts a dead job back in the queue with fresh attempts. */
    @PostMapping("/v1/admin/jobs/{id}/retry")
    @Transactional
    ResponseEntity<Void> retry(@PathVariable UUID id) {
        int updated = jdbc.sql("""
                UPDATE generation_job
                   SET status = 'queued', attempts = 0, available_at = now(), error = NULL,
                       finished_at = NULL, updated_at = now()
                 WHERE id = :id AND status = 'dead'
                """).param("id", id).update();
        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }
        jdbc.sql("""
                INSERT INTO admin_audit (id, actor_user_id, action, target)
                VALUES (:id, :actor, 'job.retry', :target)
                """)
                .param("id", UUID.randomUUID())
                .param("actor", CurrentUser.get().orElseThrow().id())
                .param("target", "generation_job:" + id)
                .update();
        return ResponseEntity.accepted().build();
    }
}
