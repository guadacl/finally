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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * MCP client for the email MCP server ({@code mcp/email-mcp-server}): calls its {@code send_email} tool over
 * Streamable HTTP ({@code finally.mail.mcp-url}, e.g. {@code http://email-mcp:3001/mcp}).
 *
 * <p>One short-lived client per email (initialize, call, close): alert emails are rare, the server is
 * stateless, and no connection state can go stale while the MCP container restarts.
 */
@Component
public class McpEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(McpEmailGateway.class);
    static final String TOOL = "send_email";

    private final String baseUrl;
    private final String endpoint;
    private final Duration timeout;

    @org.springframework.beans.factory.annotation.Autowired // two constructors: tell Spring which one to use
    public McpEmailGateway(FinallyProperties props) {
        this(props.mail().mcpUrl(), props.mail().timeout());
    }

    McpEmailGateway(String mcpUrl, Duration timeout) {
        String[] parts = split(mcpUrl);
        this.baseUrl = parts[0];
        this.endpoint = parts[1];
        this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
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

    private static final ExecutorService CALLS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "finally-mcp-email");
        t.setDaemon(true);
        return t;
    });

    /**
     * Prepares the MCP client stack in the background once the app is up: the first client in a fresh JVM pays
     * several seconds of one-off class loading / initialisation (measured ~25 s on a slow container), which would
     * otherwise eat the first email's deadline. Does an MCP handshake only (no email); failures are ignored.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (baseUrl.isEmpty()) {
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
            log.info("Email MCP server {}{} reachable (warm-up {} ms)", baseUrl, endpoint,
                    (System.nanoTime() - start) / 1_000_000);
            return true;
        } catch (RuntimeException e) {
            log.info("Email MCP server {}{} not reachable yet ({}); emails will retry it when needed",
                    baseUrl, endpoint, e.getMessage());
            return false;
        } finally {
            if (client != null) {
                closeLater(client, false);
            }
        }
    }

    /**
     * Initialize + {@code send_email} under a hard deadline of {@link #timeout}: the SDK's own timeouts do not
     * bound every failure mode (an unreachable server made {@code initialize()} block ~15 s with 3 s timeouts).
     * When the deadline passes before {@code send_email} was even called (nothing can have been sent), the
     * exchange is retried once. The client is closed in the background afterwards, so a slow shutdown never delays
     * or fails a sent email. Threads are not interrupted (the SDK blocks inside Reactor); a timed-out client is
     * closed from outside.
     */
    @Override
    public String sendEmail(String to, String subject, String text) {
        if (baseUrl.isEmpty()) {
            throw new EmailException("no email MCP server configured (EMAIL_MCP_URL)");
        }
        try {
            return attempt(to, subject, text);
        } catch (NotCalledYet e) {
            log.info("Email MCP server {}{} slow to answer; retrying once", baseUrl, endpoint);
            try {
                return attempt(to, subject, text);
            } catch (NotCalledYet again) {
                throw new EmailException(again.getMessage());
            }
        }
    }

    /** Deadline hit before {@code send_email} was called: safe to retry. */
    static final class NotCalledYet extends EmailException {
        NotCalledYet(String message) {
            super(message);
        }
    }

    private String attempt(String to, String subject, String text) {
        McpSyncClient client = newClient();
        AtomicBoolean called = new AtomicBoolean(false);
        Future<McpSchema.CallToolResult> call = CALLS.submit(() -> {
            client.initialize();
            called.set(true);
            return client.callTool(
                    new McpSchema.CallToolRequest(TOOL, Map.of("to", to, "subject", subject, "text", text)));
        });
        McpSchema.CallToolResult result;
        try {
            result = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            closeLater(client, false);
            String message = "email MCP server " + baseUrl + endpoint + " unreachable: no answer within "
                    + timeout.toSeconds() + " s";
            if (!called.get()) {
                throw new NotCalledYet(message);
            }
            throw new EmailException(message + " (send_email was called; not retried to avoid a duplicate)");
        } catch (InterruptedException e) {
            closeLater(client, false);
            Thread.currentThread().interrupt();
            throw new EmailException("interrupted while sending email");
        } catch (ExecutionException e) {
            closeLater(client, false);
            throw new EmailException("email MCP server " + baseUrl + endpoint + " unreachable: "
                    + (e.getCause() == null ? e : e.getCause().getMessage()), e.getCause());
        }
        closeLater(client, true);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new EmailException("send_email failed: " + text(result));
        }
        return messageId(result);
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
