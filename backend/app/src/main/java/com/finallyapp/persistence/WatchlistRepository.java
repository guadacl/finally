package com.finallyapp.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WatchlistRepository extends JpaRepository<WatchlistEntryEntity, UUID> {

    List<WatchlistEntryEntity> findByUserIdOrderByAddedAtAscTickerAsc(String userId);

    boolean existsByUserIdAndTicker(String userId, String ticker);

    /** Returns the number of deleted rows (0 or 1). */
    @Modifying
    @Transactional
    @Query("delete from WatchlistEntryEntity w where w.userId = :userId and w.ticker = :ticker")
    int deleteByUserIdAndTicker(@Param("userId") String userId, @Param("ticker") String ticker);

    long countByTicker(String ticker);

    /** Idempotent insert used when seeding a new user's default watchlist (race-free, see UserProfileRepository). */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO watchlist (id, user_id, ticker, added_at) VALUES (gen_random_uuid(), :userId, :ticker, now()) "
            + "ON CONFLICT (user_id, ticker) DO NOTHING", nativeQuery = true)
    int insertIfMissing(@Param("userId") String userId, @Param("ticker") String ticker);

    @Query("select new com.finallyapp.persistence.TickerFirstSeen(w.ticker, min(w.addedAt)) "
            + "from WatchlistEntryEntity w group by w.ticker")
    List<TickerFirstSeen> findTickersFirstSeen();
}
