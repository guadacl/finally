package com.finallyapp.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MassiveMarketDataSourceTest {

    private static final String REST_RESPONSE = """
            {"status":"OK","count":2,"tickers":[
              {"ticker":"AAPL","todaysChangePerc":0.5,
               "day":{"o":189.0,"h":191.0,"l":188.5,"c":190.4,"v":1000},
               "lastTrade":{"p":190.51,"s":100,"t":1730541600000000000},
               "min":{"c":190.45},"prevDay":{"c":189.0}},
              {"ticker":"MSFT","day":{"c":421.1},"prevDay":{"c":419.0}},
              {"ticker":"BAD"},
              {"lastTrade":{"p":1.0}}
            ]}
            """;

    private PriceCache cache;
    private MockRestServiceServer server;
    private MassiveMarketDataSource source;

    @BeforeEach
    void setUp() {
        cache = new PriceCache();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        source = new MassiveMarketDataSource(cache, builder, "https://api.massive.test", "secret",
                Duration.ofSeconds(15), new ObjectMapper());
    }

    @Test
    void parsesRestSnapshotShapeAndSkipsMalformed() {
        List<MassiveMarketDataSource.Snapshot> snaps = source.parseSnapshots(REST_RESPONSE);
        assertThat(snaps).extracting(MassiveMarketDataSource.Snapshot::ticker).containsExactly("AAPL", "MSFT");
        assertThat(snaps.get(0).price()).isEqualTo(190.51);
        assertThat(snaps.get(0).timestamp()).isEqualTo(Instant.ofEpochSecond(1730541600L));
        assertThat(snaps.get(1).price()).isEqualTo(421.1); // falls back to day close
    }

    @Test
    void parsesSnakeCaseShape() {
        String json = """
                [{"ticker":"AAPL","last_trade":{"price":125.07,"timestamp":1675190399000},"day":{"previous_close":129.61}}]
                """;
        List<MassiveMarketDataSource.Snapshot> snaps = source.parseSnapshots(json);
        assertThat(snaps).hasSize(1);
        assertThat(snaps.get(0).price()).isEqualTo(125.07);
        assertThat(snaps.get(0).timestamp()).isEqualTo(Instant.ofEpochMilli(1675190399000L));
    }

    @Test
    void garbageResponsesYieldNothing() {
        assertThat(source.parseSnapshots("not json")).isEmpty();
        assertThat(source.parseSnapshots("")).isEmpty();
        assertThat(source.parseSnapshots(null)).isEmpty();
        assertThat(source.parseSnapshots("{\"status\":\"ERROR\"}")).isEmpty();
    }

    @Test
    void timestampUnitsDetected() {
        assertThat(MassiveMarketDataSource.toInstant(1_700_000_000L)).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
        assertThat(MassiveMarketDataSource.toInstant(1_700_000_000_000L)).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(MassiveMarketDataSource.toInstant(1_700_000_000_000_000L)).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(MassiveMarketDataSource.toInstant(1_700_000_000_000_000_000L)).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
    }

    @Test
    void pollCallsSnapshotEndpointAndUpdatesCache() {
        source.addTicker("aapl");
        source.addTicker("MSFT");
        server.expect(requestTo(startsWith("https://api.massive.test/v2/snapshot/locale/us/markets/stocks/tickers")))
                .andExpect(queryParam("tickers", "AAPL,MSFT"))
                .andExpect(header("Authorization", "Bearer secret"))
                .andRespond(withSuccess(REST_RESPONSE, MediaType.APPLICATION_JSON));
        source.pollOnce();
        server.verify();
        assertThat(cache.price("AAPL")).isEqualByComparingTo("190.51");
        assertThat(cache.price("MSFT")).isEqualByComparingTo("421.10");
        assertThat(cache.contains("BAD")).isFalse();
    }

    @Test
    void pollFailureIsSwallowed() {
        source.addTicker("AAPL");
        server.expect(requestTo(startsWith("https://api.massive.test/")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        source.pollOnce();
        server.verify();
        assertThat(cache.size()).isZero();
    }

    @Test
    void noTickersNoRequest() {
        source.pollOnce();
        server.verify();
    }

    @Test
    void addRemoveTickers() {
        source.addTicker("AAPL");
        source.addTicker("AAPL");
        cache.update("AAPL", 1.0);
        assertThat(source.getTickers()).containsExactly("AAPL");
        source.removeTicker("aapl");
        assertThat(source.getTickers()).isEmpty();
        assertThat(cache.contains("AAPL")).isFalse();
        assertThat(source.name()).isEqualTo("massive");
    }
}
