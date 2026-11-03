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
 * Copies the logged-in Keycloak user's email address ({@code email} claim) and mobile number
 * ({@code phone_number} claim, Keycloak user-profile attribute {@code phone_number}) into {@code users_profile}
 * (via {@link UserContacts}).
 *
 * <p>Runs after the Spring Security filter chain on every request, not only at login: sessions are stored in
 * Postgres and survive app restarts, so users who logged in before this feature existed are covered on their
 * next request. Cheap: {@link UserContacts} only writes when a value changed. Claims are those of the current
 * login, so a number edited in Keycloak reaches the app after the next login.
 */
@Component
public class UserContactSyncFilter extends OncePerRequestFilter {

    static final String PHONE_CLAIM = "phone_number";

    private final ObjectProvider<UserContacts> contacts;

    // ObjectProvider: @WebMvcTest slices pick up servlet filters but not the repository bean.
    public UserContactSyncFilter(ObjectProvider<UserContacts> contacts) {
        this.contacts = contacts;
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
            UserContacts repo = contacts.getIfAvailable();
            if (repo != null) {
                repo.rememberEmail(auth.getName(), user.getEmail());
                Object phone = user.getClaims().get(PHONE_CLAIM);
                repo.rememberPhone(auth.getName(), phone == null ? null : phone.toString());
            }
        }
    }
}
