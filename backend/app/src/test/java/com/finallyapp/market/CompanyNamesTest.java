package com.finallyapp.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CompanyNamesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T15:00:00Z"), ZoneOffset.UTC);

    @Test
    void builtInNamesNeedNoKey() {
        CompanyNames names = new CompanyNames("", "x", "", "y", RestClient.builder(), new ObjectMapper(), CLOCK);
        assertThat(names.lookup("aapl")).contains("Apple Inc.");
        assertThat(names.lookup("NFLX")).contains("Netflix, Inc.");
        assertThat(names.lookup("ZZZZ")).isEmpty();
    }

    @Test
    void looksUpFinnhubOnceAndCaches() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(containsString("/stock/profile2?symbol=ROKU")))
                .andExpect(header("X-Finnhub-Token", "fk"))
                .andRespond(withSuccess("{\"name\":\"Roku Inc\",\"ticker\":\"ROKU\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(containsString("symbol=NOPE")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        CompanyNames names = new CompanyNames("fk", "https://finnhub.test", "", "y", builder, new ObjectMapper(), CLOCK);

        assertThat(names.cached("ROKU")).isEmpty();
        assertThat(names.lookup("ROKU")).contains("Roku Inc");
        assertThat(names.lookup("ROKU")).contains("Roku Inc"); // cached: no second call
        assertThat(names.cached("roku")).contains("Roku Inc");
        assertThat(names.lookup("NOPE")).isEmpty();
        assertThat(names.lookup("NOPE")).isEmpty(); // unknown is remembered

        server.verify();
    }

    @Test
    void fallsBackToMassiveTickerDetails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(containsString("/v3/reference/tickers/ROKU")))
                .andExpect(header("Authorization", "Bearer mk"))
                .andRespond(withSuccess("{\"results\":{\"ticker\":\"ROKU\",\"name\":\"Roku, Inc.\"}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(containsString("/v3/reference/tickers/NOPE")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        CompanyNames names = new CompanyNames("", "x", "mk", "https://massive.test", builder, new ObjectMapper(), CLOCK);

        assertThat(names.lookup("ROKU")).contains("Roku, Inc.");
        assertThat(names.lookup("NOPE")).isEmpty();
        assertThat(names.lookup("NOPE")).isEmpty();
        server.verify();
    }

    @Test
    void failedCallIsNotFatal() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(containsString("symbol=ROKU"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        CompanyNames names = new CompanyNames("fk", "https://finnhub.test", "", "y", builder, new ObjectMapper(), CLOCK);

        assertThat(names.lookup("ROKU")).isEmpty();
        assertThat(names.lookup("ROKU")).isEmpty(); // backs off: no second call within the retry window
        server.verify();
    }
}
