package ai.beatmind.generation;

import ai.beatmind.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The free daily cap an admin sets (REQ-F-55, API-014). Every change is audited. */
@RestController
class AdminCapsController {

    record Caps(@NotNull @Min(0) @Max(1000) Integer freeDailyCredits) {}

    private final Settings settings;
    private final Entitlements entitlements;
    private final JdbcClient jdbc;

    AdminCapsController(Settings settings, Entitlements entitlements, JdbcClient jdbc) {
        this.settings = settings;
        this.entitlements = entitlements;
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/admin/caps")
    Map<String, Object> get() {
        return Map.of(
                "freeDailyCredits", settings.freeDailyCredits(),
                "defaultFreeDailyCredits", entitlements.freeDailyCredits(),
                "creditsPerRequest", Entitlements.CREDITS_PER_REQUEST,
                "concurrency", Map.of("free", 1, "creator", 2, "pro", 4),
                "userRequestsPerTenMinutes", entitlements.userRequestsPerTenMinutes(),
                "ipRequestsPerHour", entitlements.ipRequestsPerHour());
    }

    @PostMapping("/v1/admin/caps")
    @Transactional
    Map<String, Object> set(@Valid @RequestBody Caps caps) {
        int before = settings.freeDailyCredits();
        UUID actor = CurrentUser.get().orElseThrow().id();
        settings.setFreeDailyCredits(caps.freeDailyCredits(), actor);
        jdbc.sql("""
                INSERT INTO admin_audit (id, actor_user_id, action, target, detail)
                VALUES (:id, :actor, 'caps.free_daily_credits', 'app_setting:free_daily_credits', :detail)
                """)
                .param("id", UUID.randomUUID())
                .param("actor", actor)
                .param("detail", before + " -> " + caps.freeDailyCredits())
                .update();
        return get();
    }
}
