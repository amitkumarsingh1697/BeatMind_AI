package ai.beatmind.generation;

import ai.beatmind.moderation.ModerationCases;
import ai.beatmind.provenance.ProvenanceLedger;
import ai.beatmind.provenance.ProvenanceLedger.Actor;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin views of songs for the moderation queue (US-031): listen to any take, including withheld
 * ones, and release a withheld take when its case is overturned (REQ-F-59).
 */
@RestController
class AdminSongsController {

    private final JdbcClient jdbc;
    private final SongStore songs;
    private final AudioStore audio;
    private final ProvenanceLedger ledger;

    AdminSongsController(JdbcClient jdbc, SongStore songs, AudioStore audio, ProvenanceLedger ledger) {
        this.jdbc = jdbc;
        this.songs = songs;
        this.audio = audio;
        this.ledger = ledger;
    }

    @GetMapping("/v1/admin/songs/{id}/takes/{index}/audio")
    ResponseEntity<Resource> takeAudio(@PathVariable UUID id, @PathVariable int index) {
        SongStore.Take take = songs.take(id, index).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        SongStore.Asset asset = SongViews.preview(take);
        if (asset == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        AudioStore.Audio file = audio.open(asset.key()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try (var in = file.body()) {
            return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ZERO).cachePrivate())
                    .contentType(MediaType.parseMediaType(file.contentType()))
                    .body(new ByteArrayResource(in.readAllBytes()));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** An overturned similarity case makes the take playable and downloadable again. */
    @EventListener
    void onDecision(ModerationCases.CaseDecided decided) {
        if (!decided.overturned() || !"similarity".equals(decided.kind()) || decided.songId() == null
                || decided.takeIdx() == null) {
            return;
        }
        int changed = jdbc.sql("UPDATE take SET status = 'ready' WHERE song_id = :song AND idx = :idx AND status = 'withheld'")
                .param("song", decided.songId()).param("idx", decided.takeIdx()).update();
        if (changed == 1) {
            ledger.append(decided.songId(), "take_released", Actor.SYSTEM, Map.of(
                    "takeIndex", decided.takeIdx(), "reason", "human review overturned the similarity screen"));
        }
    }
}
