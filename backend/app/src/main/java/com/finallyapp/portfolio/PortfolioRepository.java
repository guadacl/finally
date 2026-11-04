package com.finallyapp.portfolio;

import com.finallyapp.persistence.PortfolioSnapshotEntity;
import com.finallyapp.persistence.PortfolioSnapshotRepository;
import com.finallyapp.persistence.PositionEntity;
import com.finallyapp.persistence.PositionRepository;
import com.finallyapp.persistence.TradeEntity;
import com.finallyapp.persistence.TradeRepository;
import com.finallyapp.persistence.UserProfileEntity;
import com.finallyapp.persistence.UserProfileRepository;
import com.finallyapp.portfolio.PortfolioMath.Holding;
import com.finallyapp.security.CurrentUser;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

/**
 * The current user's cash, positions, trades and portfolio snapshots, on top of the Spring Data JPA repositories.
 * Methods that change several rows ({@link #lockCash()}, {@link #setCash}, {@link #saveHolding}) are meant to run
 * inside the trade transaction of {@link PortfolioService}.
 */
@Repository
public class PortfolioRepository {

    public record PositionRow(String ticker, BigDecimal quantity, BigDecimal avgCost, OffsetDateTime updatedAt) {
    }

    public record TradeRow(UUID id, String ticker, String side, BigDecimal quantity, BigDecimal price,
                           OffsetDateTime executedAt) {
    }

    public record SnapshotRow(BigDecimal totalValue, OffsetDateTime recordedAt) {
    }

    private final UserProfileRepository profiles;
    private final PositionRepository positions;
    private final TradeRepository trades;
    private final PortfolioSnapshotRepository snapshots;

    public PortfolioRepository(UserProfileRepository profiles, PositionRepository positions, TradeRepository trades,
                               PortfolioSnapshotRepository snapshots) {
        this.profiles = profiles;
        this.positions = positions;
        this.trades = trades;
        this.snapshots = snapshots;
    }

    /**
     * Every user with a profile. When authentication is on, the pre-Keycloak {@code default} user is skipped
     * (nobody can log in as it, so its portfolio no longer changes).
     */
    public List<String> userIds() {
        List<String> all = profiles.findAllIdsOrderByCreatedAt();
        return !CurrentUser.isAuthEnabled() ? all
                : all.stream().filter(id -> !CurrentUser.LEGACY_USER.equals(id)).toList();
    }

    public BigDecimal cash() {
        return profile().getCashBalance();
    }

    /** Reads the cash balance with a row lock (call inside a transaction). */
    public BigDecimal lockCash() {
        String user = CurrentUser.id();
        profiles.insertIfMissing(user);
        return profiles.findForUpdate(user).orElseThrow().getCashBalance();
    }

    /** Sets the cash balance (inside the trade transaction the locked profile is updated at commit). */
    public void setCash(BigDecimal cash) {
        UserProfileEntity p = profile();
        p.setCashBalance(cash);
        profiles.save(p);
    }

    private UserProfileEntity profile() {
        String user = CurrentUser.id();
        return profiles.findById(user).orElseGet(() -> {
            profiles.insertIfMissing(user);
            return profiles.findById(user).orElseThrow();
        });
    }

    public List<PositionRow> positions() {
        return positions.findByUserIdOrderByTickerAsc(CurrentUser.id()).stream()
                .map(p -> new PositionRow(p.getTicker(), p.getQuantity(), p.getAvgCost(), p.getUpdatedAt()))
                .toList();
    }

    public Optional<Holding> holding(String ticker) {
        return positions.findByUserIdAndTicker(CurrentUser.id(), ticker)
                .map(p -> new Holding(p.getQuantity(), p.getAvgCost()));
    }

    /** Creates, updates or (when {@code h} is null, i.e. fully sold) deletes the position. */
    public void saveHolding(String ticker, Holding h) {
        String user = CurrentUser.id();
        if (h == null) {
            positions.deleteByUserIdAndTicker(user, ticker);
            return;
        }
        PositionEntity p = positions.findByUserIdAndTicker(user, ticker)
                .orElseGet(() -> new PositionEntity(user, ticker));
        p.setQuantity(h.quantity());
        p.setAvgCost(h.avgCost());
        positions.save(p);
    }

    public TradeRow insertTrade(String ticker, String side, BigDecimal qty, BigDecimal price) {
        TradeEntity t = trades.save(new TradeEntity(CurrentUser.id(), ticker, side, qty, price));
        return new TradeRow(t.getId(), t.getTicker(), t.getSide(), t.getQuantity(), t.getPrice(), t.getExecutedAt());
    }

    public void insertSnapshot(BigDecimal totalValue) {
        snapshots.save(new PortfolioSnapshotEntity(CurrentUser.id(), totalValue));
    }

    /** Snapshots oldest first (most recent {@code limit}). */
    public List<SnapshotRow> snapshots(int limit) {
        List<SnapshotRow> rows = new ArrayList<>(snapshots
                .findByUserIdOrderByRecordedAtDesc(CurrentUser.id(), PageRequest.of(0, limit)).stream()
                .map(s -> new SnapshotRow(s.getTotalValue(), s.getRecordedAt()))
                .toList());
        Collections.reverse(rows);
        return rows;
    }
}
