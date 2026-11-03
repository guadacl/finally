package com.finallyapp.mail;

import java.util.Map;
import org.springframework.stereotype.Component;

/** {@link SmsGateway} via the notification MCP server's {@code send_sms} tool ({@link McpToolClient}). */
@Component
public class McpSmsGateway implements SmsGateway {

    static final String TOOL = "send_sms";

    private final McpToolClient mcp;

    public McpSmsGateway(McpToolClient mcp) {
        this.mcp = mcp;
    }

    @Override
    public String sendSms(String to, String text) {
        try {
            return McpToolClient.messageId(mcp.call(TOOL, Map.of("to", to, "text", text)));
        } catch (McpToolClient.ToolException e) {
            throw new SmsException(e.getMessage(), e);
        }
    }
}
