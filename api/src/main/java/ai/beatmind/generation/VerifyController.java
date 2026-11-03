package ai.beatmind.generation;

import ai.beatmind.botcheck.TurnstileVerifier;
import ai.beatmind.identity.ClientFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** API-011: public verification by certificate ID or by file (US-021). */
@RestController
class VerifyController {

    record ByCertificate(@Size(max = 40) String certificateId, @Size(max = 4096) String turnstileToken) {}

    private final Verifier verifier;
    private final TurnstileVerifier turnstile;
    private final ClientFingerprint fingerprint;
    private final long maxBytes;

    VerifyController(Verifier verifier, TurnstileVerifier turnstile, ClientFingerprint fingerprint,
                     @Value("${beatmind.verify.max-file-mb:30}") int maxFileMb) {
        this.verifier = verifier;
        this.turnstile = turnstile;
        this.fingerprint = fingerprint;
        this.maxBytes = maxFileMb * 1024L * 1024L;
    }

    @PostMapping(path = "/v1/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> certificate(@RequestBody ByCertificate req, HttpServletRequest http) {
        if (!turnstile.verify(req.turnstileToken(), http.getRemoteAddr())) {
            return botCheck();
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(verifier.certificate(req.certificateId(), fingerprint.ipHash(http)));
        } catch (SongRequests.Refusal r) {
            return GenerationController.problem(r.status, r.code, r.getMessage());
        }
    }

    @PostMapping(path = "/v1/verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> file(@RequestPart("file") MultipartFile file,
                           @RequestParam(name = "turnstileToken", required = false) String token,
                           HttpServletRequest http) throws IOException {
        if (!turnstile.verify(token, http.getRemoteAddr())) {
            return botCheck();
        }
        if (file.isEmpty() || file.getSize() > maxBytes) {
            return GenerationController.problem(HttpStatus.BAD_REQUEST, "invalid_file",
                    "Choose an audio file up to " + maxBytes / (1024 * 1024) + " MB.");
        }
        try {
            Verifier.Check check = verifier.file(file.getBytes(), fingerprint.ipHash(http));
            return ResponseEntity.status("checking".equals(check.status()) ? HttpStatus.ACCEPTED : HttpStatus.OK)
                    .cacheControl(CacheControl.noStore()).body(check);
        } catch (SongRequests.Refusal r) {
            return GenerationController.problem(r.status, r.code, r.getMessage());
        }
    }

    /** Polled by the page while a worker reads the file. The id is random and unguessable. */
    @GetMapping("/v1/verify/{id}")
    ResponseEntity<Verifier.Check> check(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(verifier.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    private static ResponseEntity<?> botCheck() {
        return GenerationController.problem(HttpStatus.FORBIDDEN, "bot_check",
                "We could not confirm you are a person. Reload the page and try again.");
    }
}
