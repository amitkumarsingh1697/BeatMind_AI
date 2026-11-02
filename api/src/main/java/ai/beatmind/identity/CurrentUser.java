package ai.beatmind.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The signed-in user for the current request. {@code profileComplete} is true once the user has
 * declared an adult date of birth and accepted the terms; generation needs it. {@code plan} is
 * free, creator or pro (entitlement matrix).
 */
public record CurrentUser(UUID id, String email, boolean profileComplete, boolean admin, String plan) {

    /** The signed-in user, if any. */
    public static Optional<CurrentUser> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }
}
