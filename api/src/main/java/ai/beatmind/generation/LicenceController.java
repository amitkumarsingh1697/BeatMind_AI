package ai.beatmind.generation;

import ai.beatmind.identity.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** US-025: API-006 commercial licence, API-012 public certificate view, and the monthly quota. */
@RestController
class LicenceController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'IST'", Locale.ENGLISH);

    /**
     * A certificate as anyone with its id sees it. It shows no email or name: the account is named
     * by a short reference (DPIA, data minimisation).
     */
    record CertificateView(String id, String scope, String status, String title, String titleLatin,
                           String languageTag, String created, OffsetDateTime createdAt, String planAtCreation,
                           String accountReference, String grant, String aiDisclosure, List<String> humanInputs,
                           String masterSha256, OffsetDateTime issuedAt, OffsetDateTime revokedAt, String attribution,
                           List<String> conditions, String notice) {}

    private final Licences licences;

    LicenceController(Licences licences) {
        this.licences = licences;
    }

    @PostMapping("/v1/songs/{id}/commercial-licence")
    ResponseEntity<?> licence(@PathVariable UUID id) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        try {
            Licences.Certificate c = licences.issue(me.id(), id);
            return ResponseEntity.status(HttpStatus.CREATED).body(view(c));
        } catch (SongRequests.Refusal r) {
            return GenerationController.problem(r.status, r.code, r.getMessage());
        }
    }

    @GetMapping("/v1/licences/quota")
    Licences.Quota quota() {
        return licences.quota(CurrentUser.get().orElseThrow().id());
    }

    /** Public: anyone holding the certificate id can check it (API-012). */
    @GetMapping("/v1/certificates/{id}")
    ResponseEntity<CertificateView> certificate(@PathVariable String id) {
        if (!id.matches("BM-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Licences.Certificate c = licences.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(view(c));
    }

    static CertificateView view(Licences.Certificate c) {
        boolean free = "commercial_free".equals(c.scope());
        String grant = free
                ? "BeatMind has granted the account holder a non-exclusive, worldwide, perpetual licence to use this "
                        + "song commercially, with attribution (Rights and Licence Terms, section 3.3). BeatMind keeps "
                        + "ownership of the song."
                : "BeatMind has assigned to the account holder all rights BeatMind holds in this song (Rights and "
                        + "Licence Terms, section 2).";
        List<String> human = new ArrayList<>();
        human.add(c.humanLyrics() ? "Lyrics written or edited by the account holder" : "Lyrics not written by the account holder");
        human.add("Description and settings chosen by the account holder");
        List<String> conditions = free
                ? List.of("Credit \"" + Licences.ATTRIBUTION + "\" where your work's credits appear.",
                        "Do not register the song with a content-identification system (such as YouTube Content ID) as your own.",
                        "Do not release the song by itself on streaming services as your own recording, or sell it by itself.",
                        "Keep the AI-generated labels in the file.")
                : List.of("Keep the AI-generated labels in the file.",
                        "Disclose AI use where a platform, distributor or law requires it.");
        return new CertificateView(c.id(), c.scope(), c.revokedAt() == null ? "valid" : "revoked", c.title(),
                c.titleLatin(), c.languageTag(), WHEN.format(c.songCreatedAt().atZoneSameInstant(IST)), c.songCreatedAt(),
                c.planAtCreation(), reference(c.licenseeId()), grant,
                "This song was generated with artificial intelligence.", human, c.masterSha256(), c.issuedAt(),
                c.revokedAt(), free ? Licences.ATTRIBUTION + " (certificate " + c.id() + ")" : null, conditions,
                "This certificate is not a certificate of copyright. Copyright in AI-generated music may not exist in "
                        + "some countries, and BeatMind cannot promise that no one will make a claim.");
    }

    /** A short, stable reference for the account that does not reveal who it is. */
    static String reference(UUID userId) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(("certificate-holder:" + userId).getBytes(StandardCharsets.UTF_8));
            return "BeatMind account " + HexFormat.of().formatHex(d, 0, 4).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
