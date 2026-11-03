package ai.beatmind.generation;

import ai.beatmind.identity.CurrentUser;
import ai.beatmind.moderation.ModerationCases;
import ai.beatmind.moderation.TextModeration;
import ai.beatmind.provenance.ProvenanceLedger;
import ai.beatmind.provenance.ProvenanceLedger.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Library, player and downloads (US-022, US-023, US-026): API-003 songs, take audio, API-007
 * signed downloads, the song's provenance history and the credit ledger.
 */
@RestController
class SongController {

    /** Previews up to this size are served from memory so the player can seek (HTTP ranges). */
    private static final long MAX_PREVIEW_BYTES = 32L * 1024 * 1024;

    record Page(List<SongViews.SongView> songs, String next) {}

    record Rename(@NotNull @Size(max = 1000) String title) {}

    record Appeal(@Size(max = 1000) String note) {}

    record DownloadRequest(@NotNull @Pattern(regexp = "mp3|wav") String format) {}

    record DownloadLink(String url, Instant expiresAt, String format, String quality, String fileName, String label,
                        String rights) {}

    record CreditsView(Credits.Allowance allowance, List<Credits.Entry> entries) {}

    private final SongStore songs;
    private final AudioStore audio;
    private final ProvenanceLedger ledger;
    private final DownloadTokens tokens;
    private final Credits credits;
    private final GenerationStore generations;
    private final TextModeration moderation;
    private final ModerationCases cases;
    private final Licences licences;
    private final Takedowns takedowns;
    private final Shares shares;

    SongController(SongStore songs, AudioStore audio, ProvenanceLedger ledger, DownloadTokens tokens, Credits credits,
                   GenerationStore generations, TextModeration moderation, ModerationCases cases, Licences licences,
                   Takedowns takedowns, Shares shares) {
        this.licences = licences;
        this.takedowns = takedowns;
        this.shares = shares;
        this.moderation = moderation;
        this.cases = cases;
        this.generations = generations;
        this.songs = songs;
        this.audio = audio;
        this.ledger = ledger;
        this.tokens = tokens;
        this.credits = credits;
    }

    /** The library: newest first, with Unicode-aware search ({@code q}) and paging ({@code before}). */
    @GetMapping("/v1/songs")
    Page list(@RequestParam(required = false) String q, @RequestParam(required = false) OffsetDateTime before,
              @RequestParam(defaultValue = "20") int limit) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        int size = Math.clamp(limit, 1, 50);
        List<SongStore.Song> found = songs.list(me.id(), q, before, size + 1);
        boolean more = found.size() > size;
        List<SongStore.Song> page = more ? found.subList(0, size) : found;
        Map<UUID, List<SongStore.Take>> takes = songs.takes(page.stream().map(SongStore.Song::id).toList());
        List<SongViews.SongView> views = page.stream()
                .map(s -> SongViews.of(s, takes.getOrDefault(s.id(), List.of()), me.plan()))
                .toList();
        return new Page(views, more ? page.getLast().createdAt().toString() : null);
    }

    @GetMapping("/v1/songs/{id}")
    SongViews.SongView get(@PathVariable UUID id) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        SongStore.Song song = owned(id, me);
        String certificate = licences.latestForSong(id).map(Licences.Certificate::id).orElse(null);
        Takedowns.OwnerView takedown = takedowns.forOwner(id).orElse(null);
        return SongViews.of(song, songs.takes(id), me.plan(), certificate, takedown);
    }

    @PatchMapping("/v1/songs/{id}")
    @Transactional
    ResponseEntity<?> rename(@PathVariable UUID id, @Valid @RequestBody Rename req) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        owned(id, me);
        String title = TextRules.nfc(req.title().strip());
        if (title.isEmpty()) {
            return GenerationController.problem(HttpStatus.BAD_REQUEST, "invalid_title", "Give the song a title.");
        }
        if (TextRules.graphemes(title) > SongRequests.MAX_TITLE_GRAPHEMES) {
            return GenerationController.problem(HttpStatus.BAD_REQUEST, "invalid_title",
                    "Keep the title to " + SongRequests.MAX_TITLE_GRAPHEMES + " characters.");
        }
        TextModeration.Verdict verdict = moderation.check("title", title);
        if (verdict.blocked() && !cases.cleared(me.id(), title)) {
            return GenerationController.blocked(verdict, cases.recordTextBlock(me.id(), "text_block", verdict, title));
        }
        songs.rename(id, me.id(), title);
        ledger.append(id, "renamed", Actor.HUMAN, Map.of("titleSha256", SongRequests.sha256(title)));
        return ResponseEntity.ok(get(id));
    }

    /** Removes the song from the library at once; files are purged after 30 days (TDD 5.2). */
    @DeleteMapping("/v1/songs/{id}")
    @Transactional
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        owned(id, me);
        songs.softDelete(id, me.id());
        shares.revokeAll(id);
        ledger.append(id, "deleted", Actor.HUMAN, Map.of());
        return ResponseEntity.noContent().build();
    }

    /** Take audio for the in-app player. Supports range requests so the player can seek. */
    @GetMapping("/v1/songs/{id}/takes/{index}/audio")
    ResponseEntity<Resource> takeAudio(@PathVariable UUID id, @PathVariable int index) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        owned(id, me);
        SongStore.Take take = songs.take(id, index)
                .filter(t -> "ready".equals(t.status()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        SongStore.Asset asset = SongViews.preview(take);
        if (asset == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return stream(asset.key(), CacheControl.maxAge(Duration.ofHours(1)).cachePrivate(), null);
    }

    /** REQ-F-59: the owner asks a person to review a withheld take (72-hour target). */
    @PostMapping("/v1/songs/{id}/takes/{index}/appeal")
    ResponseEntity<?> appealTake(@PathVariable UUID id, @PathVariable int index, @Valid @RequestBody Appeal req) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        owned(id, me);
        songs.take(id, index).filter(t -> "withheld".equals(t.status()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        UUID caseId = cases.forTake(id, index).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!cases.appeal(caseId, me.id(), req.note())) {
            return GenerationController.problem(HttpStatus.CONFLICT, "already_appealed",
                    "You have already asked for a review of this take.");
        }
        ledger.append(id, "appeal_requested", Actor.HUMAN, Map.of("takeIndex", index));
        return ResponseEntity.accepted().body(Map.of("status", "appealed", "caseId", caseId));
    }

    /** Sprint 1 address of a take, kept for the smoke test and older pages. */
    @GetMapping("/v1/generations/{jobId}/takes/{index}/audio")
    ResponseEntity<Resource> generationTakeAudio(@PathVariable UUID jobId, @PathVariable int index) {
        UUID songId = generations.songOf(jobId, CurrentUser.get().orElseThrow().id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return takeAudio(songId, index);
    }

    /** API-007: a signed link to a labelled file, valid for 15 minutes, in a format the plan allows. */
    @PostMapping("/v1/songs/{id}/takes/{index}/downloads")
    @Transactional
    ResponseEntity<?> download(@PathVariable UUID id, @PathVariable int index, @Valid @RequestBody DownloadRequest req) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        SongStore.Song song = owned(id, me);
        if ("disabled".equals(song.rightsStatus())) {
            return GenerationController.problem(HttpStatus.FORBIDDEN, "disabled", "This song is unavailable.");
        }
        SongStore.Take take = songs.take(id, index).filter(t -> "ready".equals(t.status()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String assetName = Entitlements.assetFor(me.plan(), song.rightsStatus(), req.format());
        if (assetName == null) {
            return GenerationController.problem(HttpStatus.FORBIDDEN, "plan",
                    "WAV downloads come with the Creator and Pro plans. Free plans download MP3.");
        }
        SongStore.Asset asset = take.assets().get(assetName);
        if (asset == null) {
            return GenerationController.problem(HttpStatus.CONFLICT, "not_labelled",
                    "This take was made before labelled downloads existed. Make a new take to download it.");
        }
        Instant expires = tokens.expiry();
        String token = tokens.issue(new DownloadTokens.Grant(id, index, assetName, req.format(), expires));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("takeIndex", index);
        payload.put("format", assetName);
        if (asset.sha256() != null) {
            payload.put("fileSha256", asset.sha256());
        }
        ledger.append(id, "exported", Actor.HUMAN, payload);
        return ResponseEntity.ok(new DownloadLink("/v1/downloads/" + token, expires, req.format(), quality(assetName),
                fileName(song, index, req.format()), SongViews.AI_LABEL, SongViews.rights(song.rightsStatus())));
    }

    /** Serves a signed download. No session needed: the signature is the permission. */
    @GetMapping("/v1/downloads/{token}")
    ResponseEntity<Resource> fetch(@PathVariable String token) {
        DownloadTokens.Grant grant = tokens.check(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "This download link has expired."));
        String latinTitle = songs.title(grant.songId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        SongStore.Asset asset = songs.take(grant.songId(), grant.takeIndex())
                .map(t -> t.assets().get(grant.asset()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String name = fileName(latinTitle, grant.takeIndex(), grant.format());
        return stream(asset.key(), CacheControl.noStore(), ContentDisposition.attachment().filename(name).build());
    }

    /** The song's provenance history and whether its chain verifies (REQ-F-31). */
    @GetMapping("/v1/songs/{id}/provenance")
    Map<String, Object> provenance(@PathVariable UUID id) {
        owned(id, CurrentUser.get().orElseThrow());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", ledger.events(id));
        body.put("verification", ledger.verify(id));
        return body;
    }

    /** Allowance for the current window and the latest credit ledger entries (REQ-F-56). */
    @GetMapping("/v1/credits")
    CreditsView credits() {
        CurrentUser me = CurrentUser.get().orElseThrow();
        return new CreditsView(credits.allowance(me.id(), me.plan()), credits.recent(me.id(), 20));
    }

    private SongStore.Song owned(UUID id, CurrentUser me) {
        return songs.find(id, me.id()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private ResponseEntity<Resource> stream(String key, CacheControl cache, ContentDisposition disposition) {
        return stream(audio, key, cache, disposition);
    }

    static ResponseEntity<Resource> stream(AudioStore audio, String key, CacheControl cache, ContentDisposition disposition) {
        AudioStore.Audio file = audio.open(key).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var response = ResponseEntity.ok()
                .cacheControl(cache)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(MediaType.parseMediaType(file.contentType()));
        if (disposition != null) {
            response.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        }
        if (file.length() >= 0 && file.length() <= MAX_PREVIEW_BYTES) {
            try (InputStream in = file.body()) {
                return response.body(new ByteArrayResource(in.readAllBytes()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return response.body(new InputStreamResource(file.body()));
    }

    static String quality(String asset) {
        return switch (asset) {
            case "wav" -> "WAV, 48 kHz, 24-bit";
            case "mp3_320" -> "MP3, 320 kbps";
            default -> "MP3, 192 kbps, personal listening";
        };
    }

    /** ASCII file name from the Latin title, e.g. "raat-ka-safar-take-1.mp3". */
    static String fileName(SongStore.Song song, int index, String format) {
        return fileName(song.titleLatin(), index, format);
    }

    static String fileName(String latinTitle, int index, String format) {
        String base = latinTitle.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isEmpty()) {
            base = "beatmind-song";
        }
        if (base.length() > 60) {
            base = base.substring(0, 60).replaceAll("-$", "");
        }
        return base + "-take-" + index + "." + format;
    }
}
