# Promise Graph

Promise Graph detects the commitments people make in chat and email, like "I'll send it by
Friday", and tracks each one as a durable Temporal workflow. It sends reminders, checks on the due
date, warns the people who depend on a promise when it slips, and covers someone's promises while
they are away.

Every day, people at work make small commitments that are never written down anywhere. When one is
forgotten or quietly slips, the people depending on it find out too late. Promise Graph follows
through on them, without anyone changing how they work.

**Status:** Phase 1 (capture and track), Gmail reading and Phase 2 (links, ripple alerts, handoffs
and a web inbox) are implemented. Phase 3 adds recovery plans when a slip puts other work at risk.

## How it works

```
email or chat -> GmailSyncWorkflow ---> ExtractionWorkflow ---------------> PromiseWorkflow (one per promise)
                 or webhook gateway     claim, filter, extract, match       reminders, due-day check,
                                        suggest links, spot absences        slip, kept, missed
                                               |                                   | slips or misses
                                               v                                   v
                                        HandoffWorkflow                     RippleWorkflow
                                        plan cover, ask, move work          warn everyone downstream
                                        out and back

people read and answer everything in the web inbox (http://localhost:8787)
```

- **Links.** "I'll build the dashboard once the API is live" suggests that the dashboard depends on
  the API promise. The owner confirms or rejects the suggestion in their inbox; only confirmed
  links count.
- **Ripple.** When a promise slips or is missed, a `RippleWorkflow` follows confirmed links and
  tells each affected owner their likely new date, so they can keep their date or move it. It
  also spots promises that wait on each other in a loop.
- **Handoffs.** "I'm on leave from 16 Nov to 27 Nov" (or the form in the web app) starts a
  `HandoffWorkflow`. Three days before, it plans who should cover each open promise, asks them,
  and escalates to the team lead if someone declines. On the first day away, accepted promises
  move to their cover; the day after the absence, they move back with a summary of what changed.
- **The handoff agent.** By default the plan uses transparent rules (same role, same team, linked
  work, lightest workload). With `PG_HANDOFF_PLANNER=agent`, Claude plans instead, using read-only
  tools to look up each promise and the available people, with a step limit and checks on every
  choice. Anything it doesn't settle falls back to the rules.

| Package | What it holds |
| --- | --- |
| `domain` | Promise rules: lifecycle, IDs, timing, matching. No Temporal, Spring or I/O. |
| `extraction` | Commitment filter, date parsing, rule-based extractor; `model` validates and calls the model. |
| `links` | Link suggestions and the ripple calculation. Pure logic. |
| `handoff` | Absence detection, cover planning, the directory; `agent` is the Claude planner. |
| `inbox` | Inbox notifications and turning button presses into workflow signals. |
| `ingestion`, `gmail` | The webhook gateway and the read-only Gmail sync. |
| `temporal` | Workflows, activities, worker registration. |
| `store` | In-memory and Postgres stores. |
| `web` | The JSON API; the page itself is `src/main/resources/static/index.html`. |
| `sim`, `eval` | The simulated 20-person company and the Gate 1 and Gate 2 evaluations. |

Built with Java 21, Spring Boot 3.5, Temporal Java SDK 1.40, Postgres and Gradle.

## Run it locally

Requirements: Java 21 and Docker. (Gradle 8.14 does not run on Java 25; see the note below.)

```bash
docker compose up -d       # Temporal, Temporal UI (http://localhost:8080), Postgres
./gradlew bootRun          # worker, web app and webhook on http://localhost:8787

# optional, in a second terminal: replay the simulated company's messages, then exit
./gradlew bootRun --args='--spring.profiles.active=replay --promisegraph.temporal.worker-enabled=false --server.port=0'
```

Open **http://localhost:8787** for the web app. Pick who you are at the top right; the Inbox shows
that person's reminders and questions with buttons to answer them. The other tabs show promises,
links, a dependency graph and handoffs. The Temporal UI shows every workflow and its history.

To read your own Gmail, follow [docs/gmail-setup.md](docs/gmail-setup.md).

On Windows, use `.\gradlew.bat` instead of `./gradlew`. If Java 25 is your default, point Gradle at
Java 21 first: `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21…"`.

### Settings

| Variable | Default | Meaning |
| --- | --- | --- |
| `PG_STORE` | `memory` | `jdbc` uses Postgres (`DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`) |
| `PG_EXTRACTOR` | `rules` | `llm` uses Claude to find promises (`ANTHROPIC_API_KEY`, `PG_MODEL`) |
| `PG_HANDOFF_PLANNER` | `rules` | `agent` lets Claude plan handoff cover (`ANTHROPIC_API_KEY`) |
| `PG_HANDOFF_PREP_DAYS` | `3` | How many days before an absence to plan cover |
| `PG_GMAIL_ENABLED` | `false` | Read a Gmail account (see the Gmail guide) |
| `PG_WEBHOOK_TOKEN` | empty | Required to accept `POST /messages` |
| `PG_CONFIDENCE_THRESHOLD` | `0.8` | Promises at or above this activate without confirmation |
| `SERVER_ADDRESS` | `127.0.0.1` | The web app has no login yet, so it only listens on this computer |
| `TEMPORAL_ADDRESS` | `localhost:7233` | Temporal frontend |

With `PG_STORE=jdbc`, a fresh database gets both migrations automatically. An existing one needs
the Phase 2 migration applied once:
`docker compose exec -T app-db psql -U promise -d promise_graph < migrations/002_phase2.sql`.

## Tests and quality gates

```bash
./gradlew test        # unit, workflow (time-skipping server), Postgres (Testcontainers) and Spring tests
./gradlew evalGate    # Gate 1: extraction precision must be at least 0.85
./gradlew evalGate2   # Gate 2: links, ripple, absences and cover picks on the core scenario
./gradlew check       # all of the above, as CI runs them
```

CI (`.github/workflows/ci.yml`) runs `./gradlew check` on every push and pull request. The
Postgres tests need Docker and are skipped without it.

## Progress

| Phase | Tasks | Status |
| --- | --- | --- |
| Setup and Phase 1 | Capture promises, track them, reminders, digests, Gate 1 | Done, except Slack buttons (the web inbox covers them) |
| Gmail | Read sent and received mail from a personal account | Done; awaiting a first run on a real mailbox |
| Phase 2 | Links, ripple alerts, handoffs, the handoff agent, web inbox and app, Gate 2 | Done |
| Phase 3 | Recovery plans when a slip puts other promises at risk | Next |
| Phase 4 | Hardening, load and failure tests, pilot | Later |

## Honest notes on quality

- Both gates pass, but they run on simulated data. Gate 1's messages mostly come from templates the
  rules were written against, and Gate 2 checks the one hand-written story. They catch regressions;
  they don't prove accuracy on real messages. Label realistic messages before trusting either.
- Link suggestions only come from explicit phrases ("once", "after", "waiting on"), and most real
  dependencies are never written that way. The web app lets people add links by hand.
- The ripple assumes each dependent promise needs the same gap after its upstream promise as
  before. It tells owners a likely date; they decide.
- Teams, roles and leads come from the simulated company. For a personal Gmail account there is
  no team, so handoffs ask you to choose your own cover.
- The web app has no login. It listens on localhost only; add authentication before exposing it.
- Times use UTC working hours (09:30 India time). Per-person time zones come later.

See [docs/decisions.md](docs/decisions.md) for the main engineering decisions.
