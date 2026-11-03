-- Free-text comment on a kanban card (Support / Resistance), editable on the board.
-- The Alerts page no longer shows price_alerts.comment; that column is kept (no data is dropped).
ALTER TABLE kanban_cards
    ADD COLUMN comment TEXT NULL,
    ADD CONSTRAINT kanban_cards_comment_len_ck CHECK (comment IS NULL OR char_length(comment) <= 500);
