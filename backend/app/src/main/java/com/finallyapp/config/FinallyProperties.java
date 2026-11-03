package com.finallyapp.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Application settings under the {@code finally.*} prefix (see application.yml). */
@ConfigurationProperties(prefix = "finally")
public record FinallyProperties(
        @DefaultValue Llm llm,
        @DefaultValue Market market,
        @DefaultValue Auth auth) {

    /**
     * Keycloak login. {@code keycloakUrl} is the URL the <em>browser</em> uses (login page, issuer);
     * {@code keycloakInternalUrl} is the URL the <em>backend</em> uses for token/JWKS/userinfo calls
     * (in Docker: http://keycloak:8080). {@code enabled=false} turns authentication off and every request
     * acts as the legacy single user "default" — only for tests and local runs without Keycloak.
     */
    public record Auth(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("http://localhost:8180") String keycloakUrl,
            @DefaultValue("") String keycloakInternalUrl,
            @DefaultValue("finally") String realm,
            @DefaultValue("finally-app") String clientId,
            @DefaultValue("") String clientSecret) {

        /** Realm URL as seen by the browser, e.g. http://localhost:8180/realms/finally (also the token issuer). */
        public String publicRealmUrl() {
            return trimSlash(keycloakUrl) + "/realms/" + realm;
        }

        /** Realm URL used for server-to-server calls (falls back to the public URL). */
        public String internalRealmUrl() {
            String base = keycloakInternalUrl == null || keycloakInternalUrl.isBlank() ? keycloakUrl : keycloakInternalUrl;
            return trimSlash(base) + "/realms/" + realm;
        }

        private static String trimSlash(String s) {
            return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
        }
    }

    public record Llm(
            @DefaultValue("false") boolean mock,
            @DefaultValue("") String apiKey,
            @DefaultValue("https://openrouter.ai/api/v1") String baseUrl,
            @DefaultValue("openai/gpt-oss-120b") String model,
            @DefaultValue("cerebras") String provider,
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("20") int historyLimit) {
    }

    public record Market(
            @DefaultValue("") String massiveApiKey,
            @DefaultValue("https://api.massive.com") String massiveBaseUrl,
            @DefaultValue("15s") Duration massivePollInterval,
            @DefaultValue("500ms") Duration simulatorInterval,
            @DefaultValue("0.001") double eventProbability,
            @DefaultValue("") String finnhubApiKey,
            @DefaultValue("https://finnhub.io/api/v1") String finnhubBaseUrl,
            @DefaultValue("1100ms") Duration finnhubCallSpacing) {

        public boolean useFinnhub() {
            return finnhubApiKey != null && !finnhubApiKey.isBlank();
        }

        public boolean useMassive() {
            return massiveApiKey != null && !massiveApiKey.isBlank();
        }
    }
}
