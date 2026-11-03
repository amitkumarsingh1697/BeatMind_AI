package ai.beatmind.identity;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Who may call what (REQ-SEC-01).
 *
 * <p>CSRF tokens are not used: the session cookie is {@code SameSite=Lax}, so browsers do not send
 * it on cross-site POSTs, and every state-changing endpoint takes a JSON body, which a cross-site
 * form cannot send without a CORS preflight (and the API allows no cross-origin calls).
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, SessionService sessions, UserStore users, AuthProperties props)
            throws Exception {
        http.csrf(c -> c.disable())
                .cors(c -> c.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new SessionCookieFilter(sessions, users, props), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/v1/pricing", "/v1/waitlist", "/v1/waitlist/visits").permitAll()
                        .requestMatchers("/v1/auth/**").permitAll()
                        // Signed links (US-026): the signature is the permission, valid 15 minutes.
                        .requestMatchers(HttpMethod.GET, "/v1/downloads/*").permitAll()
                        // Certificates are public by id (API-012); they show no personal data.
                        .requestMatchers(HttpMethod.GET, "/v1/certificates/*").permitAll()
                        // Reports (API-013): rights holders and authorities need no account (US-032).
                        .requestMatchers(HttpMethod.POST, "/v1/reports").permitAll()
                        // Share links (US-024): the token is the permission; revocable.
                        .requestMatchers(HttpMethod.GET, "/v1/shared/*", "/v1/shared/*/audio").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated());
        return http.build();
    }

    /** No username and password accounts; stops Spring Boot from creating a default user. */
    @Bean
    UserDetailsService noPasswordUsers() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }
}
