package com.finallyapp.mail;

import com.finallyapp.config.FinallyProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * MCP client for the notification MCP server ({@code mcp/email-mcp-server}, compose service {@code email-mcp}):
 * calls one of its tools ({@code send_email}, {@code send_sms}) over Streamable HTTP ({@code finally.mail.mcp-url},
 * e.g. {@code http://email-mcp:3001/mcp}).
 *
 * <p>One short-lived client per call (initialize, call, close): notifications are rare, the server is stateless,
 * and no connection state can go stale while the MCP container restarts.
 */
@Component
public class McpToolClient {

    private static final Logger log = LoggerFactory.getLogger(McpToolClient.class);

    private final String baseUrl;
    private final String endpoint;
    private final Duration timeout;

    @Autowired // two constructors: tell Spring which one to use
    public McpToolClient(FinallyProperties props) {
        this(props.mail().mcpUrl(), props.mail().timeout());
    }

    McpToolClient(String mcpUrl, Duration timeout) {
        String[] parts = split(mcpUrl);
        this.baseUrl = parts[0];
        this.endpoint = parts[1];
        this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    }

    /** A failed tool call: server not configured / unreachable, or the tool reported an error. */
    public static class ToolException extends RuntimeException {
        public ToolException(String message) {
            super(message);
        }

        public ToolException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** {@code http://email-mcp:3001/mcp} -> {@code ["http://email-mcp:3001", "/mcp"]}. */
    static String[] split(String url) {
        if (url == null || url.isBlank()) {
            return new String[] {"", "/mcp"};
        }
        URI uri = URI.create(url.trim());
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/mcp" : uri.getRawPath();
        return new String[] {uri.getScheme() + "://" + uri.getRawAuthority(), path};
    }

    public boolean configured() {
        return !baseUrl.isEmpty();
    }

    String url() {
        return baseUrl + endpoint;
    }

    private static final ExecutorService CALLS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "finally-mcp");
        t.setDaemon(true);
        return t;
    });

    /**
     * Prepares the MCP client stack in the background once the app is up: the first client in a fresh JVM pays
     * several seconds of one-off class loading / initialisation (measured ~25 s on a slow container), which would
     * otherwise eat the first notification's deadline. Does an MCP handshake only; failures are ignored.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!configured()) {
            return;
        }
        CALLS.execute(this::warmUpNow);
    }

    /** Blocking warm-up: one handshake (and a tools/list, which exercises the response path); never throws. */
    boolean warmUpNow() {
        long start = System.nanoTime();
        McpSyncClient client = null;
        try {
            client = newClient();
            client.initialize();
            client.listTools();
            log.info("Notification MCP server {} reachable (warm-up {} ms)", url(),
                    (System.nanoTime() - start) / 1_000_000);
            return true;
        } catch (RuntimeException e) {
            log.info("Notification MCP server {} not reachable yet ({}); notifications will retry it when needed",
                    url(), e.getMessage());
            return false;
        } finally {
            if (client != null) {
                closeLater(client, false);
            }
        }
    }

    /**
     * Initialize + tool call under a hard deadline of {@link #timeout}: the SDK's own timeouts do not bound every
     * failure mode (an unreachable server made {@code initialize()} block ~15 s with 3 s timeouts). When the
     * deadline passes before the tool was even called (nothing can have been sent), the exchange is retried once.
     * The client is closed in the background afterwards, so a slow shutdown never delays or fails a sent message.
     * Threads are not interrupted (the SDK blocks inside Reactor); a timed-out client is closed from outside.
     *
     * @return the successful result (never one with {@code isError})
     * @throws ToolException on any failure
     */
    public McpSchema.CallToolResult call(String tool, Map<String, Object> arguments) {
        if (!configured()) {
            throw new ToolException("no notification MCP server configured (EMAIL_MCP_URL)");
        }
        try {
            return attempt(tool, arguments);
        } catch (NotCalledYet e) {
            log.info("Notification MCP server {} slow to answer; retrying {} once", url(), tool);
            try {
                return attempt(tool, arguments);
            } catch (NotCalledYet again) {
                throw new ToolException(again.getMessage());
            }
        }
    }

    /** Deadline hit before the tool was called: safe to retry. */
    static final class NotCalledYet extends ToolException {
        NotCalledYet(String message) {
            super(message);
        }
    }

    private McpSchema.CallToolResult attempt(String tool, Map<String, Object> arguments) {
        McpSyncClient client = newClient();
        AtomicBoolean called = new AtomicBoolean(false);
        Future<McpSchema.CallToolResult> call = CALLS.submit(() -> {
            client.initialize();
            called.set(true);
            return client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        });
        McpSchema.CallToolResult result;
        try {
            result = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            closeLater(client, false);
            String message = "notification MCP server " + url() + " unreachable: no answer within "
                    + timeout.toSeconds() + " s";
            if (!called.get()) {
                throw new NotCalledYet(message);
            }
            throw new ToolException(message + " (" + tool + " was called; not retried to avoid a duplicate)");
        } catch (InterruptedException e) {
            closeLater(client, false);
            Thread.currentThread().interrupt();
            throw new ToolException("interrupted while calling " + tool);
        } catch (ExecutionException e) {
            closeLater(client, false);
            throw new ToolException("notification MCP server " + url() + " unreachable: "
                    + (e.getCause() == null ? e : e.getCause().getMessage()), e.getCause());
        }
        closeLater(client, true);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new ToolException(tool + " failed: " + text(result));
        }
        return result;
    }

    private McpSyncClient newClient() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(endpoint)
                .connectTimeout(timeout)
                .openConnectionOnStartup(false)
                .build();
        return McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("finally-app", "1.0.0"))
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .build();
    }

    /** Graceful close only after a working exchange; a client that never connected is closed immediately. */
    private static void closeLater(McpSyncClient client, boolean graceful) {
        CALLS.execute(() -> {
            try {
                if (graceful) {
                    client.closeGracefully();
                } else {
                    client.close();
                }
            } catch (RuntimeException ignored) {
                // best effort
            }
        });
    }

    /** {@code messageId} from {@code structuredContent}, else the text after "sent ". */
    static String messageId(McpSchema.CallToolResult result) {
        if (result.structuredContent() instanceof Map<?, ?> map && map.get("messageId") != null) {
            return map.get("messageId").toString();
        }
        String t = text(result);
        return t.startsWith("sent ") ? t.substring(5) : t;
    }

    static String text(McpSchema.CallToolResult result) {
        if (result.content() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content c : result.content()) {
            if (c instanceof McpSchema.TextContent tc && tc.text() != null) {
                sb.append(sb.isEmpty() ? "" : " ").append(tc.text());
            }
        }
        return sb.toString();
    }
}
