-- Default seed data (idempotent). Contract: planning/CONTRACTS.md §4.

INSERT INTO users_profile (id, cash_balance)
VALUES ('default', 10000.0000)
ON CONFLICT DO NOTHING;

INSERT INTO watchlist (user_id, ticker)
VALUES ('default', 'AAPL'),
       ('default', 'GOOGL'),
       ('default', 'MSFT'),
       ('default', 'AMZN'),
       ('default', 'TSLA'),
       ('default', 'NVDA'),
       ('default', 'META'),
       ('default', 'JPM'),
       ('default', 'V'),
       ('default', 'NFLX')
ON CONFLICT DO NOTHING;
