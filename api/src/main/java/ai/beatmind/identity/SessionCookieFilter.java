package ai.beatmind.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Turns a valid session cookie into the request's {@link CurrentUser}. */
class SessionCookieFilter extends OncePerRequestFilter {

    private final SessionService sessions;
    private final UserStore users;
    private final AuthProperties props;

    SessionCookieFilter(SessionService sessions, UserStore users, AuthProperties props) {
        this.sessions = sessions;
        this.users = users;
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SessionService.tokenFrom(request)
                .flatMap(sessions::resolve)
                .flatMap(users::find)
                .ifPresent(user -> {
                    boolean admin = props.isAdmin(user.email());
                    var principal = new CurrentUser(user.id(), user.email(), user.profileComplete(), admin);
                    var authorities = admin
                            ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                            : List.of(new SimpleGrantedAuthority("ROLE_USER"));
                    var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(auth);
                    SecurityContextHolder.setContext(context);
                });
        chain.doFilter(request, response);
    }
}
