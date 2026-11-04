package com.finallyapp.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PositionRepository extends JpaRepository<PositionEntity, UUID> {

    List<PositionEntity> findByUserIdOrderByTickerAsc(String userId);

    Optional<PositionEntity> findByUserIdAndTicker(String userId, String ticker);

    /** Returns the number of deleted rows (0 or 1). */
    @Modifying
    @Transactional
    @Query("delete from PositionEntity p where p.userId = :userId and p.ticker = :ticker")
    int deleteByUserIdAndTicker(@Param("userId") String userId, @Param("ticker") String ticker);

    long countByTicker(String ticker);

    @Query("select new com.finallyapp.persistence.TickerFirstSeen(p.ticker, min(p.updatedAt)) "
            + "from PositionEntity p group by p.ticker")
    List<TickerFirstSeen> findTickersFirstSeen();
}
