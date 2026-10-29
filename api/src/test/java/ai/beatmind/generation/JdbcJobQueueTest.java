package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;

import ai.beatmind.DatabaseTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@DatabaseTest
class JdbcJobQueueTest {

    @Autowired
    JobQueue queue;

    @Autowired
    JdbcClient jdbc;

    @Test
    void enqueuedJobIsQueuedForWorkers() {
        UUID id = queue.enqueue(JobQueue.Kind.SONG, "{\"prompt\":\"lofi chai morning\"}", 5);

        var row = jdbc.sql("SELECT kind, status, priority, payload->>'prompt' FROM generation_job WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getInt(3) + "|" + rs.getString(4))
                .single();
        assertThat(row).isEqualTo("song|queued|5|lofi chai morning");
    }
}
