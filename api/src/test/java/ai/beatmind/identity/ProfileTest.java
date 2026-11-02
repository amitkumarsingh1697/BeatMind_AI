package ai.beatmind.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import ai.beatmind.SignInHelper;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The 18+ profile step at sign-up (REQ-F-53). */
@DatabaseTest
@AutoConfigureMockMvc
@Tag("TC-REQ-F-53-01")
class ProfileTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        SignInHelper.reset(jdbc);
    }

    @Test
    void adultCompletesProfile() throws Exception {
        Cookie cookie = SignInHelper.signIn(mvc, "asha@example.com");

        profile(cookie, LocalDate.now().minusYears(25), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsProfile").value(false));
        mvc.perform(get("/v1/me").cookie(cookie)).andExpect(jsonPath("$.needsProfile").value(false));
    }

    @Test
    void keepsOnlyTheBirthMonth() throws Exception {
        Cookie cookie = SignInHelper.signIn(mvc, "asha@example.com");

        profile(cookie, LocalDate.of(1995, 4, 12), true).andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT birth_month FROM app_user").query(LocalDate.class).single())
                .isEqualTo(LocalDate.of(1995, 4, 1));
    }

    @Test
    void underEighteenIsBlockedAndTheAccountDeleted() throws Exception {
        Cookie cookie = SignInHelper.signIn(mvc, "teen@example.com");

        profile(cookie, LocalDate.now().minusYears(17), true).andExpect(status().isForbidden());

        assertThat(jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM user_session").query(Long.class).single()).isZero();
        mvc.perform(get("/v1/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void termsMustBeAccepted() throws Exception {
        Cookie cookie = SignInHelper.signIn(mvc, "asha@example.com");

        profile(cookie, LocalDate.now().minusYears(25), false).andExpect(status().isBadRequest());
    }

    @Test
    void eighteenthBirthdayCounts() {
        LocalDate today = LocalDate.of(2026, 11, 1);

        assertThat(MeController.isAdult(LocalDate.of(2008, 11, 1), today)).isTrue();
        assertThat(MeController.isAdult(LocalDate.of(2008, 11, 2), today)).isFalse();
        assertThat(MeController.isAdult(LocalDate.of(2008, 2, 29), LocalDate.of(2026, 2, 28))).isFalse();
    }

    private ResultActions profile(Cookie cookie, LocalDate birth, boolean terms) throws Exception {
        return mvc.perform(post("/v1/me/profile").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content("{\"birthDate\":\"%s\",\"acceptTerms\":%s,\"termsVersion\":\"2026-11-beta\"}"
                        .formatted(birth, terms)));
    }
}
