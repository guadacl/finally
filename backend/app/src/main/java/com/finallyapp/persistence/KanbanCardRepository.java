package com.finallyapp.persistence;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface KanbanCardRepository extends JpaRepository<KanbanCardEntity, UUID> {

    /** A user's board: RESISTANCE before SUPPORT (column descending), then by sort order and creation. */
    List<KanbanCardEntity> findByUserIdOrderByColumnDescSortOrderAscCreatedAtAsc(String userId);

    /** Every user's cards (the alert engine watches prices for all users). */
    List<KanbanCardEntity> findAllByOrderByCreatedAtAsc();

    Optional<KanbanCardEntity> findByIdAndUserId(UUID id, String userId);

    /** Same, with a row lock (SELECT ... FOR UPDATE) held until the calling transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<KanbanCardEntity> findForUpdateByIdAndUserId(UUID id, String userId);

    @Query("select coalesce(max(c.sortOrder) + 1, 0) from KanbanCardEntity c where c.userId = :userId and c.column = :column")
    int nextSortOrder(@Param("userId") String userId, @Param("column") String column);

    /**
     * Alert engine, compare-and-set: marks any user's card NOTIFIED only if it is still ACTIVE with the column and
     * target that were hit (an edit committed meanwhile wins); returns the number of changed rows (0 or 1).
     */
    @Modifying
    @Transactional
    @Query("update KanbanCardEntity c set c.armed = false where c.id = :id and c.armed = true "
            + "and c.column = :column and c.targetPrice = :target")
    int markNotified(@Param("id") UUID id, @Param("column") String column, @Param("target") BigDecimal target);

    @Modifying
    @Transactional
    @Query("update KanbanCardEntity c set c.processInstanceKey = :key where c.id = :id")
    int updateProcessInstanceKey(@Param("id") UUID id, @Param("key") Long key);

    /** Returns the number of deleted rows (0 or 1). */
    @Modifying
    @Transactional
    @Query("delete from KanbanCardEntity c where c.id = :id and c.userId = :userId")
    int deleteByIdAndUserId(@Param("id") UUID id, @Param("userId") String userId);

    long countByTicker(String ticker);

    @Query("select new com.finallyapp.persistence.TickerFirstSeen(c.ticker, min(c.createdAt)) "
            + "from KanbanCardEntity c group by c.ticker")
    List<TickerFirstSeen> findTickersFirstSeen();
}
