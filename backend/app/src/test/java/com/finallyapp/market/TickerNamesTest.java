package com.finallyapp.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TickerNamesTest {

    private static void drain(ExecutorService executor) throws InterruptedException {
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void knowsCommonTickersWithoutAnyKeyOrCall() {
        TickerNames names = new TickerNames("", "https://api.massive.test", "", "https://finnhub.test",
                RestClient.builder(), new ObjectMapper(), Executors.newSingleThreadExecutor());

        assertThat(names.get("aapl")).contains("Apple Inc.");
        assertThat(names.get("JPM")).contains("JPMorgan Chase & Co.");
        assertThat(names.get("ZZZZ")).isEmpty(); // unknown and no key: stays unknown, no lookup
    }

    @Test
    void looksUpAnUnknownTickerOnceFromMassive() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(containsString("/v3/reference/tickers/SHOP")))
                .andExpect(header("Authorization", "Bearer mkey"))
                .andRespond(withSuccess("{\"results\":{\"ticker\":\"SHOP\",\"name\":\"Shopify Inc.\"},\"status\":\"OK\"}",
                        MediaType.APPLICATION_JSON));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TickerNames names = new TickerNames("mkey", "https://api.massive.test", "", "https://finnhub.test",
                builder, new ObjectMapper(), executor);

        assertThat(names.get("SHOP")).isEmpty(); // non-blocking: unknown on the first call
        names.get("shop"); // already being looked up: no second call (the mock server would fail on it)
        drain(executor);

        server.verify();
        assertThat(names.get("SHOP")).contains("Shopify Inc.");
    }

    @Test
    void fallsBackToFinnhubWhenMassiveHasNoName() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(containsString("/v3/reference/tickers/SHOP")))
                .andRespond(withResourceNotFound());
        server.expect(once(), requestTo(containsString("/stock/profile2?symbol=SHOP&token=fkey")))
                .andRespond(withSuccess("{\"ticker\":\"SHOP\",\"name\":\"Shopify Inc\"}", MediaType.APPLICATION_JSON));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TickerNames names = new TickerNames("mkey", "https://api.massive.test", "fkey", "https://finnhub.test",
                builder, new ObjectMapper(), executor);

        names.get("SHOP");
        drain(executor);

        server.verify();
        assertThat(names.get("SHOP")).contains("Shopify Inc");
    }
}
