package com.finallyapp.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finallyapp.api.model.TickerSearchResult;
import com.finallyapp.error.ApiException;
import com.finallyapp.mail.McpToolClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TickerSearchServiceTest {

    /** As returned by mcp_massive's call_api on /v3/reference/tickers (header, quoted names, a pagination hint). */
    private static final String CSV = """
            ticker,name,market,locale,primary_exchange,type,active,currency_name,cik
            ENPHX,"Enphase Energy Something, Inc.",stocks,us,XNAS,CS,True,usd,1
            ENPH,"Enphase Energy, Inc.",stocks,us,XNAS,CS,True,usd,0001463101
            SOLR,"Solar ""Quoted"" Holdings",stocks,us,XNYS,CS,True,usd,2
            OLDX,Old Enphase,stocks,us,XNYS,CS,False,usd,3

            Next page: call again with cursor=abc
            """;

    private static McpSchema.CallToolResult result(String text) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(text)))
                .structuredContent(Map.of("result", text))
                .isError(false)
                .build();
    }

    private static TickerSearchService service(McpToolClient mcp, TickerNames names) {
        return new TickerSearchService(mcp, names, Duration.ofMinutes(10),
                Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void parsesQuotedCsvSkipsInactiveAndStopsAtTheBlankLine() {
        List<TickerSearchResult> rows = TickerSearchService.parse(CSV);
        assertThat(rows).extracting(TickerSearchResult::getTicker).containsExactly("ENPHX", "ENPH", "SOLR");
        assertThat(rows.get(1).getName()).isEqualTo("Enphase Energy, Inc.");
        assertThat(rows.get(1).getExchange()).isEqualTo("XNAS");
        assertThat(rows.get(1).getType()).isEqualTo("CS");
        assertThat(rows.get(2).getName()).isEqualTo("Solar \"Quoted\" Holdings");
        assertThat(TickerSearchService.parse("Warning [EMPTY]: API returned 0 records")).isEmpty();
        // a pagination hint glued to the rows (no blank line) is not taken for a stock
        String glued = "ticker,name,active\nFSLR,\"First Solar, Inc.\",True\n"
                + "Next page available. To fetch, call call_api with path=\"/v3/reference/tickers\" and params={\"cursor\": \"abc\"}\n";
        assertThat(TickerSearchService.parse(glued)).extracting(TickerSearchResult::getTicker).containsExactly("FSLR");
    }

    @Test
    void ranksExactTickerFirstThenPrefixesThenNames() {
        List<TickerSearchResult> found = List.of(
                new TickerSearchResult("XENPH", "Something Enph"),
                new TickerSearchResult("ENPHX", "Enphase Two"),
                new TickerSearchResult("ENPH", "Enphase Energy, Inc."),
                new TickerSearchResult("ENPH", "duplicate"));
        assertThat(TickerSearchService.rank("enph", found)).extracting(TickerSearchResult::getTicker)
                .containsExactly("ENPH", "ENPHX", "XENPH");
    }

    @Test
    void callsTheMcpToolCachesAndRemembersNames() {
        McpToolClient mcp = mock(McpToolClient.class);
        when(mcp.configured()).thenReturn(true);
        when(mcp.call(eq("call_api"), any())).thenReturn(result(CSV));
        TickerNames names = mock(TickerNames.class);
        TickerSearchService s = service(mcp, names);

        List<TickerSearchResult> first = s.search("  Enphase ");
        assertThat(first).extracting(TickerSearchResult::getTicker).first().isEqualTo("ENPH"); // name prefix tie: shorter ticker first
        assertThat(s.search("enphase")).isSameAs(first); // cached (case-insensitive): no second call
        verify(mcp, times(1)).call(eq("call_api"), any());
        verify(names).remember("ENPH", "Enphase Energy, Inc.");
        assertThat(s.search("   ")).isEmpty();
    }

    @Test
    void unavailableWhenNotConfiguredFailingOrRateLimited() {
        McpToolClient off = mock(McpToolClient.class);
        assertThatThrownBy(() -> service(off, mock(TickerNames.class)).search("apple"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        McpToolClient down = mock(McpToolClient.class);
        when(down.configured()).thenReturn(true);
        when(down.call(any(), any())).thenThrow(new McpToolClient.ToolException("unreachable"));
        assertThatThrownBy(() -> service(down, mock(TickerNames.class)).search("apple"))
                .isInstanceOf(ApiException.class).hasMessageContaining("unavailable");

        McpToolClient limited = mock(McpToolClient.class);
        when(limited.configured()).thenReturn(true);
        when(limited.call(any(), any())).thenReturn(result("Error [HTTP_429]: too many requests"));
        assertThatThrownBy(() -> service(limited, mock(TickerNames.class)).search("apple"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Too many searches");
    }

    @Test
    void rejectsOverlongQueries() {
        McpToolClient mcp = mock(McpToolClient.class);
        assertThatThrownBy(() -> service(mcp, mock(TickerNames.class)).search("x".repeat(51)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
