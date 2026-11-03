package com.finallyapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards against a build that packages the app without {@code application.yml} (it happened with an incremental
 * Maven build): the app would then start with defaults only, e.g. an empty Keycloak client secret, and every
 * login would fail with {@code invalid_client_credentials}.
 */
class ApplicationConfigPresentTest {

    @Test
    void applicationYmlIsOnTheClasspathWithAuthSettings() throws IOException {
        ClassPathResource yml = new ClassPathResource("application.yml");
        assertThat(yml.exists()).as("application.yml must be packaged").isTrue();
        String content = yml.getContentAsString(StandardCharsets.UTF_8);
        assertThat(content).contains("client-secret: ${KEYCLOAK_CLIENT_SECRET").contains("name: finally")
                // app-specific session cookie: localhost cookies are shared across ports
                .contains("name: FINALLY_SESSION");
    }
}
