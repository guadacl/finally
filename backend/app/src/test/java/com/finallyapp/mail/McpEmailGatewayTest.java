package com.finallyapp.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class McpEmailGatewayTest {

    @Test
    void splitsUrlIntoBaseAndEndpoint() {
        assertThat(McpEmailGateway.split("http://email-mcp:3001/mcp")).containsExactly("http://email-mcp:3001", "/mcp");
        assertThat(McpEmailGateway.split("http://localhost:3001")).containsExactly("http://localhost:3001", "/mcp");
        assertThat(McpEmailGateway.split("https://mail.example.com/x/mcp")).containsExactly("https://mail.example.com", "/x/mcp");
        assertThat(McpEmailGateway.split("")).containsExactly("", "/mcp");
    }

    @Test
    void readsMessageIdFromStructuredContentOrText() {
        McpSchema.CallToolResult structured = McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("sent <a@b>")))
                .structuredContent(Map.of("messageId", "<a@b>"))
                .build();
        assertThat(McpEmailGateway.messageId(structured)).isEqualTo("<a@b>");

        McpSchema.CallToolResult textOnly = McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("sent <c@d>")))
                .build();
        assertThat(McpEmailGateway.messageId(textOnly)).isEqualTo("<c@d>");
    }

    @Test
    void notConfiguredFailsWithEmailException() {
        assertThatThrownBy(() -> new McpEmailGateway("", Duration.ofSeconds(1)).sendEmail("a@b.co", "s", "t"))
                .isInstanceOf(EmailGateway.EmailException.class)
                .hasMessageContaining("EMAIL_MCP_URL");
    }

    @Test
    void unreachableServerFailsFastWithEmailException() {
        McpEmailGateway gateway = new McpEmailGateway("http://127.0.0.1:1/mcp", Duration.ofSeconds(3));
        // first call pays the one-off SDK class loading / initialisation of a cold JVM
        assertThatThrownBy(() -> gateway.sendEmail("a@b.co", "s", "t"))
                .isInstanceOf(EmailGateway.EmailException.class)
                .hasMessageContaining("unreachable");
        long start = System.nanoTime();
        assertThatThrownBy(() -> gateway.sendEmail("a@b.co", "s", "t"))
                .isInstanceOf(EmailGateway.EmailException.class)
                .hasMessageContaining("unreachable");
        // bounded by the configured timeout (3 s, tried at most twice) plus slack, not by the SDK's ~15 s
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(12));
    }

    /**
     * Interop check against a running mcp/email-mcp-server (not part of the normal build):
     * {@code EMAIL_MCP_IT_URL=http://host.docker.internal:3001/mcp mvn -pl app test -Dtest=McpEmailGatewayTest}.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "EMAIL_MCP_IT_URL", matches = "https?://.+")
    void sendsThroughARealEmailMcpServer() {
        McpEmailGateway gateway = new McpEmailGateway(System.getenv("EMAIL_MCP_IT_URL"), Duration.ofSeconds(10));
        // what the app does at startup (ApplicationReadyEvent): pays the one-off SDK initialisation up front
        assertThat(gateway.warmUpNow()).isTrue();
        String id = gateway.sendEmail("demo@finally.local", "FinAlly alert: AAPL hit Support 185.00",
                "Interop test from McpEmailGatewayTest");
        assertThat(id).isNotBlank();
    }
}
