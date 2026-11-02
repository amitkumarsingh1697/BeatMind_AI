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
import org.springframework.web.bind.annotation.PostMapping;
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

    private final JdbcClient jdbc;

    AdminUsersController(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
