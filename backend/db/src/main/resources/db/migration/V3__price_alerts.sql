-- Price alerts (Alerts page): a watchlist ticker with an upper and/or lower price limit and a free-text comment.
CREATE TABLE price_alerts (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      TEXT          NOT NULL DEFAULT 'default',
    ticker       TEXT          NOT NULL,
    upper_limit  NUMERIC(18,4) NULL,
    lower_limit  NUMERIC(18,4) NULL,
    comment      TEXT          NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT price_alerts_upper_positive_ck CHECK (upper_limit IS NULL OR upper_limit > 0),
    CONSTRAINT price_alerts_lower_positive_ck CHECK (lower_limit IS NULL OR lower_limit > 0),
    CONSTRAINT price_alerts_some_limit_ck     CHECK (upper_limit IS NOT NULL OR lower_limit IS NOT NULL),
    CONSTRAINT price_alerts_range_ck          CHECK (upper_limit IS NULL OR lower_limit IS NULL OR lower_limit < upper_limit),
    CONSTRAINT price_alerts_comment_len_ck    CHECK (comment IS NULL OR char_length(comment) <= 500)
);

CREATE INDEX idx_price_alerts_user_created ON price_alerts (user_id, created_at);
