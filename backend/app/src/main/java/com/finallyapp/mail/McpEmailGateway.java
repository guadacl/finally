package com.finallyapp.mail;

import java.util.Map;
import org.springframework.stereotype.Component;

/** {@link EmailGateway} via the notification MCP server's {@code send_email} tool ({@link McpToolClient}). */
@Component
public class McpEmailGateway implements EmailGateway {

    static final String TOOL = "send_email";

    private final McpToolClient mcp;

    public McpEmailGateway(McpToolClient mcp) {
        this.mcp = mcp;
    }

    @Override
    public String sendEmail(String to, String subject, String text) {
        try {
            return McpToolClient.messageId(mcp.call(TOOL, Map.of("to", to, "subject", subject, "text", text)));
        } catch (McpToolClient.ToolException e) {
            throw new EmailException(e.getMessage(), e);
        }
    }
}
