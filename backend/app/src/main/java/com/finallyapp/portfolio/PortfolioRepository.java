package com.finallyapp.portfolio;

import com.finallyapp.portfolio.PortfolioMath.Holding;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PortfolioRepository {

    public static final String USER = "default";

    public record PositionRow(String ticker, BigDecimal quantity, BigDecimal avgCost, OffsetDateTime updatedAt) {
    }

    public record TradeRow(UUID id, String ticker, String side, BigDecimal quantity, BigDecimal price,
                           OffsetDateTime executedAt) {
    }

    public record SnapshotRow(BigDecimal totalValue, OffsetDateTime recordedAt) {
    }

    private final JdbcClient jdbc;

    public PortfolioRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public BigDecimal cash() {
        ensureProfile();
        return jdbc.sql("SELECT cash_balance FROM users_profile WHERE id = :u")
                .param("u", USER).query(BigDecimal.class).single();
    }

    /** Reads the cash balance with a row lock (call inside a transaction). */
    public BigDecimal lockCash() {
        ensureProfile();
        return jdbc.sql("SELECT cash_balance FROM users_profile WHERE id = :u FOR UPDATE")
                .param("u", USER).query(BigDecimal.class).single();
    }

    public void setCash(BigDecimal cash) {
        jdbc.sql("UPDATE users_profile SET cash_balance = :c WHERE id = :u")
                .param("c", cash).param("u", USER).update();
    }

    private void ensureProfile() {
        jdbc.sql("INSERT INTO users_profile (id) VALUES (:u) ON CONFLICT (id) DO NOTHING").param("u", USER).update();
    }

    public List<PositionRow> positions() {
        return jdbc.sql("SELECT ticker, quantity, avg_cost, updated_at FROM positions WHERE user_id = :u ORDER BY ticker")
                .param("u", USER)
                .query((rs, i) -> new PositionRow(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getObject(4, OffsetDateTime.class)))
                .list();
    }

    public Optional<Holding> holding(String ticker) {
        return jdbc.sql("SELECT quantity, avg_cost FROM positions WHERE user_id = :u AND ticker = :t")
                .param("u", USER).param("t", ticker)
                .query((rs, i) -> new Holding(rs.getBigDecimal(1), rs.getBigDecimal(2)))
                .optional();
    }

    public void saveHolding(String ticker, Holding h) {
        if (h == null) {
            jdbc.sql("DELETE FROM positions WHERE user_id = :u AND ticker = :t")
                    .param("u", USER).param("t", ticker).update();
            return;
        }
        jdbc.sql("""
                        INSERT INTO positions (user_id, ticker, quantity, avg_cost, updated_at)
                        VALUES (:u, :t, :q, :a, now())
                        ON CONFLICT (user_id, ticker)
                        DO UPDATE SET quantity = EXCLUDED.quantity, avg_cost = EXCLUDED.avg_cost, updated_at = now()
                        """)
                .param("u", USER).param("t", ticker).param("q", h.quantity()).param("a", h.avgCost())
                .update();
    }

    public TradeRow insertTrade(String ticker, String side, BigDecimal qty, BigDecimal price) {
        return jdbc.sql("""
                        INSERT INTO trades (user_id, ticker, side, quantity, price)
                        VALUES (:u, :t, :s, :q, :p)
                        RETURNING id, ticker, side, quantity, price, executed_at
                        """)
                .param("u", USER).param("t", ticker).param("s", side).param("q", qty).param("p", price)
                .query((rs, i) -> new TradeRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getObject(6, OffsetDateTime.class)))
                .single();
    }

    public void insertSnapshot(BigDecimal totalValue) {
        jdbc.sql("INSERT INTO portfolio_snapshots (user_id, total_value) VALUES (:u, :v)")
                .param("u", USER).param("v", totalValue).update();
    }

    /** Snapshots oldest first (most recent {@code limit}). */
    public List<SnapshotRow> snapshots(int limit) {
        return jdbc.sql("""
                        SELECT total_value, recorded_at FROM (
                          SELECT total_value, recorded_at FROM portfolio_snapshots
                          WHERE user_id = :u ORDER BY recorded_at DESC LIMIT :n
                        ) s ORDER BY recorded_at ASC
                        """)
                .param("u", USER).param("n", limit)
                .query((rs, i) -> new SnapshotRow(rs.getBigDecimal(1), rs.getObject(2, OffsetDateTime.class)))
                .list();
    }
}
