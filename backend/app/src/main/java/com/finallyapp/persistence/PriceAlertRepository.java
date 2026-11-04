package com.finallyapp.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PriceAlertRepository extends JpaRepository<PriceAlertEntity, UUID> {

    List<PriceAlertEntity> findByUserIdOrderByCreatedAtAscIdAsc(String userId);

    Optional<PriceAlertEntity> findByIdAndUserId(UUID id, String userId);

    long countByTicker(String ticker);

    @Query("select new com.finallyapp.persistence.TickerFirstSeen(a.ticker, min(a.createdAt)) "
            + "from PriceAlertEntity a group by a.ticker")
    List<TickerFirstSeen> findTickersFirstSeen();
}
