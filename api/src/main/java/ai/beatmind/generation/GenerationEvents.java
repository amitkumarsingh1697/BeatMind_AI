package ai.beatmind.generation;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live status for API-002. Each open stream runs on a virtual thread that checks the job about
 * once a second and sends a {@code status} event when it changes. Polling keeps the worker side
 * simple; LISTEN/NOTIFY can replace it if many streams are open at once.
 */
@Component
class GenerationEvents {

    static final Duration MAX_STREAM = Duration.ofMinutes(10);

    private final GenerationStore store;
    private final Duration pollEvery;

    GenerationEvents(GenerationStore store, @Value("${beatmind.generation.poll-every:1s}") Duration pollEvery) {
        this.store = store;
        this.pollEvery = pollEvery;
    }

    SseEmitter stream(UUID id, UUID owner) {
        SseEmitter emitter = new SseEmitter(MAX_STREAM.toMillis());
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));
        Thread.ofVirtual().name("sse-" + id).start(() -> run(emitter, closed, id, owner));
        return emitter;
    }

    private void run(SseEmitter emitter, AtomicBoolean closed, UUID id, UUID owner) {
        Instant deadline = Instant.now().plus(MAX_STREAM);
        String lastSent = null;
        try {
            while (!closed.get() && Instant.now().isBefore(deadline)) {
                Optional<Generation> current = store.find(id, owner);
                if (current.isEmpty()) {
                    break;
                }
                Generation g = current.get();
                String fingerprint = g.status() + ":" + g.takes().size();
                if (!Objects.equals(fingerprint, lastSent)) {
                    emitter.send(SseEmitter.event().name("status").data(g, MediaType.APPLICATION_JSON));
                    lastSent = fingerprint;
                }
                if (g.finished()) {
                    break;
                }
                Thread.sleep(pollEvery);
            }
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            // The browser went away; nothing to clean up.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (RuntimeException e) {
            emitter.completeWithError(e);
        }
    }
}
