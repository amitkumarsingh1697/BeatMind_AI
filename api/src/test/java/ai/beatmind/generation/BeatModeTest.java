package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/** US-015: beat mode with genre, BPM, key and instruments, and the tempo check (REQ-F-04). */
@DatabaseTest
@AutoConfigureMockMvc
class BeatModeTest extends SongApiSupport {

    Cookie dev;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        dev = adult("producer@example.com");
    }

    private Map<String, Object> beat(Object... overrides) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "beat");
        body.put("genre", "lofi");
        body.put("bpm", 90);
        body.put("key", "a minor");
        body.put("includeInstruments", List.of("piano", "tabla"));
        body.put("excludeInstruments", List.of("drums"));
        for (int i = 0; i < overrides.length; i += 2) {
            body.put((String) overrides[i], overrides[i + 1]);
        }
        return body;
    }

    @Test
    @Tag("TC-REQ-F-04-01")
    void beatRequestQueuesAnInstrumentalBeatJobWithItsSettings() throws Exception {
        UUID[] ids = made(dev, beat());
        var job = jdbc.sql("SELECT kind, payload::text AS p FROM generation_job WHERE id = :id").param("id", ids[0])
                .query().singleRow();
        assertThat(job.get("kind")).isEqualTo("beat");
        var payload = json.readTree(job.get("p").toString());
        assertThat(payload.get("mode").asString()).isEqualTo("beat");
        assertThat(payload.get("instrumental").asBoolean()).isTrue();
        assertThat(payload.get("beat").get("bpm").asInt()).isEqualTo(90);
        assertThat(payload.get("beat").get("key").asString()).isEqualTo("A minor");
        assertThat(payload.get("beat").get("exclude").get(0).asString()).isEqualTo("drums");

        // The worker's tempo check lands on the take and in the ledger.
        String result = """
                {"takes":[{"key":"k1.wav","format":"wav","runner":"stub","assets":{"mp3_192":{"key":"k1.mp3","contentType":"audio/mpeg"}},
                           "tempo":{"targetBpm":90,"measuredBpm":89.7,"withinTolerance":true,"octave":false}},
                          {"key":"k2.wav","format":"wav","runner":"stub","assets":{"mp3_192":{"key":"k2.mp3","contentType":"audio/mpeg"}},
                           "tempo":{"targetBpm":90,"measuredBpm":90.4,"withinTolerance":true,"octave":false}}]}
                """;
        jdbc.sql("UPDATE generation_job SET status = 'succeeded', finished_at = now(), result = CAST(:r AS jsonb) WHERE id = :id")
                .param("r", result).param("id", ids[0]).update();
        finalizer.runOnce();
        mvc.perform(get("/v1/songs/" + ids[1]).cookie(dev))
                .andExpect(jsonPath("$.mode").value("beat"))
                .andExpect(jsonPath("$.instrumental").value(true))
                .andExpect(jsonPath("$.title").value("Lofi beat, 90 BPM"))
                .andExpect(jsonPath("$.beat.genre").value("lofi"))
                .andExpect(jsonPath("$.beat.include[1]").value("tabla"))
                .andExpect(jsonPath("$.takes[0].tempoBpm").value(89.7));
        assertThat(jdbc.sql("""
                SELECT body FROM provenance_event WHERE song_id = :s AND type = 'take_generated' ORDER BY seq LIMIT 1
                """).param("s", ids[1]).query(String.class).single())
                .contains("\"tempoMeasuredBpm\":89.7").contains("\"tempoWithinTolerance\":true");
        assertThat(jdbc.sql("""
                SELECT body FROM provenance_event WHERE song_id = :s AND type = 'prompt_submitted'
                """).param("s", ids[1]).query(String.class).single()).contains("\"genre\":\"lofi\"");
    }

    @Test
    @Tag("TC-REQ-F-04-02")
    void beatSettingsAreValidated() throws Exception {
        request(dev, beat("bpm", 59)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_bpm"));
        request(dev, beat("bpm", 201)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_bpm"));
        request(dev, beat("genre", "polka")).andExpect(jsonPath("$.code").value("invalid_genre"));
        request(dev, beat("key", "H major")).andExpect(jsonPath("$.code").value("invalid_key"));
        request(dev, beat("includeInstruments", List.of("kazoo"))).andExpect(jsonPath("$.code").value("invalid_include"));
        request(dev, beat("excludeInstruments", List.of("piano"))).andExpect(jsonPath("$.code").value("invalid_exclude"));
        // Edges are allowed; mode "beat" works like kind "beat".
        made(dev, beat("bpm", 60, "key", "any"));
        jdbc.sql("UPDATE generation_job SET status = 'dead', finalized_at = now()").update();
        Map<String, Object> viaMode = beat("bpm", 200);
        viaMode.put("kind", "song");
        viaMode.put("mode", "beat");
        made(dev, viaMode);
    }

    @Test
    void keysAreNormalised() {
        assertThat(SongRequests.normaliseKey("Am")).isEqualTo("A minor");
        assertThat(SongRequests.normaliseKey("f# min")).isEqualTo("F# minor");
        assertThat(SongRequests.normaliseKey("Db")).isEqualTo("C# major");
        assertThat(SongRequests.normaliseKey("B♭ major")).isEqualTo("Bb major");
        assertThat(SongRequests.normaliseKey("")).isEqualTo("any");
        assertThat(SongRequests.normaliseKey("Cb")).isNull();
        assertThatThrownBy(() -> SongRequests.checkBeat("", null, "en", 60, null))
                .isInstanceOf(SongRequests.Refusal.class);
    }
}
