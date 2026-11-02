# Promise Graph

Promise Graph detects the commitments people make in chat, like "I'll send it by Friday", and
tracks each one as a durable Temporal workflow. It sends reminders, checks on the due date,
notices when a promise slips, and records whether it was kept or missed.

Every day, people at work make small commitments that are never written down anywhere. When
one is forgotten or quietly slips, the people depending on it find out too late. Promise Graph
follows through on them, without anyone changing how they work.

**Status: Phase 1 (capture and track) is implemented, and it can read a personal Gmail account.**
Dependency links, ripple alerts and handoffs come in Phase 2.

## How it works

Messages arrive two ways: the `GmailSyncWorkflow` reads new sent and received email every minute
(see [docs/gmail-setup.md](docs/gmail-setup.md)), and any other source can post to the webhook.

```
email or chat -> GmailSyncWorkflow ----> ExtractionWorkflow ------------------> PromiseWorkflow (one per promise)
                 or webhook gateway      1. claim + cheap filter               confirm or auto-activate
                (batch per channel)   1. claim + cheap filter               confirm or auto-activate
                                         2. extract (rules or model)           reminders, due-day check
                                         3. match to existing promises         slip, kept, missed
                                         4. start or signal promise workflows  every change recorded
```

| Package | What it holds |
| --- | --- |
| `domain` | Business rules: lifecycle, IDs, timing, matching. No Temporal, Spring or I/O. |
| `extraction` | Commitment filter, date parsing, rule-based extractor; `model` validates and calls the model. |
| `ingestion` | Gateway (batching, dedupe), webhook endpoint, replay of the simulated company. |
| `gmail` | Read-only Gmail sync: sign-in, history-based fetching, email cleaning and filtering. |
| `temporal` | Workflows, activities, worker registration. |
| `store` | In-memory and Postgres (JDBC) repositories. |
| `sim`, `eval` | Simulated 20-person company with 300 labelled messages; the Gate 1 evaluation. |

Built with Java 21, Spring Boot 3.5, Temporal Java SDK 1.40, Postgres and Gradle.

## Run it locally

Requirements: Java 21 and Docker.

```bash
docker compose up -d       # Temporal, Temporal UI (http://localhost:8080), Postgres
./gradlew bootRun          # terminal 1: worker + webhook on :8787 (in-memory store, rule extractor)

# terminal 2: replay the simulated company's messages, then exit
./gradlew bootRun --args='--spring.profiles.active=replay --promisegraph.temporal.worker-enabled=false --server.port=0'
```

Open the Temporal UI to watch each `promise-…` workflow. Replayed promises use real time, so
their timers fire on real dates; the workflow tests use Temporal's time-skipping test server
to run whole lifecycles in seconds.

Settings come from `application.yml` and can be overridden with environment variables:

| Variable | Default | Meaning |
| --- | --- | --- |
| `PG_STORE` | `memory` | `jdbc` uses Postgres (`DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`) |
| `PG_EXTRACTOR` | `rules` | `llm` uses the Anthropic API (`ANTHROPIC_API_KEY`, `PG_MODEL`) |
| `PG_WEBHOOK_TOKEN` | empty | Required to accept `POST /messages` |
| `PG_CONFIDENCE_THRESHOLD` | `0.8` | Promises at or above this activate without confirmation |
| `TEMPORAL_ADDRESS` | `localhost:7233` | Temporal frontend |

Posting a message to the webhook:

```bash
curl -X POST localhost:8787/messages -H "Authorization: Bearer $PG_WEBHOOK_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"id":"m1","channel":"platform","sender":"neha","ts":"2026-11-02T10:00:00+05:30","text":"I will ship the API by Friday."}'
```

## Tests and quality gates

```bash
./gradlew test       # unit tests, workflow tests (time-skipping server), Spring context test
./gradlew evalGate   # Gate 1: fails if extraction precision < 0.85
./gradlew check      # both, as CI runs them
```

CI (`.github/workflows/ci.yml`) runs `./gradlew check` on every push and pull request.

## Phase 1 status

| # | Task | Status |
| --- | --- | --- |
| 1 | Repo, CI, build and test setup | Done |
| 2 | Docker Compose stack | Written; not yet run |
| 3 | Schema and migrations | Done |
| 4, 5 | Simulated company and 300 labelled messages | Done |
| 6 | Ingestion: gateway, webhook, dedupe | Done |
| 7 | Cheap commitment filter | Done |
| 8 | Extraction with schema and grounding checks | Done |
| 9, 10 | Extraction and Promise workflows | Written; covered by `WorkflowTest` |
| 11 | Slack bot buttons | Not started; workflows already accept confirm, dismiss and kept signals |
| 12 | Daily digest schedule | Written; not yet run |
| 13 | Gate 1 evaluation | Done; passes |

## Honest notes on quality

- The rule-based extractor scores 1.0 precision and recall on the simulated set. That shows the
  pipeline works, not that it understands real chat: most simulated messages come from templates
  the rules were written against. Before Phase 2, label 100+ realistic messages and evaluate the model
  extractor on them (`ExtractionEval` accepts `--extractor llm`).
- The dataset in `src/main/resources/sim/messages.jsonl` is a fixed fixture: 24 hand-written
  messages telling the slip and leave stories, plus 276 generated ones.
- The Postgres repository mirrors the in-memory one but has no integration test yet.
- Times use UTC working hours (reminders at 09:30 India time). Per-person time zones come later.

See `docs/decisions.md` for the main engineering decisions.
