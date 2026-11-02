# FinAlly — Camunda workflow

Each kanban card (SUPPORT/RESISTANCE price level) has its own instance of the Camunda 8 process `kanban-card`.
Camunda tracks the card's column and sends every browser notification through one job worker.

## Files

| Path | What |
|---|---|
| `camunda/bpmn/kanban-card.bpmn` | The process model (open it in Camunda Modeler; it also shows in Operate) |
| `camunda/compose.camunda.yml` | Docker services `camunda` + `camunda-elasticsearch` (included by the root `docker-compose.yml`) |
| `camunda/config/application.yaml` | Orchestration-cluster config (basic auth, demo user, Elasticsearch) |
| `backend/workflow/` | Maven module `finally-workflow`: Camunda client, BPMN deployment, `finally-notify` job worker |

## The process (`kanban-card`)

```
(Card created) -> <Column?> --column = "SUPPORT"--> [Notify: card created in Support]    --+
                           \--default (RESISTANCE)--> [Notify: card created in Resistance] --+-> <merge> -> (( card-deleted )) -> (Card removed)

Event subprocess, non-interrupting:  (( target-price-hit )) -> [Notify: target price hit] -> (end)
Event subprocess, non-interrupting:  (( card-moved ))       -> [Notify: card moved]       -> (end)
```

- The **exclusive gateway on `column`** comes right after the start and routes to a "card created" notification task for each column.
- The main path then waits for the `card-deleted` message, which ends the instance.
- `target-price-hit` and `card-moved` start **non-interrupting event subprocesses**, so they can fire any number of times while the card exists.
  The message variables are merged into the process variables, so a `card-moved` updates `column`/`targetPrice` and a `target-price-hit` sets `price`.
- **Every** notification is a service task with job type **`finally-notify`**. Each task has the header
  `notificationType` = `CARD_CREATED` | `TARGET_HIT` | `CARD_MOVED`.

### Variables

| Variable | Type | Set by |
|---|---|---|
| `cardId` | string (UUID) | start; **correlation key** for all messages |
| `ticker` | string | start |
| `column` | `SUPPORT` \| `RESISTANCE` | start, `card-moved` |
| `targetPrice` | number | start, `card-moved` |
| `price` | number | `target-price-hit` |

### Messages (correlation key = `cardId`)

| Message | Variables | Effect |
|---|---|---|
| `card-moved` | `column`, `targetPrice` | updates variables, notifies `CARD_MOVED` ("AAPL moved to RESISTANCE @ 200.00") |
| `target-price-hit` | `price` | notifies `TARGET_HIT` ("AAPL hit support 185.00 (now 184.97)") |
| `card-deleted` | – | ends the instance |

The app sends messages with the **correlate-message** API (`POST /v2/messages/correlation`), not
*publish*. Nothing is buffered, so a message can never reach a later instance by mistake. If no
instance is waiting (for example, the card was created while Camunda was down), the call fails and the
app publishes the notification directly.

## Java side (`backend/workflow`, package `com.finallyapp.workflow`)

- `CamundaKanbanWorkflow` (`finally.workflow.enabled=true`, the default) implements `KanbanWorkflow`:
  - `cardCreated` starts an instance and waits at most 2 s.
  - The other methods correlate messages asynchronously.
  - On any error, the notification is published directly via `NotificationPublisher`. These methods never throw.
- `DirectKanbanWorkflow` (`finally.workflow.enabled=false`) never uses Camunda and publishes notifications directly.
- `NotifyJobWorker` runs `@JobWorker(type = "finally-notify")`. The notification id comes from the job key, so a retried job keeps the same id. Publisher errors are logged and the job still completes.
- `BpmnDeployer` deploys `classpath*:bpmn/*.bpmn` on a background thread after startup and retries until Camunda is up. The app starts fine without Camunda.
- `NotificationMessages` builds the message text for both the worker and the fallback, so the text is the same either way.
- Config defaults are in `finally-workflow.yml`. The app imports them with `spring.config.import: optional:classpath:finally-workflow.yml`.
  The env vars are `FINALLY_WORKFLOW_ENABLED`, `CAMUNDA_GRPC_ADDRESS` and `CAMUNDA_REST_ADDRESS`.
  `camunda.client.enabled` follows `finally.workflow.enabled`.

## Running Camunda

```bash
# from the repo root: only Camunda (and its Elasticsearch)
docker compose up -d camunda
# or standalone
docker compose -f camunda/compose.camunda.yml up -d
# stop (volumes finally-camunda / finally-camunda-es are kept)
docker compose -f camunda/compose.camunda.yml down
```

- **Operate / Tasklist / Admin:** http://localhost:8081 (Operate: http://localhost:8081/operate). Log in with **demo / demo**.
- **Zeebe gRPC:** `localhost:26500`. **REST API:** `http://localhost:8081/v2/...`. The APIs are unprotected for local use.
- For local dev outside Docker, the defaults in `finally-workflow.yml` already point at the host ports:
  `http://localhost:26500` for gRPC and `http://localhost:8081` for REST.
  Inside Docker, the app gets `http://camunda:26500` / `http://camunda:8080`.
- Startup takes about 1–2 minutes. The container turns `healthy` once `/actuator/health/status` on the management port 9600 reports `UP`.
- Images: `camunda/camunda:8.8.45` and `elasticsearch:8.19.23`, roughly 1.5–2 GB of RAM in total.
  Camunda 8.8's Operate and Tasklist need Elasticsearch/OpenSearch as secondary storage. H2/RDBMS became the lightweight default only in 8.9.

### Live smoke test

With Camunda running:

```bash
mvn -f backend/pom.xml -pl workflow test -Dtest=CamundaLiveSmokeTest -Dfinally.it.camunda=true \
    -Dcamunda.grpc=http://localhost:26500 -Dcamunda.rest=http://localhost:8081
```

This deploys the BPMN, creates a SUPPORT card and sends two `target-price-hit` messages, a `card-moved` and another hit, then `card-deleted`.
It checks the five notifications produced by a `finally-notify` worker.
