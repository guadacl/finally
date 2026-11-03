package com.finallyapp.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DailyClosesTest {

    /** The StockCharts RSI example as daily bars (first RSI 70.46), plus a malformed entry. */
    private static final String AGGS = """
            {"ticker":"AAPL","resultsCount":16,"results":[
              {"c":44.34},{"c":44.09},{"c":44.15},{"c":43.61},{"c":44.33},{"c":44.83},{"c":45.10},{"c":45.42},
              {"c":45.84},{"c":46.08},{"c":45.89},{"c":46.03},{"c":45.61},{"c":46.28},{"c":46.28},{"v":1}]}
            """;

    /** Tuesday 2026-11-03 08:00 New York: before the open. */
    private static final Clock PRE_OPEN = Clock.fixed(Instant.parse("2026-11-03T13:00:00Z"), ZoneOffset.UTC);
    /** Tuesday 2026-11-03 11:00 New York: session running. */
    private static final Clock OPEN = Clock.fixed(Instant.parse("2026-11-03T16:00:00Z"), ZoneOffset.UTC);

    private static DailyCloses closes(RestClient.Builder builder, String key, Clock clock) {
        return new DailyCloses(key, "https://api.massive.test", builder, new ObjectMapper(), clock);
    }

    @Test
    void loadsOnDemandOncePerDayAndComputesRsi() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(containsString("/v2/aggs/ticker/AAPL/range/1/day/2026-06-06/2026-11-02")))
                .andExpect(header("Authorization", "Bearer key"))
                .andRespond(withSuccess(AGGS, MediaType.APPLICATION_JSON));
        DailyCloses c = closes(builder, "key", PRE_OPEN);

        assertThat(c.rsi("aapl", null)).isEmpty(); // not loaded yet: requests a load
        c.loadNext();
        c.loadNext(); // nothing wanted any more: no second call (the mock server would fail on it)

        server.verify();
        assertThat(c.rsi("AAPL", new BigDecimal("46.00")).getAsDouble()).isCloseTo(70.46, within(0.01)); // pre-open: live price ignored
        c.loadNext(); // loaded today: still no call
        server.verify();
    }

    @Test
    void countsTheLivePriceAsTodaysCloseDuringTheSession() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(containsString("/v2/aggs/ticker/AAPL/")))
                .andRespond(withSuccess(AGGS, MediaType.APPLICATION_JSON));
        DailyCloses c = closes(builder, "key", OPEN);
        c.rsi("AAPL", null);
        c.loadNext();

        assertThat(c.rsi("AAPL", new BigDecimal("46.00")).getAsDouble()).isCloseTo(66.25, within(0.01));
        assertThat(c.rsi("AAPL", null).getAsDouble()).isCloseTo(70.46, within(0.01));
    }

    @Test
    void disabledWithoutAKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DailyCloses c = closes(builder, "", OPEN);

        assertThat(c.isEnabled()).isFalse();
        assertThat(c.rsi("AAPL", BigDecimal.TEN)).isEmpty();
        c.loadNext();
        server.verify(); // no calls
    }

    @Test
    void parseSkipsBadEntries() {
        DailyCloses c = closes(RestClient.builder(), "key", OPEN);
        assertThat(c.parse(AGGS)).hasSize(15);
        assertThat(c.parse("not json")).isEmpty();
        assertThat(c.parse(null)).isEmpty();
    }
}
