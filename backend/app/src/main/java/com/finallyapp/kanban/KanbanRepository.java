package com.finallyapp.kanban;

import com.finallyapp.contracts.KanbanColumn;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class KanbanRepository {

    static final String USER = "default";
    private static final String COLS =
            "id, ticker, column_name, target_price, sort_order, armed, process_instance_key, created_at, updated_at";

    public record CardRow(UUID id, String ticker, KanbanColumn column, BigDecimal targetPrice, int sortOrder,
                          boolean armed, Long processInstanceKey, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    private final JdbcClient jdbc;

    public KanbanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CardRow> findAll() {
        return jdbc.sql("SELECT " + COLS + " FROM kanban_cards WHERE user_id = :u ORDER BY column_name DESC, sort_order, created_at")
                .param("u", USER).query(KanbanRepository::map).list();
    }

    public Optional<CardRow> find(UUID id) {
        return jdbc.sql("SELECT " + COLS + " FROM kanban_cards WHERE user_id = :u AND id = :id")
                .param("u", USER).param("id", id).query(KanbanRepository::map).optional();
    }

    public CardRow insert(String ticker, KanbanColumn column, BigDecimal target) {
        return jdbc.sql("""
                        INSERT INTO kanban_cards (user_id, ticker, column_name, target_price, sort_order, armed)
                        VALUES (:u, :t, :c, :p,
                                (SELECT COALESCE(MAX(sort_order) + 1, 0) FROM kanban_cards WHERE user_id = :u AND column_name = :c),
                                TRUE)
                        RETURNING\s""" + COLS)
                .param("u", USER).param("t", ticker).param("c", column.name()).param("p", target)
                .query(KanbanRepository::map).single();
    }

    public int nextSortOrder(KanbanColumn column) {
        return jdbc.sql("SELECT COALESCE(MAX(sort_order) + 1, 0) FROM kanban_cards WHERE user_id = :u AND column_name = :c")
                .param("u", USER).param("c", column.name()).query(Integer.class).single();
    }

    public CardRow update(UUID id, KanbanColumn column, BigDecimal target, int sortOrder, boolean armed) {
        return jdbc.sql("""
                        UPDATE kanban_cards
                        SET column_name = :c, target_price = :p, sort_order = :s, armed = :a, updated_at = now()
                        WHERE user_id = :u AND id = :id
                        RETURNING\s""" + COLS)
                .param("c", column.name()).param("p", target).param("s", sortOrder).param("a", armed)
                .param("u", USER).param("id", id)
                .query(KanbanRepository::map).single();
    }

    public void setProcessInstanceKey(UUID id, Long key) {
        jdbc.sql("UPDATE kanban_cards SET process_instance_key = :k WHERE id = :id")
                .param("k", key).param("id", id).update();
    }

    public void setArmed(UUID id, boolean armed) {
        jdbc.sql("UPDATE kanban_cards SET armed = :a WHERE id = :id AND armed <> :a")
                .param("a", armed).param("id", id).update();
    }

    public boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM kanban_cards WHERE user_id = :u AND id = :id")
                .param("u", USER).param("id", id).update() > 0;
    }

    private static CardRow map(ResultSet rs, int i) throws SQLException {
        long key = rs.getLong(7);
        Long pik = rs.wasNull() ? null : key;
        return new CardRow(rs.getObject(1, UUID.class), rs.getString(2), KanbanColumn.valueOf(rs.getString(3)),
                rs.getBigDecimal(4), rs.getInt(5), rs.getBoolean(6), pik,
                rs.getObject(8, OffsetDateTime.class), rs.getObject(9, OffsetDateTime.class));
    }
}
