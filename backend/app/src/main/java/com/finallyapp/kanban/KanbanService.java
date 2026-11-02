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
import com.finallyapp.market.PriceCache;
import com.finallyapp.market.TickerTracker;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Kanban cards (support / resistance price targets) backed by Camunda {@code kanban-card} processes. */
@Service
public class KanbanService {

    private static final Logger log = LoggerFactory.getLogger(KanbanService.class);

    private final KanbanRepository repo;
    private final KanbanWorkflow workflow;
    private final AlertEngine alerts;
    private final TickerTracker tracker;
    private final PriceCache cache;

    public KanbanService(KanbanRepository repo, KanbanWorkflow workflow, AlertEngine alerts,
                         TickerTracker tracker, PriceCache cache) {
        this.repo = repo;
        this.workflow = workflow;
        this.alerts = alerts;
        this.tracker = tracker;
        this.cache = cache;
    }

    public List<KanbanCard> list() {
        return repo.findAll().stream().map(this::toDto).toList();
    }

    public KanbanCard create(KanbanCardCreate req) {
        String ticker = Tickers.normalize(req.getTicker());
        if (req.getColumn() == null) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "column must be SUPPORT or RESISTANCE");
        }
        KanbanColumn column = KanbanColumn.valueOf(req.getColumn().getValue());
        BigDecimal target = validatePrice(req.getTargetPrice());

        CardRow row = repo.insert(ticker, column, target);
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
                    key, row.createdAt(), row.updatedAt());
        }
        alerts.put(row.id(), row.ticker(), row.column(), row.targetPrice(), row.armed());
        return toDto(row);
    }

    /** Moves (column / sortOrder) and/or edits the target. Column or target change re-arms and notifies Camunda. */
    public KanbanCard update(UUID id, KanbanCardUpdate req) {
        CardRow cur = repo.find(id).orElseThrow(() -> notFound(id));
        KanbanColumn column = req.getColumn() == null ? cur.column() : KanbanColumn.valueOf(req.getColumn().getValue());
        BigDecimal target = req.getTargetPrice() == null ? cur.targetPrice() : validatePrice(req.getTargetPrice());
        if (req.getSortOrder() != null && req.getSortOrder() < 0) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "sortOrder must be >= 0");
        }
        boolean columnChanged = column != cur.column();
        boolean targetChanged = target.compareTo(cur.targetPrice()) != 0;

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
        CardRow row = repo.update(id, column, target, sortOrder, armed);

        if (columnChanged || targetChanged) {
            alerts.put(row.id(), row.ticker(), row.column(), row.targetPrice(), row.armed());
            try {
                workflow.cardMoved(ref(row));
            } catch (RuntimeException e) {
                log.error("KanbanWorkflow.cardMoved failed for {}: {}", id, e.toString());
            }
        }
        return toDto(row);
    }

    public void delete(UUID id) {
        CardRow cur = repo.find(id).orElseThrow(() -> notFound(id));
        repo.delete(id);
        alerts.remove(id);
        try {
            workflow.cardDeleted(id);
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

    private static ApiException notFound(UUID id) {
        return ApiException.notFound(ErrorCodes.NOT_FOUND, "Kanban card " + id + " not found");
    }

    static KanbanCardRef ref(CardRow r) {
        return new KanbanCardRef(r.id(), r.ticker(), r.column(), r.targetPrice());
    }

    private KanbanCard toDto(CardRow r) {
        KanbanCard c = new KanbanCard(r.id(), r.ticker(),
                com.finallyapp.api.model.KanbanColumn.fromValue(r.column().name()),
                r.targetPrice().stripTrailingZeros(), r.sortOrder(), r.armed(), r.createdAt(), r.updatedAt());
        c.setCurrentPrice(cache.price(r.ticker()));
        return c;
    }
}
