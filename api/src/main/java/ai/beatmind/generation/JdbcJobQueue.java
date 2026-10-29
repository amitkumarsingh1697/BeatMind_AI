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
    public UUID enqueue(Kind kind, String payloadJson, int priority) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO generation_job (id, kind, priority, payload)
                VALUES (:id, :kind, :priority, CAST(:payload AS jsonb))
                """)
                .param("id", id)
                .param("kind", kind.name().toLowerCase(Locale.ROOT))
                .param("priority", priority)
                .param("payload", payloadJson)
                .update();
        return id;
    }
}
