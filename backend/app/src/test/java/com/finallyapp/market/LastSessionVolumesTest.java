package com.finallyapp.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LastSessionVolumesTest {

    private static final String MONDAY = """
            {"queryCount":3,"resultsCount":3,"results":[
              {"T":"AAPL","v":6.4757691e+07,"o":342.67,"c":338.32},
              {"T":"MSFT","v":21000000},
              {"T":"BAD"}]}
            """;
    private static final String EMPTY = "{\"queryCount\":0,\"resultsCount\":0}";

    /** Tuesday 2026-11-03 10:00 New York. */
    private static final Clock TUESDAY = Clock.fixed(Instant.parse("2026-11-03T15:00:00Z"), ZoneOffset.UTC);

    private static LastSessionVolumes volumes(RestClient.Builder builder, String key, Clock clock) {
        return new LastSessionVolumes(key, "https://api.massive.test", builder, new ObjectMapper(), clock);
    }

    @Test
    void loadsThePreviousSessionOnceWithOneCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(containsString("/v2/aggs/grouped/locale/us/market/stocks/2026-11-02")))
                .andExpect(header("Authorization", "Bearer key"))
                .andRespond(withSuccess(MONDAY, MediaType.APPLICATION_JSON));
        LastSessionVolumes v = volumes(builder, "key", TUESDAY);

        v.refresh();
        v.refresh(); // same session: no second call (the mock server would fail on it)

        server.verify();
        var aapl = v.get("aapl").orElseThrow();
        assertThat(aapl.volume()).isEqualTo(64_757_691L);
        assertThat(aapl.session()).isEqualTo(LocalDate.parse("2026-11-02"));
        assertThat(v.get("MSFT").orElseThrow().volume()).isEqualTo(21_000_000L);
        assertThat(v.get("BAD")).isEmpty();
        assertThat(v.get("ZZZZ")).isEmpty();
    }

    @Test
    void skipsHolidaysAndWeekends() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(containsString("/stocks/2026-11-02"))).andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(containsString("/stocks/2026-10-30"))).andRespond(withSuccess(MONDAY, MediaType.APPLICATION_JSON));
        LastSessionVolumes v = volumes(builder, "key", TUESDAY);

        v.refresh();

        server.verify();
        assertThat(v.get("AAPL").orElseThrow().session()).isEqualTo(LocalDate.parse("2026-10-30"));
    }

    @Test
    void disabledWithoutAMassiveKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LastSessionVolumes v = volumes(builder, "", TUESDAY);
        v.refresh();
        server.verify(); // no request at all
        assertThat(v.isEnabled()).isFalse();
        assertThat(v.get("AAPL")).isEmpty();
    }

    @Test
    void previousWeekdaySkipsWeekend() {
        assertThat(LastSessionVolumes.previousWeekday(LocalDate.parse("2026-11-02"))).isEqualTo(LocalDate.parse("2026-10-30"));
        assertThat(LastSessionVolumes.previousWeekday(LocalDate.parse("2026-11-03"))).isEqualTo(LocalDate.parse("2026-11-02"));
    }
}
