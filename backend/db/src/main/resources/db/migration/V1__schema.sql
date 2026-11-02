-- FinAlly schema (Postgres 16). Contract: planning/CONTRACTS.md §4.
-- gen_random_uuid() is built in since Postgres 13.

CREATE TABLE users_profile (
    id            TEXT PRIMARY KEY DEFAULT 'default',
    cash_balance  NUMERIC(18,4) NOT NULL DEFAULT 10000.0000,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE watchlist (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id   TEXT NOT NULL DEFAULT 'default',
    ticker    TEXT NOT NULL,
    added_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT watchlist_user_ticker_uk UNIQUE (user_id, ticker)
);

CREATE TABLE positions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     TEXT NOT NULL DEFAULT 'default',
    ticker      TEXT NOT NULL,
    quantity    NUMERIC(18,6) NOT NULL,
    avg_cost    NUMERIC(18,4) NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT positions_user_ticker_uk UNIQUE (user_id, ticker)
);

CREATE TABLE trades (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      TEXT NOT NULL DEFAULT 'default',
    ticker       TEXT NOT NULL,
    side         TEXT NOT NULL CONSTRAINT trades_side_ck CHECK (side IN ('buy', 'sell')),
    quantity     NUMERIC(18,6) NOT NULL CONSTRAINT trades_quantity_ck CHECK (quantity > 0),
    price        NUMERIC(18,4) NOT NULL,
    executed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE portfolio_snapshots (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      TEXT NOT NULL DEFAULT 'default',
    total_value  NUMERIC(18,4) NOT NULL,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chat_messages (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     TEXT NOT NULL DEFAULT 'default',
    role        TEXT NOT NULL CONSTRAINT chat_messages_role_ck CHECK (role IN ('user', 'assistant')),
    content     TEXT NOT NULL,
    actions     JSONB NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE kanban_cards (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               TEXT NOT NULL DEFAULT 'default',
    ticker                TEXT NOT NULL,
    column_name           TEXT NOT NULL CONSTRAINT kanban_cards_column_ck CHECK (column_name IN ('SUPPORT', 'RESISTANCE')),
    target_price          NUMERIC(18,4) NOT NULL CONSTRAINT kanban_cards_target_price_ck CHECK (target_price > 0),
    sort_order            INTEGER NOT NULL DEFAULT 0,
    armed                 BOOLEAN NOT NULL DEFAULT TRUE,   -- alert re-arm state
    process_instance_key  BIGINT NULL,                     -- Camunda process instance
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_trades_user_executed_at              ON trades (user_id, executed_at);
CREATE INDEX idx_portfolio_snapshots_user_recorded_at ON portfolio_snapshots (user_id, recorded_at);
CREATE INDEX idx_chat_messages_user_created_at        ON chat_messages (user_id, created_at);
CREATE INDEX idx_kanban_cards_user_column_sort        ON kanban_cards (user_id, column_name, sort_order);
