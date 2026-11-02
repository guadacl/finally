package com.finallyapp.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finallyapp.config.FinallyProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LlmClientsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmResponseParser parser = new LlmResponseParser(mapper);

    private static FinallyProperties.Llm cfg(String key) {
        return new FinallyProperties.Llm(false, key, "https://openrouter.test/api/v1", "openai/gpt-oss-120b", "cerebras",
                Duration.ofSeconds(5), 20);
    }

    private static List<LlmClient.Message> prompt(String user) {
        return List.of(new LlmClient.Message("system", "sys"), new LlmClient.Message("user", user));
    }

    @Test
    void openRouterSendsStructuredOutputRequest() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo("https://openrouter.test/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer k123"))
                .andExpect(jsonPath("$.model").value("openai/gpt-oss-120b"))
                .andExpect(jsonPath("$.provider.order[0]").value("cerebras"))
                .andExpect(jsonPath("$.reasoning.effort").value("low"))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.response_format.json_schema.schema.required[2]").value("watchlist_changes"))
                .andExpect(jsonPath("$.messages[1].content").value("hello"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant",
                          "content":"{\\"message\\":\\"hi\\",\\"trades\\":[],\\"watchlist_changes\\":[]}"}}]}
                        """, MediaType.APPLICATION_JSON));
        String content = new OpenRouterLlmClient(b, cfg("k123"), mapper).complete(prompt("hello"));
        server.verify();
        assertThat(parser.parse(content).message()).isEqualTo("hi");
    }

    @Test
    void openRouterErrorsBecomeLlmException() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo("https://openrouter.test/api/v1/chat/completions")).andRespond(withServerError());
        assertThatThrownBy(() -> new OpenRouterLlmClient(b, cfg("k"), mapper).complete(prompt("x")))
                .isInstanceOf(LlmClient.LlmException.class);

        RestClient.Builder b2 = RestClient.builder();
        MockRestServiceServer s2 = MockRestServiceServer.bindTo(b2).build();
        s2.expect(requestTo("https://openrouter.test/api/v1/chat/completions"))
                .andRespond(withSuccess("{\"error\":{\"message\":\"rate limited\"}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> new OpenRouterLlmClient(b2, cfg("k"), mapper).complete(prompt("x")))
                .hasMessageContaining("rate limited");
    }

    @Test
    void openRouterWithoutKeyFailsFastWithoutNetwork() {
        assertThatThrownBy(() -> new OpenRouterLlmClient(RestClient.builder(), cfg(""), mapper).complete(prompt("x")))
                .isInstanceOf(LlmClient.LlmException.class)
                .hasMessageContaining("OPENROUTER_API_KEY");
    }

    @Test
    void mockIsDeterministic() {
        MockLlmClient mock = new MockLlmClient(mapper);
        LlmDecision buy = parser.parse(mock.complete(prompt("please buy something")));
        assertThat(buy.trades()).singleElement().satisfies(t -> {
            assertThat(t.ticker()).isEqualTo("AAPL");
            assertThat(t.side()).isEqualTo("buy");
            assertThat(t.quantity()).isEqualByComparingTo("1");
        });
        assertThat(mock.complete(prompt("please buy something"))).isEqualTo(mock.complete(prompt("please buy something")));

        LlmDecision explicit = parser.parse(mock.complete(prompt("Buy 5 shares of tsla")));
        assertThat(explicit.trades()).singleElement().satisfies(t -> {
            assertThat(t.ticker()).isEqualTo("TSLA");
            assertThat(t.quantity()).isEqualByComparingTo("5");
        });

        LlmDecision sell = parser.parse(mock.complete(prompt("sell 2 MSFT")));
        assertThat(sell.trades().get(0).side()).isEqualTo("sell");
        assertThat(sell.trades().get(0).ticker()).isEqualTo("MSFT");

        LlmDecision watch = parser.parse(mock.complete(prompt("add PYPL to my watchlist and remove NFLX")));
        assertThat(watch.trades()).isEmpty();
        assertThat(watch.watchlistChanges()).extracting(LlmDecision.WatchlistIntent::ticker).containsExactly("PYPL", "NFLX");
        assertThat(watch.watchlistChanges()).extracting(LlmDecision.WatchlistIntent::action).containsExactly("add", "remove");

        LlmDecision chat = parser.parse(mock.complete(prompt("how is my portfolio?")));
        assertThat(chat.trades()).isEmpty();
        assertThat(chat.watchlistChanges()).isEmpty();
        assertThat(chat.message()).isNotBlank();
    }
}
