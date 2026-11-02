package ai.beatmind.generation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(Entitlements.class)
class GenerationConfig {}
