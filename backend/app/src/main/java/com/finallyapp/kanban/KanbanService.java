package com.finallyapp.kanban;

import com.finallyapp.api.model.KanbanCard;
import com.finallyapp.api.model.KanbanCardCreate;
import com.finallyapp.api.model.KanbanCardUpdate;
import com.finallyapp.config.Tickers;
import com.finallyapp.contracts.KanbanCardRef;
import com.finallyapp.contracts.KanbanColumn;
import com.finallyapp.contracts.KanbanWorkflow;
import com.finallyapp.error.ApiException;
import com.finallyapp.error.ErrorCodes;
import com.finallyapp.kanban.KanbanRepository.CardRow;
import com.finallyapp.market.MarketStats;
import com.finallyapp.market.TickerNames;
import com.finallyapp.market.TickerTracker;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Kanban cards (support / resistance price targets) backed by Camunda {@code kanban-card} processes. */
@Service
public class KanbanService {

    private static final Logger log = LoggerFactory.getLogger(KanbanService.class);
    static final int MAX_COMMENT = 500;

    private final KanbanRepository repo;
    private final KanbanWorkflow workflow;
    private final AlertEngine alerts;
    private final TickerTracker tracker;
    private final MarketStats stats;
    private final TickerNames names;

    public KanbanService(KanbanRepository repo, KanbanWorkflow workflow, AlertEngine alerts,
                         TickerTracker tracker, MarketStats stats, TickerNames names) {
        this.names = names;
        this.repo = repo;
        this.workflow = workflow;
        this.alerts = alerts;
        this.tracker = tracker;
        this.stats = stats;
    }

    public List<KanbanCard> list() {
        return repo.findAll().stream().map(this::toDto).toList();
    }

    /** A created card ({@code created}) or the existing card of that ticker and column, updated. */
    public record Upserted(KanbanCard card, boolean created) {
    }

    /**
     * Adds a card. A ticker appears at most once per column: when the column already has a card for the ticker,
     * that card is updated instead (new target, re-armed; comment replaced only when one is given).
     */
    public Upserted create(KanbanCardCreate req) {
        String ticker = Tickers.normalize(req.getTicker());
        if (req.getColumn() == null) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "column must be SUPPORT or RESISTANCE");
        }
        KanbanColumn column = KanbanColumn.valueOf(req.getColumn().getValue());
        BigDecimal target = validatePrice(req.getTargetPrice());
        String comment = comment(req.getComment());

        Optional<CardRow> existing = repo.findByTickerAndColumn(ticker, column);
        if (existing.isPresent()) {
            return new Upserted(updateExisting(existing.get(), target, comment), false);
        }
        CardRow row;
        try {
            row = repo.insert(ticker, column, target, comment);
        } catch (DataIntegrityViolationException e) {
            // added concurrently (unique user + ticker + column): update that card instead
            CardRow other = repo.findByTickerAndColumn(ticker, column).orElseThrow(() -> e);
            return new Upserted(updateExisting(other, target, comment), false);
        }
        tracker.track(ticker);
        Long key = null;
        try {
            key = workflow.cardCreated(ref(row));
        } catch (RuntimeException e) {
            log.error("KanbanWorkflow.cardCreated failed for {}: {}", row.id(), e.toString());
        }
        if (key != null) {
            repo.setProcessInstanceKey(row.id(), key);
            row = new CardRow(row.id(), row.ticker(), row.column(), row.targetPrice(), row.sortOrder(), row.armed(),
                    key, row.createdAt(), row.updatedAt(), row.userId(), row.comment());
        }
        alerts.put(row.id(), row.userId(), row.ticker(), row.column(), row.targetPrice(), row.armed());
        return new Upserted(toDto(row), true);
    }

    private KanbanCard updateExisting(CardRow cur, BigDecimal target, String comment) {
        KanbanCardUpdate upd = new KanbanCardUpdate().targetPrice(target);
        if (comment != null) {
            upd.comment(comment);
        }
        return update(cur.id(), upd);
    }

    /**
     * Moves (column / sortOrder) and/or edits the target and comment. Column or target change re-arms and notifies
     * Camunda; a comment change does neither. {@code comment} absent = unchanged, blank = cleared.
     */
    public KanbanCard update(UUID id, KanbanCardUpdate req) {
        CardRow cur = repo.find(id).orElseThrow(() -> notFound(id));
        KanbanColumn column = req.getColumn() == null ? cur.column() : KanbanColumn.valueOf(req.getColumn().getValue());
        BigDecimal target = req.getTargetPrice() == null ? cur.targetPrice() : validatePrice(req.getTargetPrice());
        String comment = req.getComment() == null ? cur.comment() : comment(req.getComment());
        if (req.getSortOrder() != null && req.getSortOrder() < 0) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "sortOrder must be >= 0");
        }
        boolean columnChanged = column != cur.column();
        boolean targetChanged = target.compareTo(cur.targetPrice()) != 0;
        if (columnChanged && repo.findByTickerAndColumn(cur.ticker(), column).isPresent()) {
            throw ApiException.conflict(ErrorCodes.TICKER_EXISTS,
                    cur.ticker() + " is already in the " + label(column) + " table");
        }

        int sortOrder;
        if (req.getSortOrder() != null) {
            // Accepted as given: after a drag the frontend PATCHes every card whose sortOrder changed.
            sortOrder = req.getSortOrder();
        } else if (columnChanged) {
            sortOrder = repo.nextSortOrder(column);
        } else {
            sortOrder = cur.sortOrder();
        }
        boolean armed = (columnChanged || targetChanged) || cur.armed();
        CardRow row = repo.update(id, column, target, sortOrder, armed, comment);

        if (columnChanged || targetChanged) {
            alerts.put(row.id(), row.userId(), row.ticker(), row.column(), row.targetPrice(), row.armed());
            try {
                workflow.cardMoved(ref(row));
            } catch (RuntimeException e) {
                log.error("KanbanWorkflow.cardMoved failed for {}: {}", id, e.toString());
            }
        }
        return toDto(row);
    }

    public void delete(UUID id) {
        // read first: the workflow needs the card's last ticker / column / target / owner for the deletion email
        CardRow cur = repo.find(id).orElseThrow(() -> notFound(id));
        repo.delete(id);
        alerts.remove(id);
        try {
            workflow.cardDeleted(ref(cur), cur.processInstanceKey());
        } catch (RuntimeException e) {
            log.error("KanbanWorkflow.cardDeleted failed for {}: {}", id, e.toString());
        }
        try {
            tracker.untrackIfUnused(cur.ticker());
        } catch (RuntimeException e) {
            log.debug("untrack failed: {}", e.toString());
        }
    }

    private static BigDecimal validatePrice(BigDecimal p) {
        if (p == null || p.signum() <= 0) {
            throw ApiException.badRequest(ErrorCodes.INVALID_PRICE, "targetPrice must be greater than 0");
        }
        if (p.compareTo(new BigDecimal("100000000000000")) >= 0) {
            throw ApiException.badRequest(ErrorCodes.INVALID_PRICE, "targetPrice is too large");
        }
        BigDecimal scaled = p.setScale(4, RoundingMode.HALF_UP);
        if (scaled.signum() <= 0) {
            throw ApiException.badRequest(ErrorCodes.INVALID_PRICE, "targetPrice must be greater than 0");
        }
        return scaled;
    }

    /** Trimmed comment, null when blank; 400 when longer than {@link #MAX_COMMENT}. */
    static String comment(String raw) {
        if (raw == null) {
            return null;
        }
        String c = raw.trim();
        if (c.isEmpty()) {
            return null;
        }
        if (c.length() > MAX_COMMENT) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                    "The comment can be at most " + MAX_COMMENT + " characters");
        }
        return c;
    }

    private static String label(KanbanColumn column) {
        return column == KanbanColumn.SUPPORT ? "Support" : "Resistance";
    }

    private static ApiException notFound(UUID id) {
        return ApiException.notFound(ErrorCodes.NOT_FOUND, "Kanban card " + id + " not found");
    }

    static KanbanCardRef ref(CardRow r) {
        return new KanbanCardRef(r.id(), r.ticker(), r.column(), r.targetPrice(), r.userId());
    }

    private KanbanCard toDto(CardRow r) {
        KanbanCard c = new KanbanCard(r.id(), r.ticker(),
                com.finallyapp.api.model.KanbanColumn.fromValue(r.column().name()),
                r.targetPrice().stripTrailingZeros(), r.sortOrder(), r.armed(), r.createdAt(), r.updatedAt());
        MarketStats.Stats s = stats.of(r.ticker());
        c.setCurrentPrice(s.price());
        c.setComment(r.comment());
        c.setStatus(com.finallyapp.api.model.KanbanCardStatus.fromValue(AlertRules.status(r.armed()).name()));
        c.setDayHigh(s.dayHigh());
        c.setDayLow(s.dayLow());
        c.setLastSessionVolume(s.lastSessionVolume());
        c.setLastSessionDate(s.lastSessionDate());
        c.setRsi(s.rsi());
        c.setName(names.get(r.ticker()).orElse(null));
        return c;
    }
}
