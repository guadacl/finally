package com.finallyapp.mail;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Copies the logged-in Keycloak user's email address into {@code users_profile.email} (via {@link UserEmails}).
 *
 * <p>Runs after the Spring Security filter chain on every request, not only at login: sessions are stored in
 * Postgres and survive app restarts, so users who logged in before this feature existed are covered on their
 * next request. Cheap: {@link UserEmails#remember} only writes when the address changed.
 */
@Component
public class UserEmailSyncFilter extends OncePerRequestFilter {

    private final ObjectProvider<UserEmails> emails;

    // ObjectProvider: @WebMvcTest slices pick up servlet filters but not the repository bean.
    public UserEmailSyncFilter(ObjectProvider<UserEmails> emails) {
        this.emails = emails;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        sync();
        chain.doFilter(request, response);
    }

    void sync() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof OidcUser user) {
            UserEmails repo = emails.getIfAvailable();
            if (repo != null) {
                repo.remember(auth.getName(), user.getEmail());
            }
        }
    }
}
