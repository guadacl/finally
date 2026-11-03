package com.finallyapp.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class McpToolClientTest {

    private static McpSchema.CallToolResult sent(String id) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("sent " + id)))
                .structuredContent(Map.of("messageId", id))
                .build();
    }

    @Test
    void splitsUrlIntoBaseAndEndpoint() {
        assertThat(McpToolClient.split("http://email-mcp:3001/mcp")).containsExactly("http://email-mcp:3001", "/mcp");
        assertThat(McpToolClient.split("http://localhost:3001")).containsExactly("http://localhost:3001", "/mcp");
        assertThat(McpToolClient.split("https://mail.example.com/x/mcp")).containsExactly("https://mail.example.com", "/x/mcp");
        assertThat(McpToolClient.split("")).containsExactly("", "/mcp");
    }

    @Test
    void readsMessageIdFromStructuredContentOrText() {
        assertThat(McpToolClient.messageId(sent("<a@b>"))).isEqualTo("<a@b>");
        McpSchema.CallToolResult textOnly = McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("sent <c@d>")))
                .build();
        assertThat(McpToolClient.messageId(textOnly)).isEqualTo("<c@d>");
    }

    @Test
    void notConfiguredFailsWithToolException() {
        assertThatThrownBy(() -> new McpToolClient("", Duration.ofSeconds(1)).call("send_email", Map.of()))
                .isInstanceOf(McpToolClient.ToolException.class)
                .hasMessageContaining("EMAIL_MCP_URL");
    }

    @Test
    void gatewaysCallTheirToolAndMapErrors() {
        McpToolClient mcp = mock(McpToolClient.class);
        when(mcp.call(eq("send_email"), anyMap())).thenReturn(sent("<e>"));
        when(mcp.call(eq("send_sms"), anyMap())).thenReturn(sent("sms-1"));

        assertThat(new McpEmailGateway(mcp).sendEmail("a@b.co", "s", "t")).isEqualTo("<e>");
        verify(mcp).call("send_email", Map.of("to", "a@b.co", "subject", "s", "text", "t"));
        assertThat(new McpSmsGateway(mcp).sendSms("+32470123456", "hi")).isEqualTo("sms-1");
        verify(mcp).call("send_sms", Map.of("to", "+32470123456", "text", "hi"));

        when(mcp.call(eq("send_sms"), anyMap())).thenThrow(new McpToolClient.ToolException("send_sms failed: bad"));
        assertThatThrownBy(() -> new McpSmsGateway(mcp).sendSms("+32470123456", "hi"))
                .isInstanceOf(SmsGateway.SmsException.class).hasMessageContaining("bad");
        when(mcp.call(eq("send_email"), anyMap())).thenThrow(new McpToolClient.ToolException("down"));
        assertThatThrownBy(() -> new McpEmailGateway(mcp).sendEmail("a@b.co", "s", "t"))
                .isInstanceOf(EmailGateway.EmailException.class);
    }

    @Test
    void unreachableServerFailsFastWithToolException() {
        McpToolClient client = new McpToolClient("http://127.0.0.1:1/mcp", Duration.ofSeconds(3));
        // first call pays the one-off SDK class loading / initialisation of a cold JVM
        assertThatThrownBy(() -> client.call("send_email", Map.of()))
                .isInstanceOf(McpToolClient.ToolException.class)
                .hasMessageContaining("unreachable");
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.call("send_sms", Map.of()))
                .isInstanceOf(McpToolClient.ToolException.class)
                .hasMessageContaining("unreachable");
        // bounded by the configured timeout (3 s, tried at most twice) plus slack, not by the SDK's ~15 s
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(12));
    }

    /**
     * Interop check against a running mcp/email-mcp-server (not part of the normal build):
     * {@code EMAIL_MCP_IT_URL=http://host.docker.internal:3001/mcp mvn -pl app test -Dtest=McpToolClientTest}.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "EMAIL_MCP_IT_URL", matches = "https?://.+")
    void sendsEmailAndSmsThroughARealMcpServer() {
        McpToolClient client = new McpToolClient(System.getenv("EMAIL_MCP_IT_URL"), Duration.ofSeconds(10));
        // what the app does at startup (ApplicationReadyEvent): pays the one-off SDK initialisation up front
        assertThat(client.warmUpNow()).isTrue();
        assertThat(new McpEmailGateway(client).sendEmail("demo@finally.local", "FinAlly alert: AAPL hit Support 185.00",
                "Interop test from McpToolClientTest")).isNotBlank();
        assertThat(new McpSmsGateway(client).sendSms("+32470123456",
                "FinAlly: AAPL hit Support 185.00 (now 184.97)")).isNotBlank();
    }
}
