package ai.beatmind.generation;

import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcJobQueue implements JobQueue {

    private final JdbcClient jdbc;

    JdbcJobQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID enqueue(Kind kind, String payloadJson, int priority, UUID ownerId, String provider,
                        java.math.BigDecimal costUsd) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO generation_job (id, kind, priority, payload, user_id, provider, cost_estimate_usd)
                VALUES (:id, :kind, :priority, CAST(:payload AS jsonb), :owner, :provider, :cost)
                """)
                .param("provider", provider)
                .param("cost", costUsd)
                .param("id", id)
                .param("kind", kind.name().toLowerCase(Locale.ROOT))
                .param("priority", priority)
                .param("payload", payloadJson)
                .param("owner", ownerId)
                .update();
        return id;
    }
}
