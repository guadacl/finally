package com.finallyapp.mail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class UserEmailSyncFilterTest {

    private UserEmails emails;
    private ObjectProvider<UserEmails> provider;
    private UserEmailSyncFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        emails = mock(UserEmails.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(emails);
        filter = new UserEmailSyncFilter(provider);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String sub, String email) {
        OidcIdToken token = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(60),
                email == null ? Map.of("sub", sub) : Map.of("sub", sub, "email", email));
        DefaultOidcUser user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), token, "sub");
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak"));
    }

    @Test
    void storesTheLoggedInUsersEmail() throws Exception {
        loginAs("kc-sub-1", "demo@finally.local");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/portfolio");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        verify(emails).remember("kc-sub-1", "demo@finally.local");
        verify(chain).doFilter(req, res);
    }

    @Test
    void ignoresAnonymousAndNonOidcUsers() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), mock(FilterChain.class));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob", "pw", List.of()));
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), mock(FilterChain.class));
        verify(emails, never()).remember(any(), any());
    }

    @Test
    void worksWithoutRepositoryBean() throws Exception {
        when(provider.getIfAvailable()).thenReturn(null);
        loginAs("kc-sub-1", "demo@finally.local");
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
    }
}
