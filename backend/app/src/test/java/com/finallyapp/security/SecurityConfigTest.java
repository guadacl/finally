package com.finallyapp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finallyapp.api.model.WatchlistItem;
import com.finallyapp.config.FinallyProperties;
import com.finallyapp.market.MarketDataSource;
import com.finallyapp.system.HealthController;
import com.finallyapp.watchlist.WatchlistController;
import com.finallyapp.watchlist.WatchlistService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import com.finallyapp.persistence.UserProfileRepository;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The real security filter chain: Keycloak redirect, 401 for the API, CSRF, public health, /api/me. */
@WebMvcTest(controllers = {MeController.class, HealthController.class, WatchlistController.class})
@Import(SecurityConfig.class)
@EnableConfigurationProperties(FinallyProperties.class)
class SecurityConfigTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UserProvisioning provisioning;
    @MockitoBean
    WatchlistService watchlist;
    @MockitoBean
    UserProfileRepository profiles;
    @MockitoBean
    MarketDataSource source;
    @MockitoBean
    com.finallyapp.market.TickerSearchService tickerSearch;

    @Test
    void apiWithoutLoginIs401Json() throws Exception {
        mvc.perform(get("/api/watchlist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void pageWithoutLoginRedirectsToKeycloakLogin() throws Exception {
        // Browsers navigate with Accept: text/html; only those get the login redirect.
        MvcResult page = mvc.perform(get("/markets").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(page.getResponse().getRedirectedUrl()).endsWith("/oauth2/authorization/keycloak");

        MvcResult authorize = mvc.perform(get("/oauth2/authorization/keycloak"))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(authorize.getResponse().getRedirectedUrl())
                .startsWith("http://localhost:8180/realms/finally/protocol/openid-connect/auth?")
                .contains("client_id=finally-app")
                .contains("scope=openid%20profile%20email");
    }

    @Test
    void onlyAppPagesBecomeThePostLoginTarget() {
        assertThat(SecurityConfig.isAppPageNavigation(new MockHttpServletRequest("GET", "/markets"))).isTrue();
        assertThat(SecurityConfig.isAppPageNavigation(new MockHttpServletRequest("GET", "/"))).isTrue();
        assertThat(SecurityConfig.isAppPageNavigation(new MockHttpServletRequest("GET", "/api/portfolio"))).isFalse();
        assertThat(SecurityConfig.isAppPageNavigation(new MockHttpServletRequest("GET", "/api/stream/prices"))).isFalse();
        assertThat(SecurityConfig.isAppPageNavigation(new MockHttpServletRequest("GET", "/chunk-ABC.js"))).isFalse();
        assertThat(SecurityConfig.isAppPageNavigation(new MockHttpServletRequest("POST", "/markets"))).isFalse();
    }

    @Test
    void backgroundApiCallsDoNotOverrideTheReturnPage() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/portfolio").accept(MediaType.TEXT_HTML).session(session)).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/watchlist").session(session)).andExpect(status().isUnauthorized());
        DefaultSavedRequest saved = (DefaultSavedRequest) session.getAttribute("SPRING_SECURITY_SAVED_REQUEST");
        assertThat(saved.getRequestURI()).isEqualTo("/portfolio");
    }

    @Test
    void logoutEndsTheKeycloakSessionEvenWithoutOrWithAForeignCsrfCookie() throws Exception {
        var keycloak = SecurityConfig.keycloakRegistration(new FinallyProperties.Auth(
                true, "http://localhost:8180", "", "finally", "finally-app", "secret"));
        for (var request : List.of(
                post("/logout").with(oidcLogin().clientRegistration(keycloak)),
                post("/logout").with(oidcLogin().clientRegistration(keycloak)).cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "from-another-localhost-app"))
                        .param("_csrf", "stale"))) {
            MvcResult r = mvc.perform(request).andExpect(status().is3xxRedirection()).andReturn();
            assertThat(r.getResponse().getRedirectedUrl())
                    .startsWith("http://localhost:8180/realms/finally/protocol/openid-connect/logout?")
                    .contains("id_token_hint=")
                    .contains("post_logout_redirect_uri=http://localhost/");
        }
    }

    @Test
    void healthIsPublic() throws Exception {
        when(source.name()).thenReturn("simulator");
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void meReturnsTheKeycloakUser() throws Exception {
        mvc.perform(get("/api/me").with(oidcLogin().idToken(t -> t
                        .subject("kc-sub-1")
                        .claim("preferred_username", "alice")
                        .claim("email", "alice@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("kc-sub-1"))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.authEnabled").value(true));
    }

    @Test
    void loggedInUsersCanWriteWithoutAnyExtraToken() throws Exception {
        when(watchlist.add(anyString())).thenReturn(new WatchlistItem("PYPL", OffsetDateTime.now()));
        String body = "{\"ticker\":\"PYPL\"}";

        // no CSRF header, and even a stale token cookie from an old tab, must not block a logged-in user
        mvc.perform(post("/api/watchlist").with(oidcLogin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/watchlist").with(oidcLogin())
                        .cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "stale")).header("X-XSRF-TOKEN", "other")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        // still: no login, no access
        mvc.perform(post("/api/watchlist").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }
}
