package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.beatmind.DatabaseTest;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/** US-014: queue position and a wait estimate within ±30% (UX-A11Y-15). */
@DatabaseTest
@AutoConfigureMockMvc
class QueueEstimateTest extends SongApiSupport {

    @BeforeEach
    void setUp() {
        resetAll();
    }

    @Test
    void positionCountsTheJobsAheadAndRunningJobsReportZero() throws Exception {
        // 40-second jobs recently, one worker.
        jdbc.sql("""
                INSERT INTO generation_job (id, kind, status, payload, started_at, finished_at, finalized_at)
                SELECT gen_random_uuid(), 'song', 'succeeded', '{}'::jsonb, now() - interval '50 seconds',
                       now() - interval '10 seconds', now() FROM generate_series(1, 5)
                """).update();
        List<Cookie> people = new ArrayList<>();
        List<UUID> jobs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Cookie c = adult("q" + i + "@example.com");
            people.add(c);
            jobs.add(made(c, "song " + i)[0]);
        }
        jdbc.sql("UPDATE generation_job SET status = 'running', locked_by = 'w1', locked_at = now(), started_at = now() - interval '10 seconds' WHERE id = :id")
                .param("id", jobs.get(0)).update();
        mvc.perform(get("/v1/generations/" + jobs.get(0)).cookie(people.get(0)))
                .andExpect(jsonPath("$.queuePosition").value(0))
                .andExpect(jsonPath("$.estimatedSeconds").value(30));
        mvc.perform(get("/v1/generations/" + jobs.get(1)).cookie(people.get(1)))
                .andExpect(jsonPath("$.queuePosition").value(1))
                .andExpect(jsonPath("$.estimatedSeconds").value(60));
        mvc.perform(get("/v1/generations/" + jobs.get(2)).cookie(people.get(2)))
                .andExpect(jsonPath("$.queuePosition").value(2))
                .andExpect(jsonPath("$.estimatedSeconds").value(100));
        // A finished job has neither.
        workerFinishes(jobs.get(0), false);
        mvc.perform(get("/v1/generations/" + jobs.get(0)).cookie(people.get(0)))
                .andExpect(jsonPath("$.queuePosition").doesNotExist());
        mvc.perform(get("/v1/generations/" + jobs.get(1)).cookie(people.get(1)))
                .andExpect(jsonPath("$.queuePosition").value(1));
    }

    /**
     * The estimate against a simulated queue: run times vary ±25% around the mean, one to four
     * workers, up to 12 jobs ahead. The acceptance bar is that most estimates land within ±30%.
     */
    @Test
    @Tag("TC-UX-A11Y-15-01")
    void estimateIsWithinThirtyPercentOnASimulatedQueue() {
        Random rng = new Random(2026);
        int within = 0;
        int trials = 2000;
        for (int trial = 0; trial < trials; trial++) {
            int workers = 1 + rng.nextInt(4);
            int position = 1 + rng.nextInt(12);
            double mean = 20 + rng.nextInt(40);
            // Simulate: every worker is part-way through a job; jobs ahead are handed out in order.
            double[] free = new double[workers];
            for (int w = 0; w < workers; w++) {
                free[w] = rng.nextDouble() * mean * (0.75 + rng.nextDouble() * 0.5);
            }
            double done = 0;
            for (int j = 1; j <= position; j++) {
                int w = 0;
                for (int k = 1; k < workers; k++) {
                    if (free[k] < free[w]) {
                        w = k;
                    }
                }
                double run = mean * (0.75 + rng.nextDouble() * 0.5);
                free[w] += run;
                if (j == position) {
                    done = free[w];
                }
            }
            int estimate = GenerationStore.estimate(new GenerationStore.QueueStats(mean, workers), position, 0);
            if (Math.abs(estimate - done) <= 0.30 * done) {
                within++;
            }
        }
        assertThat(within / (double) trials).as("share of estimates within ±30%%").isGreaterThan(0.8);
    }
}
