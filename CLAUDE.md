# FinAlly Project - the Finance Ally

All project documentation is in the `planning` directory.

The key document is PLAN.md included in full below. `planning/CONTRACTS.md` is the binding build contract (stack: Angular + Java 21/Spring Boot + Postgres + Camunda 8 via Docker Compose; schema; API; module ownership) and overrides PLAN.md wherever PLAN.md still mentions Python/FastAPI/SQLite/Next.js. The API is spec-first: `backend/openapi/openapi.yaml`.

The original Python market data component (summarized in `planning/MARKET_DATA_SUMMARY.md`, details in `planning/archive`) is being ported to Java in `backend/app`. Consult these docs only when required.

@planning/PLAN.md