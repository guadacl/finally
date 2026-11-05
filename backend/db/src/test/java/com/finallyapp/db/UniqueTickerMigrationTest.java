package com.finallyapp.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V7 on a database that already holds duplicates: keeps the most recently edited row per ticker and table,
 * deletes the others and records the deleted cards' Camunda process instance keys for cancellation.
 */
@Testcontainers(disabledWithoutDocker = true)
class UniqueTickerMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("finally")
            .withUsername("finally")
            .withPassword("finally");

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static List<String> rows(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    @Test
    void keepsTheNewestRowPerTickerAndRecordsDiscardedProcesses() throws SQLException {
        flyway("6").migrate();
        // AAPL Resistance twice: the 380 card was edited last and is kept; 300 goes (process 101 recorded)
        exec("INSERT INTO kanban_cards (user_id, ticker, column_name, target_price, process_instance_key, created_at, updated_at) VALUES "
                + "('u1', 'AAPL', 'RESISTANCE', 300, 101, '2026-11-03T10:00Z', '2026-11-03T10:00Z'),"
                + "('u1', 'AAPL', 'RESISTANCE', 380, 102, '2026-11-03T09:00Z', '2026-11-04T09:00Z'),"
                // a single card per column is untouched, as is the same ticker for another user
                + "('u1', 'AAPL', 'SUPPORT', 150, 103, '2026-11-03T10:00Z', '2026-11-03T10:00Z'),"
                + "('u2', 'AAPL', 'RESISTANCE', 999, 104, '2026-11-01T10:00Z', '2026-11-01T10:00Z'),"
                // a discarded card without a process instance is simply deleted
                + "('u1', 'RUN', 'SUPPORT', 9, NULL, '2026-11-03T10:00Z', '2026-11-03T10:00Z'),"
                + "('u1', 'RUN', 'SUPPORT', 8.61, 105, '2026-11-05T10:00Z', '2026-11-05T10:00Z')");
        exec("INSERT INTO price_alerts (user_id, ticker, upper_limit, created_at) VALUES "
                + "('u1', 'MSFT', 500, '2026-11-03T10:00Z'), ('u1', 'MSFT', 600, '2026-11-04T10:00Z')");

        flyway("latest").migrate();

        assertThat(rows("SELECT user_id || ' ' || ticker || ' ' || column_name || ' ' || target_price::numeric(10,2) "
                + "FROM kanban_cards ORDER BY 1")).containsExactly(
                "u1 AAPL RESISTANCE 380.00", "u1 AAPL SUPPORT 150.00", "u1 RUN SUPPORT 8.61", "u2 AAPL RESISTANCE 999.00");
        assertThat(rows("SELECT process_instance_key::text FROM kanban_discarded_processes ORDER BY 1"))
                .containsExactly("101");
        assertThat(rows("SELECT upper_limit::numeric(10,0)::text FROM price_alerts")).containsExactly("600");
    }
}
