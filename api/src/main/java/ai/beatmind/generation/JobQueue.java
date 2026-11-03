package ai.beatmind.generation;

import java.util.UUID;

/**
 * Hand-off from the API to workers (ARCH-03). The PostgreSQL implementation can be swapped for a
 * broker when throughput needs it (ADR-005); workers claim jobs on their side.
 */
public interface JobQueue {

    enum Kind { SONG, BEAT, LYRICS, EDIT, VERIFY, STEMS }

    /** Queues a job with no owner (system jobs) and returns its id. */
    default UUID enqueue(Kind kind, String payloadJson, int priority) {
        return enqueue(kind, payloadJson, priority, null);
    }

    /** Queues a job for a user and returns its id. {@code payloadJson} must be a JSON object. */
    default UUID enqueue(Kind kind, String payloadJson, int priority, UUID ownerId) {
        return enqueue(kind, payloadJson, priority, ownerId, "self", null);
    }

    /**
     * As above, for a given runner: "self" (our GPU workers) or "lyria" (the paid fallback, US-016)
     * with its estimated cost in US dollars.
     */
    UUID enqueue(Kind kind, String payloadJson, int priority, UUID ownerId, String provider, java.math.BigDecimal costUsd);
}
