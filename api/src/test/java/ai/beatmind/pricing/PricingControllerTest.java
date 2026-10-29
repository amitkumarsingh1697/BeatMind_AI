package ai.beatmind.pricing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PricingController.class)
@Import(PricingService.class)
class PricingControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void queryParameterWins() throws Exception {
        mvc.perform(get("/v1/pricing").param("country", "BR").header("CF-IPCountry", "IN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.country").value("BR"))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void fallsBackToEdgeCountryHeader() throws Exception {
        mvc.perform(get("/v1/pricing").header("CF-IPCountry", "IN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.plans[0].plan").value("creator"))
                .andExpect(jsonPath("$.plans[0].monthly").value(149));
    }
}
