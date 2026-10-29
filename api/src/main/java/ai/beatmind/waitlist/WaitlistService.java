package ai.beatmind.waitlist;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class WaitlistService {

    private final JdbcClient jdbc;
    private final Clock clock = Clock.systemUTC();

    WaitlistService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores a sign-up. A repeated email is ignored without telling the caller, so the endpoint
     * cannot be used to find out who is on the list.
     */
    void signUp(SignupRequest req) {
        jdbc.sql("""
                INSERT INTO waitlist_signup (id, email, country, locale, use_case, source_channel,
                                             price_variant, consent_text_version, consented_at)
                VALUES (:id, :email, :country, :locale, :useCase, :source, :variant, :consentVersion, :now)
                ON CONFLICT (lower(email)) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("email", req.email().strip())
                .param("country", req.country().toUpperCase(Locale.ROOT))
                .param("locale", req.locale())
                .param("useCase", blankToNull(req.useCase()))
                .param("source", blankToNull(req.sourceChannel()))
                .param("variant", req.priceVariant())
                .param("consentVersion", req.consentTextVersion())
                .param("now", OffsetDateTime.now(clock))
                .update();
    }

    void recordVisit(VisitRequest req) {
        jdbc.sql("""
                INSERT INTO waitlist_visit (day, country, price_variant, source_channel, visits)
                VALUES (:day, :country, :variant, :source, 1)
                ON CONFLICT (day, country, price_variant, source_channel)
                DO UPDATE SET visits = waitlist_visit.visits + 1
                """)
                .param("day", LocalDate.now(clock.withZone(ZoneOffset.UTC)))
                .param("country", req.country().toUpperCase(Locale.ROOT))
                .param("variant", req.priceVariant())
                .param("source", req.sourceChannel() == null ? "" : req.sourceChannel().strip())
                .update();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
