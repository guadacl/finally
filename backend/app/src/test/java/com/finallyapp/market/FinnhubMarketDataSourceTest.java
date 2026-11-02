package com.finallyapp.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finallyapp.config.FinallyProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FinnhubMarketDataSourceTest {

    private static final String AAPL = """
            {"c":261.74,"d":1.2,"dp":0.46,"h":263.31,"l":260.68,"o":261.07,"pc":260.54,"t":1762117200}
            """;

    private PriceCache cache;
    private MockRestServiceServer server;
    private FinnhubMarketDataSource source;

    @BeforeEach
    void setUp() {
        cache = new PriceCache();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        source = new FinnhubMarketDataSource(cache, builder, "https://finnhub.test/api/v1", "secret",
                Duration.ofMillis(1100), new ObjectMapper());
    }

    @Test
    void parsesQuote() {
        var q = source.parseQuote("aapl", AAPL).orElseThrow();
        assertThat(q.ticker()).isEqualTo("AAPL");
        assertThat(q.price()).isEqualTo(261.74);
        assertThat(q.timestamp()).isEqualTo(Instant.ofEpochSecond(1762117200L));
    }

    @Test
    void unknownSymbolAndMalformedBodiesAreSkipped() {
        assertThat(source.parseQuote("ZZZZ", "{\"c\":0,\"d\":null,\"dp\":null,\"h\":0,\"l\":0,\"o\":0,\"pc\":0,\"t\":0}")).isEmpty();
        assertThat(source.parseQuote("AAPL", "not json")).isEmpty();
        assertThat(source.parseQuote("AAPL", "")).isEmpty();
        assertThat(source.parseQuote("AAPL", null)).isEmpty();
    }

    @Test
    void missingTimestampFallsBackToNow() {
        Instant before = Instant.now();
        var q = source.parseQuote("AAPL", "{\"c\":10.5}").orElseThrow();
        assertThat(q.timestamp()).isAfterOrEqualTo(before);
    }

    @Test
    void pollsTickersRoundRobinWithTokenHeaderAndUpdatesCache() {
        source.addTicker("AAPL");
        source.addTicker("msft");
        server.expect(once(), queryParam("symbol", "AAPL"))
                .andExpect(header("X-Finnhub-Token", "secret"))
                .andRespond(withSuccess(AAPL, MediaType.APPLICATION_JSON));
        server.expect(once(), queryParam("symbol", "MSFT"))
                .andRespond(withSuccess("{\"c\":512.3,\"t\":1762117200}", MediaType.APPLICATION_JSON));

        source.pollNext();
        source.pollNext();

        server.verify();
        assertThat(cache.get("AAPL").orElseThrow().price()).isEqualByComparingTo(new BigDecimal("261.74"));
        assertThat(cache.get("MSFT").orElseThrow().price()).isEqualByComparingTo(new BigDecimal("512.3"));
    }

    @Test
    void errorsNeverThrowAndRateLimitPausesPolling() {
        source.addTicker("AAPL");
        server.expect(once(), queryParam("symbol", "AAPL")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(once(), queryParam("symbol", "AAPL")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        source.pollNext(); // 403: logged, rotation continues
        source.pollNext(); // 429: pauses
        source.pollNext(); // paused: no request (an unexpected request would fail verify)

        server.verify();
        assertThat(cache.get("AAPL")).isEmpty();
    }

    @Test
    void removeTickerDropsItFromRotationAndCache() {
        source.addTicker("AAPL");
        source.addTicker("aapl");
        cache.update("AAPL", 1.0);
        assertThat(source.getTickers()).isEqualTo(List.of("AAPL"));
        source.removeTicker("aapl");
        assertThat(source.getTickers()).isEmpty();
        assertThat(cache.get("AAPL")).isEmpty();
        assertThat(source.name()).isEqualTo("finnhub");
    }

    @Test
    void factoryPrefersFinnhubOverMassive() {
        var both = new FinallyProperties.Market("massive-key", "https://api.massive.com", Duration.ofSeconds(15),
                Duration.ofMillis(500), 0.001, "finnhub-key", "https://finnhub.io/api/v1", Duration.ofMillis(1100));
        assertThat(MarketDataConfig.create(both, cache, RestClient.builder(), new ObjectMapper()))
                .isInstanceOf(FinnhubMarketDataSource.class);
    }
}
