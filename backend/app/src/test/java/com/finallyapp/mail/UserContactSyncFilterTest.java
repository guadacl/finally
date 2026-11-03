package com.finallyapp.mail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.HashMap;
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

class UserContactSyncFilterTest {

    private UserContacts contacts;
    private ObjectProvider<UserContacts> provider;
    private UserContactSyncFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        contacts = mock(UserContacts.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(contacts);
        filter = new UserContactSyncFilter(provider);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String sub, String email, String phone) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", sub);
        if (email != null) {
            claims.put("email", email);
        }
        if (phone != null) {
            claims.put("phone_number", phone);
        }
        OidcIdToken token = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(60), claims);
        DefaultOidcUser user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), token, "sub");
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak"));
    }

    @Test
    void storesTheLoggedInUsersEmailAndPhone() throws Exception {
        loginAs("kc-sub-1", "demo@finally.local", "+32 470 12 34 56");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/portfolio");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        verify(contacts).rememberEmail("kc-sub-1", "demo@finally.local");
        verify(contacts).rememberPhone("kc-sub-1", "+32 470 12 34 56");
        verify(chain).doFilter(req, res);
    }

    @Test
    void missingPhoneClaimIsPassedOnAsNull() throws Exception {
        loginAs("kc-sub-2", "x@y.co", null);
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), mock(FilterChain.class));
        verify(contacts).rememberPhone(org.mockito.ArgumentMatchers.eq("kc-sub-2"), isNull());
    }

    @Test
    void ignoresAnonymousAndNonOidcUsers() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), mock(FilterChain.class));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob", "pw", List.of()));
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), mock(FilterChain.class));
        verify(contacts, never()).rememberEmail(any(), any());
        verify(contacts, never()).rememberPhone(any(), any());
    }

    @Test
    void worksWithoutRepositoryBean() throws Exception {
        when(provider.getIfAvailable()).thenReturn(null);
        loginAs("kc-sub-1", "demo@finally.local", "+32470123456");
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
    }
}
