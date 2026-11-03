package com.finallyapp.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Applies the Flyway migrations from classpath:db/migration to a throwaway Postgres 16
 * and checks schema, seed data and constraints. Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("finally")
            .withUsername("finally")
            .withPassword("finally");

    static MigrateResult firstRun;

    static Flyway flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
    }

    @BeforeAll
    static void migrate() {
        firstRun = flyway().migrate();
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    static void exec(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static long count(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @Order(1)
    void migrationsApplyCleanlyOnEmptyDatabase() throws SQLException {
        assertThat(firstRun.success).isTrue();
        assertThat(firstRun.migrationsExecuted).isEqualTo(6);
        assertThat(flyway().validateWithResult().validationSuccessful).isTrue();

        List<String> tables = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT table_name FROM information_schema.tables "
                             + "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history' "
                             + "ORDER BY table_name")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        assertThat(tables).containsExactly(
                "chat_messages", "kanban_cards", "portfolio_snapshots", "positions",
                "price_alerts", "trades", "users_profile", "watchlist");

        assertThat(count("SELECT count(*) FROM pg_indexes WHERE indexname IN ("
                + "'idx_trades_user_executed_at','idx_portfolio_snapshots_user_recorded_at',"
                + "'idx_chat_messages_user_created_at','idx_kanban_cards_user_column_sort','idx_price_alerts_user_created')"))
                .isEqualTo(5);

        // V5: nullable users_profile.email (alert emails)
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'users_profile' "
                + "AND column_name = 'email' AND data_type = 'text' AND is_nullable = 'YES'")).isEqualTo(1);
        // V6: nullable users_profile.phone (SMS notifications)
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'users_profile' "
                + "AND column_name = 'phone' AND data_type = 'text' AND is_nullable = 'YES'")).isEqualTo(1);
    }

    @Test
    @Order(2)
    void rerunningMigrationsIsNoOp() throws SQLException {
        MigrateResult second = flyway().migrate();
        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted).isZero();
        assertThat(count("SELECT count(*) FROM watchlist")).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM users_profile")).isEqualTo(1);
    }

    @Test
    @Order(3)
    void seedDataPresent() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, cash_balance FROM users_profile")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("id")).isEqualTo("default");
            assertThat(rs.getBigDecimal("cash_balance")).isEqualByComparingTo(new BigDecimal("10000"));
            assertThat(rs.next()).isFalse();
        }

        List<String> tickers = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT ticker FROM watchlist WHERE user_id = 'default'")) {
            while (rs.next()) {
                tickers.add(rs.getString(1));
            }
        }
        assertThat(tickers).containsExactlyInAnyOrder(
                "AAPL", "GOOGL", "MSFT", "AMZN", "TSLA", "NVDA", "META", "JPM", "V", "NFLX");

        assertThat(count("SELECT count(*) FROM kanban_cards")).isZero();
    }

    @Test
    @Order(4)
    void seedScriptIsIdempotent() throws Exception {
        String seed = new String(
                MigrationsTest.class.getResourceAsStream("/db/migration/V2__seed.sql").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        exec(seed);
        assertThat(count("SELECT count(*) FROM users_profile")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM watchlist")).isEqualTo(10);
    }

    @Test
    @Order(5)
    void defaultsAreApplied() throws SQLException {
        exec("INSERT INTO kanban_cards (ticker, column_name, target_price) VALUES ('ZZDEF', 'SUPPORT', 1.5)");
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id, user_id, sort_order, armed, process_instance_key, created_at, updated_at "
                             + "FROM kanban_cards WHERE ticker = 'ZZDEF'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getObject("id")).isInstanceOf(java.util.UUID.class);
            assertThat(rs.getString("user_id")).isEqualTo("default");
            assertThat(rs.getInt("sort_order")).isZero();
            assertThat(rs.getBoolean("armed")).isTrue();
            assertThat(rs.getObject("process_instance_key")).isNull();
            assertThat(rs.getTimestamp("created_at")).isNotNull();
            assertThat(rs.getTimestamp("updated_at")).isNotNull();
        }
        exec("DELETE FROM kanban_cards WHERE ticker = 'ZZDEF'");

        exec("INSERT INTO chat_messages (role, content, actions) "
                + "VALUES ('assistant', 'hi', '{\"trades\":[]}'::jsonb)");
        assertThat(count("SELECT count(*) FROM chat_messages WHERE actions ? 'trades'")).isEqualTo(1);
        exec("DELETE FROM chat_messages");
    }

    @Test
    void watchlistUniquePerUserAndTicker() throws SQLException {
        assertThatThrownBy(() -> exec("INSERT INTO watchlist (user_id, ticker) VALUES ('default', 'AAPL')"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
        // Same ticker for another user is fine.
        exec("INSERT INTO watchlist (user_id, ticker) VALUES ('other-user', 'AAPL')");
        exec("DELETE FROM watchlist WHERE user_id = 'other-user'");
    }

    @Test
    void positionsUniquePerUserAndTicker() throws SQLException {
        exec("INSERT INTO positions (ticker, quantity, avg_cost) VALUES ('ZZPOS', 1.5, 100)");
        try {
            assertThatThrownBy(() -> exec(
                    "INSERT INTO positions (ticker, quantity, avg_cost) VALUES ('ZZPOS', 2, 101)"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
        } finally {
            exec("DELETE FROM positions WHERE ticker = 'ZZPOS'");
        }
    }

    @Test
    void tradeSideAndQuantityChecks() throws SQLException {
        assertCheckViolation("INSERT INTO trades (ticker, side, quantity, price) VALUES ('AAPL', 'hold', 1, 190)");
        assertCheckViolation("INSERT INTO trades (ticker, side, quantity, price) VALUES ('AAPL', 'buy', 0, 190)");
        assertCheckViolation("INSERT INTO trades (ticker, side, quantity, price) VALUES ('AAPL', 'sell', -1, 190)");
        exec("INSERT INTO trades (ticker, side, quantity, price) VALUES ('ZZTRD', 'buy', 0.5, 190)");
        exec("INSERT INTO trades (ticker, side, quantity, price) VALUES ('ZZTRD', 'sell', 0.25, 191)");
        assertThat(count("SELECT count(*) FROM trades WHERE ticker = 'ZZTRD'")).isEqualTo(2);
        exec("DELETE FROM trades WHERE ticker = 'ZZTRD'");
    }

    @Test
    void chatRoleCheck() throws SQLException {
        assertCheckViolation("INSERT INTO chat_messages (role, content) VALUES ('system', 'x')");
    }

    @Test
    void kanbanColumnAndTargetPriceChecks() throws SQLException {
        assertCheckViolation(
                "INSERT INTO kanban_cards (ticker, column_name, target_price) VALUES ('AAPL', 'support', 10)");
        assertCheckViolation(
                "INSERT INTO kanban_cards (ticker, column_name, target_price) VALUES ('AAPL', 'MIDDLE', 10)");
        assertCheckViolation(
                "INSERT INTO kanban_cards (ticker, column_name, target_price) VALUES ('AAPL', 'SUPPORT', 0)");
        assertCheckViolation(
                "INSERT INTO kanban_cards (ticker, column_name, target_price) VALUES ('AAPL', 'RESISTANCE', -5)");
    }

    @Test
    void priceAlertChecks() throws SQLException {
        exec("INSERT INTO price_alerts (ticker, upper_limit, lower_limit, comment) VALUES ('AAPL', 200, 150, 'ok')");
        exec("INSERT INTO price_alerts (ticker, upper_limit) VALUES ('AAPL', 200)");
        exec("INSERT INTO price_alerts (ticker, lower_limit) VALUES ('AAPL', 150)");
        assertCheckViolation("INSERT INTO price_alerts (ticker) VALUES ('AAPL')");
        assertCheckViolation("INSERT INTO price_alerts (ticker, upper_limit, lower_limit) VALUES ('AAPL', 150, 200)");
        assertCheckViolation("INSERT INTO price_alerts (ticker, upper_limit) VALUES ('AAPL', 0)");
        assertCheckViolation("INSERT INTO price_alerts (ticker, lower_limit) VALUES ('AAPL', -1)");
        assertCheckViolation("INSERT INTO price_alerts (ticker, upper_limit, comment) VALUES ('AAPL', 1, repeat('x', 501))");
    }

    private static void assertCheckViolation(String sql) {
        assertThatThrownBy(() -> exec(sql))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
    }
}
