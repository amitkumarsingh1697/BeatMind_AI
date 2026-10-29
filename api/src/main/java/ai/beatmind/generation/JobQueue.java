package ai.beatmind.generation;

import java.util.UUID;

/**
 * Hand-off from the API to workers (ARCH-03). The PostgreSQL implementation can be swapped for a
 * broker when throughput needs it (ADR-005); workers claim jobs on their side.
 */
public interface JobQueue {

    enum Kind { SONG, BEAT, LYRICS }

    /** Queues a job and returns its id. {@code payloadJson} must be a JSON object. */
    UUID enqueue(Kind kind, String payloadJson, int priority);
}
