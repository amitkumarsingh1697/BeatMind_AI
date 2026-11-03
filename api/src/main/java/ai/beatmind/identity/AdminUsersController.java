package ai.beatmind.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin changes to one account (API-014, audited). Until payments arrive (Sprints 5 and 6) this is
 * how a team member tries the creator and pro plans, and how a free account gets its own daily
 * allowance (REQ-F-55).
 */
@RestController
class AdminUsersController {

    record PlanChange(@NotBlank @Email String email, @Pattern(regexp = "free|creator|pro") String plan,
                      @Min(0) Integer dailyCapOverride, Boolean clearDailyCapOverride) {}

    record Suspension(@NotBlank @Email String email, boolean suspended, @jakarta.validation.constraints.Size(max = 500) String note) {}

    /** One row of the users table in the console. */
    record UserRow(UUID id, String email, String plan, Integer dailyCapOverride, boolean suspended, String locale,
                   java.time.OffsetDateTime createdAt, java.time.OffsetDateTime lastSignInAt, long songs, long openCases) {}

    private final JdbcClient jdbc;

    AdminUsersController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Accounts, newest sign-in first, filtered by email. */
    @GetMapping("/v1/admin/users")
    java.util.List<UserRow> list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "50") int limit) {
        String filter = q == null ? "" : q.strip().toLowerCase(java.util.Locale.ROOT);
        return jdbc.sql("""
                SELECT u.id, u.email, u.plan, u.daily_cap_override, u.suspended_at IS NOT NULL AS suspended, u.locale,
                       u.created_at, u.last_sign_in_at,
                       (SELECT count(*) FROM song s WHERE s.owner_id = u.id AND s.deleted_at IS NULL) AS songs,
                       (SELECT count(*) FROM moderation_case m WHERE m.user_id = u.id AND m.status IN ('open', 'appealed')) AS cases
                  FROM app_user u
                 WHERE strpos(lower(u.email), :q) > 0
                 ORDER BY u.last_sign_in_at DESC NULLS LAST, u.created_at DESC
                 LIMIT :limit
                """)
                .param("q", filter).param("limit", Math.clamp(limit, 1, 200))
                .query((rs, n) -> {
                    int cap = rs.getInt("daily_cap_override");
                    Integer override = rs.wasNull() ? null : cap;
                    return new UserRow(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("plan"),
                            override, rs.getBoolean("suspended"), rs.getString("locale"),
                            rs.getObject("created_at", java.time.OffsetDateTime.class),
                            rs.getObject("last_sign_in_at", java.time.OffsetDateTime.class),
                            rs.getLong("songs"), rs.getLong("cases"));
                })
                .list();
    }

    /** Pauses or resumes an account: a paused account cannot make songs or lyrics. Audited. */
    @PostMapping("/v1/admin/users/suspend")
    @Transactional
    ResponseEntity<?> suspend(@Valid @RequestBody Suspension req) {
        var id = jdbc.sql("SELECT id FROM app_user WHERE lower(email) = :email")
                .param("email", UserStore.normalize(req.email())).query(UUID.class).optional();
        if (id.isEmpty()) {
            return AuthController.problem(HttpStatus.NOT_FOUND, "No account with that email.");
        }
        jdbc.sql("UPDATE app_user SET suspended_at = CASE WHEN :s THEN coalesce(suspended_at, now()) END WHERE id = :id")
                .param("s", req.suspended()).param("id", id.get()).update();
        jdbc.sql("""
                INSERT INTO admin_audit (id, actor_user_id, action, target, detail)
                VALUES (:id, :actor, :action, :target, :detail)
                """)
                .param("id", UUID.randomUUID())
                .param("actor", CurrentUser.get().orElseThrow().id())
                .param("action", req.suspended() ? "user.suspend" : "user.resume")
                .param("target", "app_user:" + id.get())
                .param("detail", req.note())
                .update();
        return ResponseEntity.ok(Map.of("id", id.get(), "suspended", req.suspended()));
    }

    /** The latest admin actions, for the console's audit view. */
    @GetMapping("/v1/admin/audit")
    java.util.List<Map<String, Object>> audit(@RequestParam(defaultValue = "50") int limit) {
        return jdbc.sql("""
                SELECT a.created_at, u.email AS actor, a.action, a.target, a.detail
                  FROM admin_audit a LEFT JOIN app_user u ON u.id = a.actor_user_id
                 ORDER BY a.created_at DESC LIMIT :limit
                """)
                .param("limit", Math.clamp(limit, 1, 200))
                .query((rs, n) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("at", rs.getObject("created_at", java.time.OffsetDateTime.class));
                    row.put("actor", rs.getString("actor"));
                    row.put("action", rs.getString("action"));
                    row.put("target", rs.getString("target"));
                    row.put("detail", rs.getString("detail"));
                    return row;
                })
                .list();
    }

    @PostMapping("/v1/admin/users/plan")
    @Transactional
    ResponseEntity<?> change(@Valid @RequestBody PlanChange req) {
        var user = jdbc.sql("SELECT id, plan, daily_cap_override FROM app_user WHERE lower(email) = :email")
                .param("email", UserStore.normalize(req.email()))
                .query((rs, n) -> Map.of("id", rs.getObject("id", UUID.class), "plan", rs.getString("plan")))
                .optional();
        if (user.isEmpty()) {
            return AuthController.problem(HttpStatus.NOT_FOUND, "No account with that email.");
        }
        UUID id = (UUID) user.get().get("id");
        String plan = req.plan() == null ? (String) user.get().get("plan") : req.plan();
        boolean clear = Boolean.TRUE.equals(req.clearDailyCapOverride());
        jdbc.sql("""
                UPDATE app_user SET plan = :plan,
                       daily_cap_override = CASE WHEN CAST(:clear AS boolean) THEN NULL ELSE coalesce(CAST(:cap AS integer), daily_cap_override) END
                 WHERE id = :id
                """)
                .param("plan", plan)
                .param("clear", clear)
                .param("cap", req.dailyCapOverride())
                .param("id", id)
                .update();
        jdbc.sql("""
                INSERT INTO admin_audit (id, actor_user_id, action, target, detail)
                VALUES (:id, :actor, 'user.plan', :target, :detail)
                """)
                .param("id", UUID.randomUUID())
                .param("actor", CurrentUser.get().orElseThrow().id())
                .param("target", "app_user:" + id)
                .param("detail", "plan=" + plan + (clear ? " cap=default"
                        : req.dailyCapOverride() != null ? " cap=" + req.dailyCapOverride() : ""))
                .update();
        return ResponseEntity.ok(Map.of("id", id, "plan", plan));
    }
}
