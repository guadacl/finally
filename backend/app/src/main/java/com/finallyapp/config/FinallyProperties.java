package com.finallyapp.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Application settings under the {@code finally.*} prefix (see application.yml). */
@ConfigurationProperties(prefix = "finally")
public record FinallyProperties(
        @DefaultValue Llm llm,
        @DefaultValue Market market,
        @DefaultValue Auth auth,
        @DefaultValue Mail mail,
        @DefaultValue Sms sms) {

    /**
     * SMS notifications (card created / deleted / target hit), sent by calling the {@code send_sms} tool of the
     * notification MCP server at {@code finally.mail.mcp-url} (the provider - {@code SMS_PROVIDER}, default
     * {@code log} - is configured on that server). Sent only when {@code enabled} and the MCP URL is set.
     */
    public record Sms(@DefaultValue("true") boolean enabled) {
    }

    /**
     * Target-price-hit alert emails, sent by calling the {@code send_email} tool of the email MCP server
     * ({@code mcp/email-mcp-server}, which owns the SMTP settings). Emails are only sent when {@code enabled}
     * and {@code mcpUrl} is non-blank. {@code appUrl} is the browser-facing base URL used for the link in the email.
     */
    public record Mail(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("") String mcpUrl,
            @DefaultValue("10s") Duration timeout,
            @DefaultValue("http://localhost:8000") String appUrl) {

        public boolean active() {
            return enabled && mcpUrl != null && !mcpUrl.isBlank();
        }

        /** Link to the Alerts page, e.g. http://localhost:8000/alerts. */
        public String alertsUrl() {
            String base = appUrl == null || appUrl.isBlank() ? "http://localhost:8000" : appUrl.trim();
            return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/alerts";
        }
    }

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
