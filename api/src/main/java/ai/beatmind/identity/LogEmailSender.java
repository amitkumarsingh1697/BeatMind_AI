package ai.beatmind.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stand-in until an email provider is set up (TODO.md, Sprint 1). It sends nothing. With
 * {@code beatmind.auth.dev-codes} on, the code is returned to the local web app instead, so it is
 * never written to logs.
 */
@Component
class LogEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);

    @Override
    public void sendSignInCode(String email, String code, String locale) {
        log.info("No email provider configured; sign-in code not sent (locale {})", locale);
    }
}
