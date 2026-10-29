package ai.beatmind.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@DatabaseTest
@AutoConfigureMockMvc
class WaitlistIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    TurnstileVerifier turnstile;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE waitlist_signup, waitlist_visit").update();
        given(turnstile.verify(any(), any())).willReturn(true);
    }

    @Test
    void storesSignupWithVariantAndConsent() throws Exception {
        signUp("Asha@Example.com", true).andExpect(status().isAccepted());

        var row = jdbc.sql("SELECT country, locale, price_variant, consent_text_version FROM waitlist_signup")
                .query((rs, n) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4))
                .single();
        assertThat(row).isEqualTo("IN|hi|B|2026-10-v1");
    }

    @Test
    void repeatedEmailIsAcceptedButStoredOnce() throws Exception {
        signUp("asha@example.com", true).andExpect(status().isAccepted());
        signUp("ASHA@example.com", true).andExpect(status().isAccepted());

        assertThat(count("waitlist_signup")).isEqualTo(1);
    }

    @Test
    void refusesSignupWithoutConsent() throws Exception {
        signUp("asha@example.com", false).andExpect(status().isBadRequest());

        assertThat(count("waitlist_signup")).isZero();
    }

    @Test
    void refusesSignupWithoutAdultConfirmation() throws Exception {
        signUp("asha@example.com", true, false).andExpect(status().isBadRequest());

        assertThat(count("waitlist_signup")).isZero();
    }

    @Test
    void refusesSignupThatFailsBotCheck() throws Exception {
        given(turnstile.verify(any(), any())).willReturn(false);

        signUp("asha@example.com", true).andExpect(status().isForbidden());
        assertThat(count("waitlist_signup")).isZero();
    }

    @Test
    void countsVisitsPerVariantWithoutPersonalData() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/v1/waitlist/visits").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"country\":\"IN\",\"priceVariant\":\"A\",\"sourceChannel\":\"youtube\"}"))
                    .andExpect(status().isNoContent());
        }

        Long visits = jdbc.sql("SELECT visits FROM waitlist_visit WHERE price_variant = 'A'")
                .query(Long.class).single();
        assertThat(visits).isEqualTo(3);
    }

    private ResultActions signUp(String email, boolean consent) throws Exception {
        return signUp(email, consent, true);
    }

    private ResultActions signUp(String email, boolean consent, boolean adult) throws Exception {
        String body = """
                {"email":"%s","country":"in","locale":"hi","useCase":"YouTube vlogs",
                 "sourceChannel":"instagram","priceVariant":"B","consentToContact":%s,"confirmsAdult":%s,
                 "consentTextVersion":"2026-10-v1"}
                """.formatted(email, consent, adult);
        return mvc.perform(post("/v1/waitlist").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
