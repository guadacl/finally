# Tradex database (Postgres 16)

## How the schema is applied

The SQL migrations live in `backend/db/src/main/resources/db/migration/` (Maven module `finally-db`):

- `V1__schema.sql` — all tables, constraints and indexes (see `planning/CONTRACTS.md` §4)
- `V2__seed.sql` — default profile (`default`, $10,000 cash) and the 10 default watchlist tickers (idempotent)

The app depends on `finally-db`, so the files are on its classpath. Flyway runs inside the Spring Boot app
at startup against `classpath:db/migration`; an empty database is migrated and seeded automatically, an
up-to-date one is left untouched. There is no manual migration step.

## Running Postgres

```bash
docker compose up -d postgres          # just the database (from the repo root)
docker compose up -d --build           # full stack
```

Data lives in the named Docker volume `finally-pgdata` and survives `docker compose down`.

## Connecting with psql

```bash
docker exec -it finally-postgres psql -U finally -d finally
# or from the host (port 5432 is published for dev):
psql "postgresql://finally:finally@localhost:5432/finally"
```

Credentials come from `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` in `.env` (default `finally` for all three).

## Resetting the data

```bash
docker compose down -v                 # removes containers AND volumes (finally-pgdata, Camunda data too)
docker compose up -d --build           # fresh, re-seeded database on next start
```

To wipe only the database: `docker compose rm -sf postgres && docker volume rm finally-pgdata`.

## Tests

`mvn -f backend/pom.xml -pl db test` applies the migrations to a throwaway `postgres:16` via Testcontainers
(skipped automatically when Docker is not available).
