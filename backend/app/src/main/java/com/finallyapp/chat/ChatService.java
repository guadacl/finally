package com.finallyapp.chat;

import com.finallyapp.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finallyapp.api.model.ActionStatus;
import com.finallyapp.api.model.ChatActions;
import com.finallyapp.api.model.ChatMessage;
import com.finallyapp.api.model.ChatResponse;
import com.finallyapp.api.model.ChatRole;
import com.finallyapp.api.model.ExecutedTrade;
import com.finallyapp.api.model.ExecutedWatchlistChange;
import com.finallyapp.api.model.Portfolio;
import com.finallyapp.api.model.Position;
import com.finallyapp.api.model.TradeSide;
import com.finallyapp.api.model.WatchlistAction;
import com.finallyapp.api.model.WatchlistItem;
import com.finallyapp.config.FinallyProperties;
import com.finallyapp.error.ApiException;
import com.finallyapp.error.ErrorCodes;
import com.finallyapp.portfolio.PortfolioService;
import com.finallyapp.persistence.ChatMessageEntity;
import com.finallyapp.persistence.ChatMessageRepository;
import com.finallyapp.watchlist.WatchlistService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** AI chat: prompt with portfolio context + history, call the LLM, auto-execute its actions, persist. */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final String SYSTEM_PROMPT = """
            You are FinAlly, an AI trading assistant inside a simulated trading workstation (fake money, zero stakes).
            - Analyze the user's portfolio composition, risk concentration and P&L.
            - Suggest trades with brief reasoning. Execute trades when the user asks or agrees, by listing them in "trades".
            - Manage the watchlist proactively via "watchlist_changes" (action "add" or "remove").
            - Trades are market orders filled instantly at the current price. Buys need enough cash; sells need enough shares.
            - Be concise and data-driven. Use the numbers in the portfolio context.
            - Always respond with valid JSON matching the schema: {"message": string, "trades": [{"ticker","side","quantity"}],
              "watchlist_changes": [{"ticker","action"}]}. Use empty arrays when there is nothing to do.
            """;

    private final LlmClient llm;
    private final LlmResponseParser parser;
    private final PortfolioService portfolio;
    private final WatchlistService watchlist;
    private final ChatMessageRepository messages;
    private final ObjectMapper mapper;
    private final int historyLimit;

    public ChatService(LlmClient llm, LlmResponseParser parser, PortfolioService portfolio, WatchlistService watchlist,
                       ChatMessageRepository messages, ObjectMapper mapper, FinallyProperties props) {
        this.llm = llm;
        this.parser = parser;
        this.portfolio = portfolio;
        this.watchlist = watchlist;
        this.messages = messages;
        this.mapper = mapper;
        this.historyLimit = props.llm().historyLimit();
    }

    public ChatResponse send(String userMessage) {
        String text = userMessage == null ? "" : userMessage.trim();
        if (text.isEmpty() || text.length() > 4000) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "message must be 1-4000 characters");
        }
        List<LlmClient.Message> prompt = new ArrayList<>();
        prompt.add(new LlmClient.Message("system", SYSTEM_PROMPT));
        prompt.add(new LlmClient.Message("system", portfolioContext()));
        for (ChatMessage m : recent(historyLimit)) {
            prompt.add(new LlmClient.Message(m.getRole().getValue(), m.getContent()));
        }
        prompt.add(new LlmClient.Message("user", text));

        String raw;
        try {
            raw = llm.complete(prompt);
        } catch (LlmClient.LlmException e) {
            log.error("LLM call failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.LLM_ERROR, e.getMessage());
        }
        LlmDecision decision = parser.parse(raw);
        ChatActions actions = execute(decision);
        String reply = decision.message() + failureNote(actions);

        insert("user", text, null);
        Saved saved = insert("assistant", reply, actions);
        return new ChatResponse(saved.id(), reply, actions, saved.createdAt());
    }

    public List<ChatMessage> history() {
        return recent(50);
    }

    /** Executes the LLM's actions through the same services as manual actions, collecting per-action status. */
    ChatActions execute(LlmDecision d) {
        List<ExecutedTrade> trades = new ArrayList<>();
        for (LlmDecision.TradeIntent t : d.trades()) {
            TradeSide side = TradeSide.fromValue(t.side());
            ExecutedTrade et = new ExecutedTrade(t.ticker().trim().toUpperCase(), side, t.quantity(), ActionStatus.EXECUTED);
            try {
                PortfolioService.TradeOutcome o = portfolio.executeTrade(t.ticker(), side, t.quantity());
                et.ticker(o.trade().getTicker()).quantity(o.trade().getQuantity()).price(o.trade().getPrice());
            } catch (ApiException e) {
                et.status(ActionStatus.FAILED).error(e.getMessage());
            } catch (RuntimeException e) {
                log.error("Chat trade failed", e);
                et.status(ActionStatus.FAILED).error("Trade failed: unexpected error");
            }
            trades.add(et);
        }
        List<ExecutedWatchlistChange> changes = new ArrayList<>();
        for (LlmDecision.WatchlistIntent w : d.watchlistChanges()) {
            WatchlistAction action = WatchlistAction.fromValue(w.action());
            ExecutedWatchlistChange ec = new ExecutedWatchlistChange(w.ticker().trim().toUpperCase(), action, ActionStatus.EXECUTED);
            try {
                if (action == WatchlistAction.ADD) {
                    ec.ticker(watchlist.add(w.ticker()).getTicker());
                } else {
                    watchlist.remove(w.ticker());
                }
            } catch (ApiException e) {
                ec.status(ActionStatus.FAILED).error(e.getMessage());
            } catch (RuntimeException e) {
                log.error("Chat watchlist change failed", e);
                ec.status(ActionStatus.FAILED).error("Watchlist change failed: unexpected error");
            }
            changes.add(ec);
        }
        return new ChatActions(trades, changes);
    }

    static String failureNote(ChatActions a) {
        List<String> errs = new ArrayList<>();
        a.getTrades().stream().filter(t -> t.getStatus() == ActionStatus.FAILED)
                .forEach(t -> errs.add(t.getSide().getValue() + " " + t.getQuantity().stripTrailingZeros().toPlainString()
                        + " " + t.getTicker() + " failed: " + t.getError()));
        a.getWatchlistChanges().stream().filter(c -> c.getStatus() == ActionStatus.FAILED)
                .forEach(c -> errs.add(c.getAction().getValue() + " " + c.getTicker() + " failed: " + c.getError()));
        return errs.isEmpty() ? "" : "\n\nNote: " + String.join("; ", errs);
    }

    String portfolioContext() {
        StringBuilder sb = new StringBuilder("Current portfolio context (live):\n");
        try {
            Portfolio p = portfolio.getPortfolio();
            sb.append("Cash: $").append(p.getCashBalance()).append('\n')
                    .append("Positions value: $").append(p.getPositionsValue()).append('\n')
                    .append("Total value: $").append(p.getTotalValue()).append('\n')
                    .append("Unrealized P&L: $").append(p.getUnrealizedPnl()).append('\n')
                    .append("Positions:");
            if (p.getPositions().isEmpty()) {
                sb.append(" none");
            }
            sb.append('\n');
            for (Position pos : p.getPositions()) {
                sb.append("- ").append(pos.getTicker()).append(": qty ").append(pos.getQuantity().toPlainString())
                        .append(", avg cost $").append(pos.getAvgCost())
                        .append(", price $").append(pos.getCurrentPrice())
                        .append(", value $").append(pos.getMarketValue())
                        .append(", P&L $").append(pos.getUnrealizedPnl())
                        .append(" (").append(pos.getUnrealizedPnlPercent()).append("%)\n");
            }
            sb.append("Watchlist:\n");
            for (WatchlistItem w : watchlist.list()) {
                sb.append("- ").append(w.getTicker()).append(": ")
                        .append(w.getPrice() == null ? "no price yet" : "$" + w.getPrice())
                        .append(w.getChangePercent() == null ? "" : " (" + w.getChangePercent() + "% since session open)")
                        .append('\n');
            }
        } catch (RuntimeException e) {
            log.warn("Could not build portfolio context: {}", e.toString());
            sb.append("(unavailable)\n");
        }
        return sb.toString();
    }

    private record Saved(UUID id, OffsetDateTime createdAt) {
    }

    private Saved insert(String role, String content, ChatActions actions) {
        String json = null;
        if (actions != null) {
            try {
                json = mapper.writeValueAsString(actions);
            } catch (Exception e) {
                log.warn("Could not serialize chat actions: {}", e.toString());
            }
        }
        ChatMessageEntity saved = messages.save(new ChatMessageEntity(CurrentUser.id(), role, content, json));
        return new Saved(saved.getId(), saved.getCreatedAt());
    }

    /** Most recent {@code limit} messages, oldest first ("user" before "assistant" at the same instant). */
    List<ChatMessage> recent(int limit) {
        return messages.findByUserIdOrderByCreatedAtDescRoleDesc(CurrentUser.id(), PageRequest.of(0, limit)).stream()
                .sorted(Comparator.comparing(ChatMessageEntity::getCreatedAt)
                        .thenComparing(ChatMessageEntity::getRole, Comparator.reverseOrder()))
                .map(this::toMessage)
                .toList();
    }

    private ChatMessage toMessage(ChatMessageEntity e) {
        ChatMessage m = new ChatMessage(e.getId(), ChatRole.fromValue(e.getRole()), e.getContent(), e.getCreatedAt());
        if (e.getActions() != null) {
            try {
                m.setActions(mapper.readValue(e.getActions(), ChatActions.class));
            } catch (Exception ex) {
                log.debug("Unreadable actions JSON on message {}", m.getId());
            }
        }
        return m;
    }
}
