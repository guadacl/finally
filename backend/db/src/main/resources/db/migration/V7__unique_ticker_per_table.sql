-- A ticker appears at most once per table: one price alert per ticker, and one Support card plus one
-- Resistance card per ticker (per user). Adding a ticker that is already there updates that row instead.
--
-- Existing duplicates are resolved by keeping the most recently edited row (then the newest, then the highest
-- id) and deleting the others. Deleted cards still have a Camunda kanban-card process instance: its key is
-- recorded in kanban_discarded_processes so the app cancels it at startup, without a "card deleted" toast/email.

CREATE TABLE kanban_discarded_processes (
    process_instance_key  BIGINT      PRIMARY KEY,
    card_id               UUID        NOT NULL,
    discarded_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TEMPORARY TABLE kanban_cards_older_duplicates ON COMMIT DROP AS
SELECT id, process_instance_key
FROM (SELECT id, process_instance_key,
             row_number() OVER (PARTITION BY user_id, ticker, column_name
                                ORDER BY updated_at DESC, created_at DESC, id DESC) AS rn
      FROM kanban_cards) ranked
WHERE rn > 1;

INSERT INTO kanban_discarded_processes (process_instance_key, card_id)
SELECT process_instance_key, id FROM kanban_cards_older_duplicates WHERE process_instance_key IS NOT NULL
ON CONFLICT (process_instance_key) DO NOTHING;

DELETE FROM kanban_cards WHERE id IN (SELECT id FROM kanban_cards_older_duplicates);

ALTER TABLE kanban_cards
    ADD CONSTRAINT kanban_cards_user_ticker_column_uk UNIQUE (user_id, ticker, column_name);

DELETE FROM price_alerts
WHERE id IN (SELECT id
             FROM (SELECT id,
                          row_number() OVER (PARTITION BY user_id, ticker ORDER BY created_at DESC, id DESC) AS rn
                   FROM price_alerts) ranked
             WHERE rn > 1);

ALTER TABLE price_alerts
    ADD CONSTRAINT price_alerts_user_ticker_uk UNIQUE (user_id, ticker);
