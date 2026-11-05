package com.finallyapp.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finallyapp.api.model.ActionStatus;
import com.finallyapp.api.model.ChatActions;
import com.finallyapp.api.model.ChatResponse;
import com.finallyapp.api.model.ExecutedTrade;
import com.finallyapp.api.model.KanbanCard;
import com.finallyapp.api.model.KanbanColumn;
import com.finallyapp.api.model.Portfolio;
import com.finallyapp.api.model.Trade;
import com.finallyapp.api.model.TradeSide;
import com.finallyapp.api.model.WatchlistItem;
import com.finallyapp.chat.ChatController;
import com.finallyapp.chat.ChatService;
import com.finallyapp.error.ApiException;
import com.finallyapp.kanban.KanbanController;
import com.finallyapp.kanban.KanbanService;
import com.finallyapp.market.MarketDataSource;
import com.finallyapp.portfolio.PortfolioController;
import com.finallyapp.portfolio.PortfolioService;
import com.finallyapp.system.HealthController;
import com.finallyapp.watchlist.WatchlistController;
import com.finallyapp.watchlist.WatchlistService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import com.finallyapp.persistence.UserProfileRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(controllers = {PortfolioController.class, WatchlistController.class, KanbanController.class,
        ChatController.class, HealthController.class})
class ControllerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 2, 10, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PortfolioService portfolio;
    @MockitoBean
    WatchlistService watchlist;
    @MockitoBean
    KanbanService kanban;
    @MockitoBean
    ChatService chat;
    @MockitoBean
    UserProfileRepository profiles;
    @MockitoBean
    MarketDataSource source;

    private static Portfolio emptyPortfolio() {
        return new Portfolio(new BigDecimal("10000.00"), BigDecimal.ZERO, new BigDecimal("10000.00"), BigDecimal.ZERO, List.of());
    }

    @Test
    void getPortfolio() throws Exception {
        when(portfolio.getPortfolio()).thenReturn(emptyPortfolio());
        mvc.perform(get("/api/portfolio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashBalance").value(10000.00))
                .andExpect(jsonPath("$.positions").isArray());
    }

    @Test
    void tradeOk() throws Exception {
        Trade t = new Trade(UUID.randomUUID(), "AAPL", TradeSide.BUY, BigDecimal.ONE, new BigDecimal("190.00"), NOW);
        when(portfolio.executeTrade(eq("AAPL"), eq(TradeSide.BUY), any()))
                .thenReturn(new PortfolioService.TradeOutcome(t, emptyPortfolio()));
        mvc.perform(post("/api/portfolio/trade").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticker\":\"AAPL\",\"quantity\":1,\"side\":\"buy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trade.side").value("buy"))
                .andExpect(jsonPath("$.trade.price").value(190.0))
                .andExpect(jsonPath("$.trade.executedAt").value("2026-11-02T10:00:00Z"))
                .andExpect(jsonPath("$.portfolio.totalValue").value(10000.0));
    }

    @Test
    void tradeBusinessErrorIs400WithCode() throws Exception {
        when(portfolio.executeTrade(any(), any(), any()))
                .thenThrow(ApiException.badRequest("INSUFFICIENT_CASH", "Insufficient cash"));
        mvc.perform(post("/api/portfolio/trade").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticker\":\"AAPL\",\"quantity\":1000,\"side\":\"buy\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_CASH"))
                .andExpect(jsonPath("$.message").value("Insufficient cash"));
    }

    @Test
    void tradeValidationErrors() throws Exception {
        mvc.perform(post("/api/portfolio/trade").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticker\":\"AAPL\",\"quantity\":0,\"side\":\"buy\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_QUANTITY"));
        mvc.perform(post("/api/portfolio/trade").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticker\":\"AAPL\",\"quantity\":1,\"side\":\"hold\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        mvc.perform(post("/api/portfolio/trade").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void history() throws Exception {
        when(portfolio.history()).thenReturn(List.of(new com.finallyapp.api.model.PortfolioSnapshot(new BigDecimal("10000.00"), NOW)));
        mvc.perform(get("/api/portfolio/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].totalValue").value(10000.0))
                .andExpect(jsonPath("$[0].recordedAt").value("2026-11-02T10:00:00Z"));
    }

    @Test
    void watchlistCrud() throws Exception {
        WatchlistItem item = new WatchlistItem("PYPL", NOW).price(new BigDecimal("60.00"));
        when(watchlist.list()).thenReturn(List.of(item, new WatchlistItem("NEW", NOW)));
        mvc.perform(get("/api/watchlist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("PYPL"))
                .andExpect(jsonPath("$[0].price").value(60.0))
                .andExpect(jsonPath("$[1].price").doesNotExist());

        when(watchlist.add("PYPL")).thenReturn(item);
        mvc.perform(post("/api/watchlist").contentType(MediaType.APPLICATION_JSON).content("{\"ticker\":\"PYPL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticker").value("PYPL"));

        when(watchlist.add("AAPL")).thenThrow(ApiException.conflict("TICKER_EXISTS", "dup"));
        mvc.perform(post("/api/watchlist").contentType(MediaType.APPLICATION_JSON).content("{\"ticker\":\"AAPL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TICKER_EXISTS"));

        mvc.perform(delete("/api/watchlist/PYPL")).andExpect(status().isNoContent());
        doThrow(ApiException.notFound("NOT_FOUND", "nope")).when(watchlist).remove("ZZZ");
        mvc.perform(delete("/api/watchlist/ZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void kanbanCrud() throws Exception {
        UUID id = UUID.randomUUID();
        KanbanCard card = new KanbanCard(id, "AAPL", KanbanColumn.SUPPORT, new BigDecimal("185"), 0, true,
                com.finallyapp.api.model.KanbanCardStatus.ACTIVE, NOW, NOW);
        when(kanban.create(any())).thenReturn(card);
        mvc.perform(post("/api/kanban").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticker\":\"AAPL\",\"column\":\"SUPPORT\",\"targetPrice\":185}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.column").value("SUPPORT"))
                .andExpect(jsonPath("$.armed").value(true));

        mvc.perform(post("/api/kanban").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticker\":\"AAPL\",\"column\":\"SUPPORT\",\"targetPrice\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PRICE"));
        mvc.perform(post("/api/kanban").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticker\":\"AAPL\",\"column\":\"MIDDLE\",\"targetPrice\":10}"))
                .andExpect(status().isBadRequest());

        when(kanban.update(eq(id), any())).thenReturn(card);
        mvc.perform(patch("/api/kanban/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"column\":\"RESISTANCE\"}"))
                .andExpect(status().isOk());
        UUID missing = UUID.randomUUID();
        when(kanban.update(eq(missing), any())).thenThrow(ApiException.notFound("NOT_FOUND", "no card"));
        mvc.perform(patch("/api/kanban/" + missing).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/kanban/not-a-uuid").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/kanban/" + id)).andExpect(status().isNoContent());
        when(kanban.list()).thenReturn(List.of(card));
        mvc.perform(get("/api/kanban")).andExpect(status().isOk()).andExpect(jsonPath("$[0].targetPrice").value(185));
    }

    @Test
    void chat() throws Exception {
        ExecutedTrade et = new ExecutedTrade("AAPL", TradeSide.BUY, BigDecimal.ONE, ActionStatus.EXECUTED).price(new BigDecimal("190.00"));
        when(chat.send("buy")).thenReturn(new ChatResponse(UUID.randomUUID(), "Bought",
                new ChatActions(List.of(et), List.of()), NOW));
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"buy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Bought"))
                .andExpect(jsonPath("$.actions.trades[0].status").value("executed"))
                .andExpect(jsonPath("$.actions.watchlistChanges").isArray());

        when(chat.send("fail")).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "LLM_ERROR", "down"));
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"fail\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("LLM_ERROR"));

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());

        when(chat.history()).thenReturn(List.of());
        mvc.perform(get("/api/chat/history")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }

    @Test
    void health() throws Exception {
        when(profiles.ping()).thenReturn(1);
        when(source.name()).thenReturn("simulator");
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.database").value("up"))
                .andExpect(jsonPath("$.marketData").value("simulator"));
    }

    @Test
    void unknownApiPathIs404Json() throws Exception {
        mvc.perform(get("/api/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}
