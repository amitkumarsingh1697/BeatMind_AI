package ai.beatmind.generation;

import ai.beatmind.identity.CurrentUser;
import ai.beatmind.moderation.ModerationCases;
import ai.beatmind.moderation.TextModeration;
import ai.beatmind.provenance.ProvenanceLedger;
import ai.beatmind.provenance.ProvenanceLedger.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sharing (US-024, FEAT-13, REQ-SEC-03): the owner makes, lists and revokes links; anyone with a
 * live link hears the song on a public page with its AI label. The title and lyrics are screened
 * again before a link is made, because moderation rules change after a song is created.
 */
@RestController
class ShareController {

    record NewShare(@NotNull @Pattern(regexp = "private|public") String visibility, @Min(1) Integer takeIndex,
                    @Pattern(regexp = "en|hi") String locale) {}

    /** The public view: what a listener sees. No owner details. */
    record SharedSong(String title, String languageTag, String mode, String lyrics, boolean instrumental,
                      int durationSeconds, String visibility, boolean aiGenerated, String label, String audioUrl,
                      String format, String certificateId, OffsetDateTime createdAt) {}

    private final Shares shares;
    private final SongStore songs;
    private final AudioStore audio;
    private final TextModeration moderation;
    private final ModerationCases cases;
    private final ProvenanceLedger ledger;
    private final Licences licences;

    ShareController(Shares shares, SongStore songs, AudioStore audio, TextModeration moderation, ModerationCases cases,
                    ProvenanceLedger ledger, Licences licences) {
        this.shares = shares;
        this.songs = songs;
        this.audio = audio;
        this.moderation = moderation;
        this.cases = cases;
        this.ledger = ledger;
        this.licences = licences;
    }

    @GetMapping("/v1/songs/{id}/shares")
    List<Shares.Share> list(@PathVariable UUID id) {
        owned(id);
        return shares.list(id);
    }

    @PostMapping("/v1/songs/{id}/shares")
    @Transactional
    ResponseEntity<?> create(@PathVariable UUID id, @Valid @RequestBody NewShare req) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        SongStore.Song song = owned(id);
        if ("disabled".equals(song.rightsStatus())) {
            return GenerationController.problem(HttpStatus.FORBIDDEN, "disabled", "This song is unavailable.");
        }
        int index = req.takeIndex() == null ? 1 : req.takeIndex();
        SongStore.Take take = songs.take(id, index).filter(t -> "ready".equals(t.status())).orElse(null);
        if (take == null || SongViews.preview(take) == null) {
            return GenerationController.problem(HttpStatus.CONFLICT, "not_ready", "Only a finished take can be shared.");
        }
        if (shares.list(id).size() >= Shares.MAX_ACTIVE_PER_SONG) {
            return GenerationController.problem(HttpStatus.CONFLICT, "too_many_links",
                    "This song has " + Shares.MAX_ACTIVE_PER_SONG + " live links. Revoke one first.");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", song.title());
        fields.put("lyrics", song.lyrics());
        TextModeration.Verdict verdict = moderation.check(fields);
        String screened = song.title() + "\n" + (song.lyrics() == null ? "" : song.lyrics());
        if (verdict.blocked() && !cases.cleared(me.id(), screened)) {
            return GenerationController.blocked(verdict, cases.recordTextBlock(me.id(), "text_block", verdict, screened));
        }
        Shares.Share made = shares.create(id, req.visibility(), index, req.locale() == null ? "en" : req.locale());
        ledger.append(id, "shared", Actor.HUMAN, Map.of("shareId", made.id().toString(), "visibility", req.visibility(),
                "takeIndex", index));
        return ResponseEntity.status(HttpStatus.CREATED).body(made);
    }

    @DeleteMapping("/v1/songs/{id}/shares/{shareId}")
    @Transactional
    ResponseEntity<Void> revoke(@PathVariable UUID id, @PathVariable UUID shareId) {
        owned(id);
        if (!shares.revoke(id, shareId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        ledger.append(id, "share_revoked", Actor.HUMAN, Map.of("shareId", shareId.toString()));
        return ResponseEntity.noContent().build();
    }

    /** The public song page's data. 404 for an unknown link, 410 for a revoked or removed one. */
    @GetMapping("/v1/shared/{token}")
    ResponseEntity<?> view(@PathVariable String token) {
        Shares.Shared link = shares.open(token).orElse(null);
        if (link == null) {
            if (shares.wasRevoked(token)) {
                return GenerationController.problem(HttpStatus.GONE, "revoked", "The owner has stopped sharing this song.");
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        SongStore.Song song = songs.byId(link.songId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if ("disabled".equals(song.rightsStatus())) {
            return GenerationController.problem(HttpStatus.GONE, "disabled", "This song has been removed.");
        }
        SongStore.Take take = songs.take(song.id(), link.takeIndex()).filter(t -> "ready".equals(t.status()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        shares.counted(link.shareId());
        String certificate = licences.latestForSong(song.id()).map(Licences.Certificate::id).orElse(null);
        SharedSong body = new SharedSong(song.title(), song.languageTag(), song.mode(), song.lyrics(), song.instrumental(),
                song.durationSeconds(), link.visibility(), true, SongViews.AI_LABEL, "/v1/shared/" + token + "/audio",
                SongViews.previewFormat(take), certificate, song.createdAt());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** The shared take's preview audio (the light MP3), with range support for seeking. */
    @GetMapping("/v1/shared/{token}/audio")
    ResponseEntity<Resource> audio(@PathVariable String token) {
        Shares.Shared link = shares.open(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        SongStore.Song song = songs.byId(link.songId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if ("disabled".equals(song.rightsStatus())) {
            throw new ResponseStatusException(HttpStatus.GONE);
        }
        SongStore.Asset asset = songs.take(song.id(), link.takeIndex()).filter(t -> "ready".equals(t.status()))
                .map(SongViews::preview)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        AudioStore.Audio file = audio.open(asset.key()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try (InputStream in = file.body()) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .contentType(MediaType.parseMediaType(file.contentType()))
                    .body(new ByteArrayResource(in.readAllBytes()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private SongStore.Song owned(UUID id) {
        return songs.find(id, CurrentUser.get().orElseThrow().id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
