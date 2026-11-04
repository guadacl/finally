package com.finallyapp.kanban;

import com.finallyapp.contracts.KanbanCardRef;
import com.finallyapp.contracts.KanbanColumn;
import com.finallyapp.contracts.KanbanWorkflow;
import com.finallyapp.kanban.AlertRules.Decision;
import com.finallyapp.market.PriceCache;
import com.finallyapp.market.PriceListener;
import com.finallyapp.market.PriceTick;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Evaluates kanban price alerts on every price tick (CONTRACTS.md §5), from an in-memory copy of the cards.
 * The DB stays the source of truth: {@code armed} changes are persisted and {@link KanbanWorkflow#targetPriceHit}
 * is called on a background dispatcher so the market data thread is never blocked.
 */
@Component
public class AlertEngine implements PriceListener {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    /** Mutable in-memory card state; guarded by its own monitor. */
    static final class CardState {
        final UUID id;
        final String userId;
        final String ticker;
        KanbanColumn column;
        BigDecimal target;
        boolean armed;

        CardState(UUID id, String userId, String ticker, KanbanColumn column, BigDecimal target, boolean armed) {
            this.id = id;
            this.userId = userId;
            this.ticker = ticker;
            this.column = column;
            this.target = target;
            this.armed = armed;
        }

        synchronized KanbanCardRef ref() {
            return new KanbanCardRef(id, ticker, column, target, userId);
        }
    }

    private final ConcurrentHashMap<UUID, CardState> cards = new ConcurrentHashMap<>();
    private final PriceCache cache;
    private final KanbanRepository repo;
    private final KanbanWorkflow workflow;
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "alert-dispatch");
        t.setDaemon(true);
        return t;
    });

    public AlertEngine(PriceCache cache, KanbanRepository repo, KanbanWorkflow workflow) {
        this.cache = cache;
        this.repo = repo;
        this.workflow = workflow;
    }

    @PostConstruct
    void register() {
        cache.addListener(this);
    }

    @PreDestroy
    void shutdown() {
        cache.removeListener(this);
        dispatcher.shutdown();
    }

    /** Loads every user's cards from the DB before the market data source starts. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(50)
    public void load() {
        try {
            List<KanbanRepository.CardRow> rows = repo.findAllUsers();
            cards.clear();
            rows.forEach(r -> put(r.id(), r.userId(), r.ticker(), r.column(), r.targetPrice(), r.armed()));
            log.info("Alert engine loaded {} kanban cards", rows.size());
        } catch (RuntimeException e) {
            log.error("Could not load kanban cards: {}", e.toString());
        }
    }

    /** Adds or replaces a card's in-memory state (after create / edit / move). */
    public void put(UUID id, String userId, String ticker, KanbanColumn column, BigDecimal target, boolean armed) {
        cards.put(id, new CardState(id, userId, ticker, column, target, armed));
    }

    public void remove(UUID id) {
        cards.remove(id);
    }

    Collection<CardState> cards() {
        return cards.values();
    }

    @Override
    public void onPrice(PriceTick tick) {
        for (CardState card : cards.values()) {
            if (!card.ticker.equals(tick.ticker())) {
                continue;
            }
            evaluate(card, tick.price());
        }
    }

    /** Evaluates one card against a price, updating in-memory state and dispatching side effects. */
    Decision evaluate(CardState card, BigDecimal price) {
        Decision d;
        KanbanCardRef ref;
        synchronized (card) {
            d = AlertRules.evaluate(card.column, card.target, card.armed, price);
            if (d == Decision.NONE) {
                return d;
            }
            card.armed = false; // NOTIFIED: no further alerts until the user edits or moves the card
            ref = new KanbanCardRef(card.id, card.ticker, card.column, card.target, card.userId);
        }
        dispatcher.execute(() -> {
            if (cards.get(card.id) != card) {
                return; // deleted or edited meanwhile (the edit already persisted its own state)
            }
            try {
                boolean current;
                synchronized (card) {
                    current = card.armed;
                }
                repo.setArmed(card.id, current);
            } catch (RuntimeException e) {
                log.warn("Could not persist armed state for card {}: {}", card.id, e.toString());
            }
            if (d == Decision.FIRE) {
                log.info("Target hit: {} {} target {} price {}", ref.ticker(), ref.column(), ref.targetPrice(), price);
                try {
                    workflow.targetPriceHit(ref, price);
                } catch (RuntimeException e) {
                    log.error("KanbanWorkflow.targetPriceHit failed: {}", e.toString());
                }
            }
        });
        return d;
    }
}
