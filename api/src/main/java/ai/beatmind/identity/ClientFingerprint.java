package ai.beatmind.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * A keyed hash of the caller's IP address, for velocity rules (REQ-SEC-09, TM-12). The address
 * itself is never stored.
 */
@Component
public class ClientFingerprint {

    private final Secrets secrets;

    ClientFingerprint(Secrets secrets) {
        this.secrets = secrets;
    }

    public String ipHash(HttpServletRequest request) {
        return secrets.hmac("ip:" + AuthController.clientIp(request));
    }
}
