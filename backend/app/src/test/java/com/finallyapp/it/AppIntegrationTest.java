package com.finallyapp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finallyapp.contracts.KanbanCardRef;
import com.finallyapp.contracts.KanbanColumn;
import com.finallyapp.contracts.KanbanWorkflow;
import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationType;
import com.finallyapp.market.PriceCache;
import com.finallyapp.stream.SseNotificationPublisher;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Full app against a real Postgres (Flyway migrations from finally-db), with a mocked Camunda workflow. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "finally.workflow.enabled=false",
        "finally.auth.enabled=false",
        "camunda.client.enabled=false",
        "finally.llm.mock=true",
        "finally.market.massive-api-key=",
        "finally.market.finnhub-api-key=",
        "finally.portfolio.snapshot-initial-delay-ms=600000",
        "finally.stream.heartbeat-ms=600000"
})
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    PriceCache cache;
    @Autowired
    SseNotificationPublisher notifications;

    @MockitoBean
    KanbanWorkflow workflow;

    private final HttpClient http = HttpClient.newHttpClient();

    record Res(int status, JsonNode body) {
    }

    @BeforeEach
    void stubWorkflow() {
        when(workflow.cardCreated(any())).thenReturn(4242L);
    }

    private Res call(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body() == null || r.body().isBlank() ? null : mapper.readTree(r.body()));
    }

    @Test
    @Order(1)
    void healthAndSeededWatchlistWithPrices() throws Exception {
        Res h = call("GET", "/api/health", null);
        assertThat(h.status()).isEqualTo(200);
        assertThat(h.body().path("database").asText()).isEqualTo("up");
        assertThat(h.body().path("marketData").asText()).isEqualTo("simulator");
        assertThat(h.body().path("workflow").asText()).isEqualTo("disabled");

        Res w = call("GET", "/api/watchlist", null);
        assertThat(w.status()).isEqualTo(200);
        List<String> tickers = new ArrayList<>();
        w.body().forEach(n -> tickers.add(n.path("ticker").asText()));
        assertThat(tickers).containsExactlyInAnyOrder("AAPL", "GOOGL", "MSFT", "AMZN", "TSLA", "NVDA", "META", "JPM", "V", "NFLX");
        assertThat(w.body().get(0).path("price").isNumber()).isTrue();
        assertThat(w.body().get(0).path("sessionOpenPrice").isNumber()).isTrue();

        Res p = call("GET", "/api/portfolio", null);
        assertThat(p.body().path("cashBalance").decimalValue()).isEqualByComparingTo("10000");
        assertThat(p.body().path("positions")).isEmpty();
    }

    @Test
    @Order(2)
    void watchlistAddDuplicateRemove() throws Exception {
        Res add = call("POST", "/api/watchlist", "{\"ticker\":\"pypl\"}");
        assertThat(add.status()).isEqualTo(201);
        assertThat(add.body().path("ticker").asText()).isEqualTo("PYPL");
        assertThat(add.body().path("price").isNumber()).isTrue(); // simulator prices it immediately
        assertThat(cache.contains("PYPL")).isTrue();

        assertThat(call("POST", "/api/watchlist", "{\"ticker\":\"PYPL\"}").status()).isEqualTo(409);
        Res bad = call("POST", "/api/watchlist", "{\"ticker\":\"bad ticker!\"}");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().path("error").asText()).isEqualTo("INVALID_TICKER");

        assertThat(call("DELETE", "/api/watchlist/PYPL", null).status()).isEqualTo(204);
        assertThat(cache.contains("PYPL")).isFalse();
        assertThat(call("DELETE", "/api/watchlist/PYPL", null).status()).isEqualTo(404);
    }

    @Test
    @Order(3)
    void buyAndSellFlow() throws Exception {
        BigDecimal price = cache.price("AAPL");
        Res buy = call("POST", "/api/portfolio/trade", "{\"ticker\":\"AAPL\",\"quantity\":2.5,\"side\":\"buy\"}");
        assertThat(buy.status()).isEqualTo(200);
        assertThat(buy.body().path("trade").path("side").asText()).isEqualTo("buy");
        BigDecimal fill = buy.body().path("trade").path("price").decimalValue();
        assertThat(fill).isPositive();
        JsonNode pf = buy.body().path("portfolio");
        assertThat(pf.path("cashBalance").decimalValue())
                .isEqualByComparingTo(new BigDecimal("10000").subtract(fill.multiply(new BigDecimal("2.5"))).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(pf.path("positions").get(0).path("ticker").asText()).isEqualTo("AAPL");
        assertThat(pf.path("positions").get(0).path("quantity").decimalValue()).isEqualByComparingTo("2.5");
        assertThat(price).isNotNull();

        Res over = call("POST", "/api/portfolio/trade", "{\"ticker\":\"AAPL\",\"quantity\":10,\"side\":\"sell\"}");
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.body().path("error").asText()).isEqualTo("INSUFFICIENT_SHARES");

        Res broke = call("POST", "/api/portfolio/trade", "{\"ticker\":\"NVDA\",\"quantity\":1000,\"side\":\"buy\"}");
        assertThat(broke.status()).isEqualTo(400);
        assertThat(broke.body().path("error").asText()).isEqualTo("INSUFFICIENT_CASH");

        Res zero = call("POST", "/api/portfolio/trade", "{\"ticker\":\"AAPL\",\"quantity\":0,\"side\":\"buy\"}");
        assertThat(zero.body().path("error").asText()).isEqualTo("INVALID_QUANTITY");

        Res unknownSell = call("POST", "/api/portfolio/trade", "{\"ticker\":\"ZZZZ\",\"quantity\":1,\"side\":\"sell\"}");
        assertThat(unknownSell.status()).isEqualTo(400);
        assertThat(unknownSell.body().path("error").asText()).isEqualTo("UNKNOWN_TICKER");

        Res sell = call("POST", "/api/portfolio/trade", "{\"ticker\":\"AAPL\",\"quantity\":2.5,\"side\":\"sell\"}");
        assertThat(sell.status()).isEqualTo(200);
        assertThat(sell.body().path("portfolio").path("positions")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM positions").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM trades").query(Integer.class).single()).isEqualTo(2);

        Res hist = call("GET", "/api/portfolio/history", null);
        assertThat(hist.body().size()).isGreaterThanOrEqualTo(2);
        assertThat(hist.body().get(0).path("recordedAt").asText()).endsWith("Z");
    }

    @Test
    @Order(4)
    void kanbanLifecycleDrivesWorkflowAndAlerts() throws Exception {
        // RESISTANCE far above the price: no alert on create.
        Res created = call("POST", "/api/kanban", "{\"ticker\":\"msft\",\"column\":\"RESISTANCE\",\"targetPrice\":100000}");
        assertThat(created.status()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asText());
        assertThat(created.body().path("ticker").asText()).isEqualTo("MSFT");
        assertThat(created.body().path("armed").asBoolean()).isTrue();
        assertThat(created.body().path("currentPrice").isNumber()).isTrue();
        verify(workflow).cardCreated(eq(new KanbanCardRef(id, "MSFT", KanbanColumn.RESISTANCE, new BigDecimal("100000.0000"), "default")));
        assertThat(jdbc.sql("SELECT process_instance_key FROM kanban_cards WHERE id = :id").param("id", id)
                .query(Long.class).single()).isEqualTo(4242L);

        // Move to SUPPORT with the same huge target: price is below -> fires on the next tick.
        Res moved = call("PATCH", "/api/kanban/" + id, "{\"column\":\"SUPPORT\"}");
        assertThat(moved.status()).isEqualTo(200);
        assertThat(moved.body().path("column").asText()).isEqualTo("SUPPORT");
        verify(workflow).cardMoved(any());
        verify(workflow, timeout(5000)).targetPriceHit(any(), any());
        long deadline = System.currentTimeMillis() + 5000;
        boolean armed = true;
        while (System.currentTimeMillis() < deadline && armed) {
            armed = jdbc.sql("SELECT armed FROM kanban_cards WHERE id = :id").param("id", id).query(Boolean.class).single();
            Thread.sleep(100);
        }
        assertThat(armed).isFalse();

        // Editing the target re-arms.
        Res edited = call("PATCH", "/api/kanban/" + id, "{\"targetPrice\":1}");
        assertThat(edited.body().path("armed").asBoolean()).isTrue();
        assertThat(edited.body().path("targetPrice").decimalValue()).isEqualByComparingTo("1");

        Res bad = call("PATCH", "/api/kanban/" + id, "{\"targetPrice\":-5}");
        assertThat(bad.status()).isEqualTo(400);

        Res list = call("GET", "/api/kanban", null);
        assertThat(list.body().size()).isEqualTo(1);

        assertThat(call("DELETE", "/api/kanban/" + id, null).status()).isEqualTo(204);
        verify(workflow).cardDeleted(id);
        assertThat(call("DELETE", "/api/kanban/" + id, null).status()).isEqualTo(404);
        assertThat(call("PATCH", "/api/kanban/" + id, "{}").status()).isEqualTo(404);
    }

    @Test
    @Order(5)
    void mockChatExecutesTradesAndPersistsHistory() throws Exception {
        Res r = call("POST", "/api/chat", "{\"message\":\"buy 3 AAPL and add PYPL\"}");
        assertThat(r.status()).isEqualTo(200);
        JsonNode trades = r.body().path("actions").path("trades");
        assertThat(trades.get(0).path("ticker").asText()).isEqualTo("AAPL");
        assertThat(trades.get(0).path("status").asText()).isEqualTo("executed");
        assertThat(trades.get(0).path("price").isNumber()).isTrue();
        assertThat(r.body().path("actions").path("watchlistChanges").get(0).path("status").asText()).isEqualTo("executed");

        Res fail = call("POST", "/api/chat", "{\"message\":\"sell 999 TSLA\"}");
        assertThat(fail.status()).isEqualTo(200);
        assertThat(fail.body().path("actions").path("trades").get(0).path("status").asText()).isEqualTo("failed");
        assertThat(fail.body().path("actions").path("trades").get(0).path("error").asText()).contains("Insufficient shares");
        assertThat(fail.body().path("message").asText()).contains("failed");

        Res hist = call("GET", "/api/chat/history", null);
        assertThat(hist.body().size()).isEqualTo(4);
        assertThat(hist.body().get(0).path("role").asText()).isEqualTo("user");
        assertThat(hist.body().get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(hist.body().get(1).path("actions").path("trades").get(0).path("ticker").asText()).isEqualTo("AAPL");
        assertThat(hist.body().get(0).path("actions").isNull() || hist.body().get(0).path("actions").isMissingNode()).isTrue();

        assertThat(call("POST", "/api/chat", "{\"message\":\"\"}").status()).isEqualTo(400);
    }

    @Test
    @Order(6)
    void priceStreamEmitsPriceEvents() throws Exception {
        List<String> lines = readSse("/api/stream/prices", 12, () -> { });
        assertThat(lines).anyMatch(l -> l.equals("event:price"));
        String data = lines.stream().filter(l -> l.startsWith("data:")).findFirst().orElseThrow();
        JsonNode ev = mapper.readTree(data.substring(5));
        assertThat(ev.path("ticker").isTextual()).isTrue();
        assertThat(ev.path("price").isNumber()).isTrue();
        assertThat(ev.path("previousPrice").isNumber()).isTrue();
        assertThat(ev.path("direction").asText()).isIn("up", "down", "flat");
        assertThat(ev.path("timestamp").asText()).endsWith("Z");
    }

    @Test
    @Order(7)
    void notificationStreamDeliversPublishedNotifications() throws Exception {
        Notification n = Notification.of(NotificationType.TARGET_HIT,
                new KanbanCardRef(UUID.randomUUID(), "AAPL", KanbanColumn.SUPPORT, new BigDecimal("185"), "default"),
                new BigDecimal("184.97"), "AAPL hit stop loss 185.00 (now 184.97)");
        List<String> lines = readSse("/api/stream/notifications", 3, () -> {
            // publish repeatedly until the client is registered
            CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 20; i++) {
                    notifications.publish(n);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
        });
        assertThat(lines).contains("event:notification");
        String data = lines.stream().filter(l -> l.startsWith("data:")).findFirst().orElseThrow();
        JsonNode ev = mapper.readTree(data.substring(5));
        assertThat(ev.path("type").asText()).isEqualTo("TARGET_HIT");
        assertThat(ev.path("column").asText()).isEqualTo("SUPPORT");
        assertThat(ev.path("targetPrice").isNumber()).isTrue();
        assertThat(ev.path("price").decimalValue()).isEqualByComparingTo("184.97");
        assertThat(ev.path("createdAt").asText()).endsWith("Z");
    }

    /** Reads SSE lines until {@code wanted} non-empty event lines have been collected (or 10 s pass). */
    private List<String> readSse(String path, int wanted, Runnable afterConnect) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/event-stream").GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        afterConnect.run();
        List<String> lines = new ArrayList<>();
        CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
                String line;
                int dataLines = 0;
                while ((line = r.readLine()) != null) {
                    if (!line.isEmpty()) {
                        synchronized (lines) {
                            lines.add(line);
                        }
                    }
                    if (line.startsWith("data:") && ++dataLines >= wanted) {
                        return;
                    }
                }
            } catch (Exception ignored) {
                // stream closed
            }
        });
        try {
            f.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException ignored) {
            // return what we have
        }
        synchronized (lines) {
            return new ArrayList<>(lines);
        }
    }
}
