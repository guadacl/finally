package com.finallyapp.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LlmResponseParserTest {

    private final LlmResponseParser parser = new LlmResponseParser(new ObjectMapper());

    @Test
    void parsesFullSchema() {
        LlmDecision d = parser.parse("""
                {"message":"Buying AAPL and watching PYPL",
                 "trades":[{"ticker":"AAPL","side":"buy","quantity":10},{"ticker":"tsla","side":"SELL","quantity":0.5}],
                 "watchlist_changes":[{"ticker":"PYPL","action":"add"},{"ticker":"NFLX","action":"remove"}]}
                """);
        assertThat(d.message()).isEqualTo("Buying AAPL and watching PYPL");
        assertThat(d.trades()).hasSize(2);
        assertThat(d.trades().get(0).quantity()).isEqualByComparingTo("10");
        assertThat(d.trades().get(1).side()).isEqualTo("sell");
        assertThat(d.watchlistChanges()).extracting(LlmDecision.WatchlistIntent::action).containsExactly("add", "remove");
    }

    @Test
    void messageOnlyWithEmptyOrMissingArrays() {
        LlmDecision d = parser.parse("{\"message\":\"Your portfolio looks fine.\",\"trades\":[],\"watchlist_changes\":[]}");
        assertThat(d.trades()).isEmpty();
        assertThat(d.watchlistChanges()).isEmpty();
        assertThat(parser.parse("{\"message\":\"hi\"}").trades()).isEmpty();
    }

    @Test
    void toleratesCodeFencesAndSurroundingText() {
        assertThat(parser.parse("```json\n{\"message\":\"ok\",\"trades\":[],\"watchlist_changes\":[]}\n```").message())
                .isEqualTo("ok");
        assertThat(parser.parse("Sure! {\"message\":\"ok2\",\"trades\":[]} hope that helps").message())
                .isEqualTo("ok2");
    }

    @Test
    void malformedJsonBecomesGracefulMessage() {
        for (String bad : new String[] {"not json at all", "{\"message\": \"unterminated", "", "   ", null, "[1,2,3]", "42"}) {
            LlmDecision d = parser.parse(bad);
            assertThat(d.message()).isEqualTo(LlmResponseParser.FALLBACK);
            assertThat(d.trades()).isEmpty();
            assertThat(d.watchlistChanges()).isEmpty();
        }
    }

    @Test
    void dropsMalformedActions() {
        LlmDecision d = parser.parse("""
                {"message":"x",
                 "trades":[{"ticker":"AAPL","side":"hold","quantity":1},{"ticker":"AAPL","side":"buy"},
                           {"side":"buy","quantity":1},{"ticker":"MSFT","side":"buy","quantity":"2.5"},
                           {"ticker":"MSFT","side":"buy","quantity":"lots"}],
                 "watchlist_changes":[{"ticker":"PYPL","action":"star"},{"action":"add"},"junk"]}
                """);
        assertThat(d.trades()).hasSize(1);
        assertThat(d.trades().get(0).quantity()).isEqualByComparingTo("2.5");
        assertThat(d.watchlistChanges()).isEmpty();
    }

    @Test
    void wrongTypesAreIgnored() {
        LlmDecision d = parser.parse("{\"message\":123,\"trades\":\"none\",\"watchlist_changes\":{}}");
        assertThat(d.message()).isEqualTo(LlmResponseParser.FALLBACK);
        assertThat(d.trades()).isEmpty();
    }

    @Test
    void missingMessageWithActionsGetsDefault() {
        LlmDecision d = parser.parse("{\"trades\":[{\"ticker\":\"AAPL\",\"side\":\"buy\",\"quantity\":1}]}");
        assertThat(d.message()).isEqualTo("Done.");
        assertThat(d.trades()).hasSize(1);
    }
}
