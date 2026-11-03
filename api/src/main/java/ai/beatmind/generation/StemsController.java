package ai.beatmind.generation;

import ai.beatmind.identity.CurrentUser;
import ai.beatmind.provenance.ProvenanceLedger;
import ai.beatmind.provenance.ProvenanceLedger.Actor;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** US-039, API-007: ask for the four stems of a take, see them, and download each as a signed link. */
@RestController
class StemsController {

    private final Stems stems;
    private final SongStore songs;
    private final AudioStore audio;
    private final DownloadTokens tokens;
    private final ProvenanceLedger ledger;

    StemsController(Stems stems, SongStore songs, AudioStore audio, DownloadTokens tokens, ProvenanceLedger ledger) {
        this.stems = stems;
        this.songs = songs;
        this.audio = audio;
        this.tokens = tokens;
        this.ledger = ledger;
    }

    @PostMapping("/v1/songs/{id}/takes/{index}/stems")
    ResponseEntity<?> request(@PathVariable UUID id, @PathVariable int index) {
        try {
            Stems.View view = stems.request(CurrentUser.get().orElseThrow(), id, index);
            return ResponseEntity.status("ready".equals(view.status()) ? HttpStatus.OK : HttpStatus.ACCEPTED).body(view);
        } catch (SongRequests.Refusal r) {
            return GenerationController.problem(r.status, r.code, r.getMessage());
        }
    }

    @GetMapping("/v1/songs/{id}/takes/{index}/stems")
    Stems.View current(@PathVariable UUID id, @PathVariable int index) {
        owned(id, CurrentUser.get().orElseThrow());
        return stems.current(id, index).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/v1/songs/{id}/takes/{index}/stems/{name}/downloads")
    @Transactional
    ResponseEntity<?> download(@PathVariable UUID id, @PathVariable int index, @PathVariable String name) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        SongStore.Song song = owned(id, me);
        if (!Stems.NAMES.contains(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if ("disabled".equals(song.rightsStatus())) {
            return GenerationController.problem(HttpStatus.FORBIDDEN, "disabled", "This song is unavailable.");
        }
        if (!Stems.allowed(me.plan(), song.rightsStatus())) {
            return GenerationController.problem(HttpStatus.FORBIDDEN, "plan", "Stems come with the Creator and Pro plans.");
        }
        Stems.View view = stems.current(id, index).filter(v -> "ready".equals(v.status()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        SongStore.Asset asset = stems.asset(id, index, view.version(), name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Instant expires = tokens.expiry();
        String token = tokens.issue(new DownloadTokens.Grant(id, index, "stem:" + name + ":" + view.version(), "wav", expires));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("takeIndex", index);
        payload.put("format", "stem_" + name);
        payload.put("version", view.version());
        if (asset.sha256() != null) {
            payload.put("fileSha256", asset.sha256());
        }
        ledger.append(id, "exported", Actor.HUMAN, payload);
        return ResponseEntity.ok(new SongController.DownloadLink("/v1/downloads/stems/" + token, expires, "wav",
                "WAV stem (" + name + "), 48 kHz, 24-bit", fileName(song.titleLatin(), index, name), SongViews.AI_LABEL,
                SongViews.rights(song.rightsStatus())));
    }

    /** Serves a signed stem download; the signature is the permission. */
    @GetMapping("/v1/downloads/stems/{token}")
    ResponseEntity<Resource> fetch(@PathVariable String token) {
        DownloadTokens.Grant grant = tokens.check(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "This download link has expired."));
        String[] parts = grant.asset().split(":");
        if (parts.length != 3 || !"stem".equals(parts[0])) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        int version;
        try {
            version = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String latin = songs.title(grant.songId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        SongStore.Asset asset = stems.asset(grant.songId(), grant.takeIndex(), version, parts[1])
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return SongController.stream(audio, asset.key(), CacheControl.noStore(),
                ContentDisposition.attachment().filename(fileName(latin, grant.takeIndex(), parts[1])).build());
    }

    static String fileName(String latinTitle, int index, String stem) {
        return SongController.fileName(latinTitle, index, "wav").replaceAll("\\.wav$", "-" + stem + ".wav");
    }

    private SongStore.Song owned(UUID id, CurrentUser me) {
        return songs.find(id, me.id()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
