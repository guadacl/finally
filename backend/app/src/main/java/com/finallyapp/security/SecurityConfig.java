package com.finallyapp.security;

import com.finallyapp.config.FinallyProperties;
import com.finallyapp.config.WebConfig;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Keycloak login as a "backend for frontend": Spring runs the OIDC authorization-code flow and keeps the
 * login in a server-side session cookie. The Angular app never sees tokens, and same-origin requests
 * (including the {@code EventSource} SSE streams) are authenticated by that cookie.
 *
 * <ul>
 *   <li>Browser navigation without a session → redirect to Keycloak's login page (which offers "Register").</li>
 *   <li>{@code /api/**} without a session → {@code 401} JSON (the SPA then navigates to the login).</li>
 *   <li>{@code /api/health} is public (Docker healthcheck).</li>
 *   <li>No CSRF token checks: a logged-in user can use every feature; the SameSite=Lax session cookie
 *       blocks cross-site requests.</li>
 *   <li>Logout: {@code POST /logout} ends the Spring session and the Keycloak session (RP-initiated logout).</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    public static final String REGISTRATION_ID = "keycloak";

    private static final RequestMatcher API = req -> req.getRequestURI().startsWith(req.getContextPath() + "/api/");

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(FinallyProperties props) {
        return new InMemoryClientRegistrationRepository(keycloakRegistration(props.auth()));
    }

    /**
     * Built explicitly (no OIDC discovery at startup) so the app starts even while Keycloak is still booting,
     * and so the browser-facing URLs and the backend-facing URLs can differ inside Docker.
     */
    static ClientRegistration keycloakRegistration(FinallyProperties.Auth auth) {
        String pub = auth.publicRealmUrl();
        String internal = auth.internalRealmUrl();
        return ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientName("Keycloak")
                .clientId(auth.clientId())
                .clientSecret(auth.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .issuerUri(pub)
                .authorizationUri(pub + "/protocol/openid-connect/auth")
                .tokenUri(internal + "/protocol/openid-connect/token")
                .jwkSetUri(internal + "/protocol/openid-connect/certs")
                .userInfoUri(internal + "/protocol/openid-connect/userinfo")
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .providerConfigurationMetadata(Map.of("end_session_endpoint", pub + "/protocol/openid-connect/logout"))
                .build();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, FinallyProperties props,
                                            ClientRegistrationRepository registrations,
                                            UserProvisioning provisioning) throws Exception {
        boolean enabled = props.auth().enabled();
        CurrentUser.setAuthEnabled(enabled);
        if (!enabled) {
            log.warn("AUTHENTICATION IS DISABLED (finally.auth.enabled=false): everyone acts as user '{}'",
                    CurrentUser.LEGACY_USER);
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).csrf(c -> c.disable());
            return http.build();
        }

        SavedRequestAwareAuthenticationSuccessHandler afterLogin = new SavedRequestAwareAuthenticationSuccessHandler() {
            @Override
            public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                                Authentication authentication) throws jakarta.servlet.ServletException, IOException {
                try {
                    provisioning.ensure(authentication.getName());
                } catch (RuntimeException e) {
                    log.error("Could not provision user {}: {}", authentication.getName(), e.toString());
                }
                super.onAuthenticationSuccess(request, response, authentication);
            }
        };
        afterLogin.setDefaultTargetUrl("/");

        OidcClientInitiatedLogoutSuccessHandler afterLogout = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        afterLogout.setPostLogoutRedirectUri("{baseUrl}/");

        // After login, return only to an app page the user navigated to. Background requests of an already open tab
        // (API calls, the SSE streams, JS chunks) must never become the post-login target, or the browser would land
        // on raw JSON / an event stream instead of the app.
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(SecurityConfig::isAppPageNavigation);

        http
                .requestCache(c -> c.requestCache(requestCache))
                .authorizeHttpRequests(a -> a
                        // SSE and error dispatches re-enter the filter chain; the original request was already authorized.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/health", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(o -> o.successHandler(afterLogin))
                .logout(l -> l.logoutSuccessHandler(afterLogout))
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(apiUnauthorized(), API))
                .exceptionHandling(e -> e.accessDeniedHandler(loggingAccessDenied()))
                // Once logged in, a user can use every feature with no extra token checks (product decision).
                // Cross-site request forgery is still blocked by the session cookie being SameSite=Lax
                // (application.yml): browsers don't send it on POST/PATCH/DELETE coming from other sites.
                .csrf(c -> c.disable());
        return http.build();
    }

    /** Logs every 403, then answers like the default handler. */
    private static org.springframework.security.web.access.AccessDeniedHandler loggingAccessDenied() {
        org.springframework.security.web.access.AccessDeniedHandlerImpl delegate =
                new org.springframework.security.web.access.AccessDeniedHandlerImpl();
        return (request, response, ex) -> {
            log.warn("403 {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
            delegate.handle(request, response, ex);
        };
    }

    static boolean isAppPageNavigation(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return WebConfig.isSpaRoute(path);
    }

    private static AuthenticationEntryPoint apiUnauthorized() {
        return (request, response, ex) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"UNAUTHENTICATED\",\"message\":\"Login required\"}");
        };
    }

}
