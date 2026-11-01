package ai.beatmind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Core API (ARCH-02). Each top-level package is a module whose boundaries are verified in tests. */
@SpringBootApplication
@EnableScheduling
public class BeatMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(BeatMindApplication.class, args);
    }
}
