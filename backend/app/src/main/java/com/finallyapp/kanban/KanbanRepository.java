package com.finallyapp.kanban;

import com.finallyapp.contracts.KanbanColumn;
import com.finallyapp.persistence.KanbanCardEntity;
import com.finallyapp.persistence.KanbanCardRepository;
import com.finallyapp.security.CurrentUser;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kanban cards of the current user (plus the all-users view the alert engine needs), on top of the Spring Data JPA
 * {@link KanbanCardRepository}. Exposes immutable {@link CardRow}s so callers never hold managed entities.
 */
@Repository
public class KanbanRepository {

    public record CardRow(UUID id, String ticker, KanbanColumn column, BigDecimal targetPrice, int sortOrder,
                          boolean armed, Long processInstanceKey, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                          String userId, String comment) {
    }

    private final KanbanCardRepository cards;

    public KanbanRepository(KanbanCardRepository cards) {
        this.cards = cards;
    }

    public List<CardRow> findAll() {
        return cards.findByUserIdOrderByColumnDescSortOrderAscCreatedAtAsc(CurrentUser.id()).stream()
                .map(KanbanRepository::row).toList();
    }

    /** Every user's cards (for the alert engine, which watches prices for all users). */
    public List<CardRow> findAllUsers() {
        return cards.findAllByOrderByCreatedAtAsc().stream().map(KanbanRepository::row).toList();
    }

    public Optional<CardRow> find(UUID id) {
        return cards.findByIdAndUserId(id, CurrentUser.id()).map(KanbanRepository::row);
    }

    /** New armed card at the end of its column. */
    public CardRow insert(String ticker, KanbanColumn column, BigDecimal target, String comment) {
        String user = CurrentUser.id();
        int sortOrder = cards.nextSortOrder(user, column.name());
        return row(cards.save(new KanbanCardEntity(user, ticker, column.name(), target, sortOrder, comment)));
    }

    public int nextSortOrder(KanbanColumn column) {
        return cards.nextSortOrder(CurrentUser.id(), column.name());
    }

    /**
     * Edits a card under a row lock, so it cannot interleave with the alert engine's {@link #markNotified}:
     * {@code rearm} sets it ACTIVE; otherwise its armed state is left exactly as stored (a comment edit or a reorder
     * never re-activates a NOTIFIED card, nor overwrites a NOTIFIED set meanwhile).
     */
    @Transactional
    public CardRow update(UUID id, KanbanColumn column, BigDecimal target, int sortOrder, boolean rearm, String comment) {
        KanbanCardEntity c = cards.findForUpdateByIdAndUserId(id, CurrentUser.id()).orElseThrow();
        c.setColumn(column.name());
        c.setTargetPrice(target);
        c.setSortOrder(sortOrder);
        if (rearm) {
            c.setArmed(true);
        }
        c.setComment(comment);
        return row(cards.saveAndFlush(c));
    }

    public void setProcessInstanceKey(UUID id, Long key) {
        cards.updateProcessInstanceKey(id, key);
    }

    /**
     * Alert engine: marks any user's card NOTIFIED if it is still ACTIVE with this column and target. False when the
     * card was deleted, edited or already NOTIFIED meanwhile; then the hit must not be notified.
     */
    public boolean markNotified(UUID id, KanbanColumn column, BigDecimal target) {
        return cards.markNotified(id, column.name(), target) > 0;
    }

    public boolean delete(UUID id) {
        return cards.deleteByIdAndUserId(id, CurrentUser.id()) > 0;
    }

    private static CardRow row(KanbanCardEntity c) {
        return new CardRow(c.getId(), c.getTicker(), KanbanColumn.valueOf(c.getColumn()), c.getTargetPrice(),
                c.getSortOrder(), c.isArmed(), c.getProcessInstanceKey(), c.getCreatedAt(), c.getUpdatedAt(),
                c.getUserId(), c.getComment());
    }
}
