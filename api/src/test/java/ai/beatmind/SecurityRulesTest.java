package ai.beatmind;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Which endpoints are public, which need sign-in and which need the admin role. */
@DatabaseTest
@AutoConfigureMockMvc
class SecurityRulesTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        SignInHelper.reset(jdbc);
    }

    @Test
    void publicEndpointsNeedNoSignIn() throws Exception {
        mvc.perform(get("/v1/pricing").param("country", "IN")).andExpect(status().isOk());
        mvc.perform(get("/v1/auth/methods")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    void generationNeedsSignIn() throws Exception {
        mvc.perform(get("/v1/generations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/generations").contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminNeedsTheAdminRole() throws Exception {
        mvc.perform(get("/v1/admin/jobs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/admin/jobs").cookie(SignInHelper.signIn(mvc, "user@example.com")))
                .andExpect(status().isForbidden());
        // US-031, REQ-SEC-06: an admin account without the second factor is not an admin yet.
        mvc.perform(get("/v1/admin/jobs").cookie(SignInHelper.signIn(mvc, "admin@example.com")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/admin/jobs").cookie(SignInHelper.signInAdmin(mvc, "admin@example.com")))
                .andExpect(status().isOk());
    }

    @Test
    void certificatesArePublic() throws Exception {
        mvc.perform(get("/v1/certificates/BM-0000-0000-0000")).andExpect(status().isNotFound());
        mvc.perform(get("/v1/moderation/cases")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/lyrics/" + java.util.UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownPathsNeedSignIn() throws Exception {
        mvc.perform(get("/v1/nothing-here")).andExpect(status().isUnauthorized());
    }
}
