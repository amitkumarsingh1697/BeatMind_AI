package ai.beatmind.generation;

import ai.beatmind.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** US-038, API-005: edit a take, list its versions, play one, restore one. */
@RestController
class EditController {

    record EditRequest(@Min(1) int take, @NotBlank @Size(max = 20) String op, Map<String, Object> params,
                       @Size(max = 300) String prompt) {}

    private final Edits edits;
    private final SongStore songs;
    private final AudioStore audio;

    EditController(Edits edits, SongStore songs, AudioStore audio) {
        this.edits = edits;
        this.songs = songs;
        this.audio = audio;
    }

    @PostMapping("/v1/songs/{id}/edits")
    ResponseEntity<?> edit(@PathVariable UUID id, @Valid @RequestBody EditRequest req) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        try {
            return ResponseEntity.accepted().body(edits.request(me, id, req.take(), req.op(), req.params(), req.prompt()));
        } catch (SongRequests.Refusal r) {
            return GenerationController.problem(r.status, r.code, r.getMessage());
        }
    }

    @GetMapping("/v1/songs/{id}/takes/{index}/versions")
    List<Edits.Version> versions(@PathVariable UUID id, @PathVariable int index) {
        owned(id);
        return edits.versions(id, index);
    }

    @GetMapping("/v1/songs/{id}/takes/{index}/versions/{version}/audio")
    ResponseEntity<Resource> versionAudio(@PathVariable UUID id, @PathVariable int index, @PathVariable int version) {
        owned(id);
        SongStore.Asset asset = edits.previewOf(id, index, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return SongController.stream(audio, asset.key(), CacheControl.maxAge(Duration.ofHours(1)).cachePrivate(), null);
    }

    @PostMapping("/v1/songs/{id}/takes/{index}/versions/{version}/restore")
    ResponseEntity<?> restore(@PathVariable UUID id, @PathVariable int index, @PathVariable int version) {
        try {
            return ResponseEntity.ok(edits.restore(CurrentUser.get().orElseThrow(), id, index, version));
        } catch (SongRequests.Refusal r) {
            return GenerationController.problem(r.status, r.code, r.getMessage());
        }
    }

    private void owned(UUID id) {
        songs.find(id, CurrentUser.get().orElseThrow().id()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
