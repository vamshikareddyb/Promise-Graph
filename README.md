# Promise Graph

Promise Graph detects the commitments people make in chat and email, like "I'll send it by
Friday", and tracks each one as a durable Temporal workflow. It sends reminders, checks on the due
date, warns the people who depend on a promise when it slips, has the affected leads agree a
recovery plan when a slip reaches a customer or another team, and covers someone's promises while
they are away.

Every day, people at work make small commitments that are never written down anywhere. When one is
forgotten or quietly slips, the people depending on it find out too late. Promise Graph follows
through on them, without anyone changing how they work.

**Status:** all four build phases are implemented: capture and track, Gmail reading, links and
handoffs, recovery plans, and hardening (idempotency, dead letters and a repair sweep, metrics,
load and failure tests, replay tests, privacy). What remains is a pilot with real teams.
[docs/write-up.md](docs/write-up.md) is the one-page summary; [docs/demo.md](docs/demo.md) is a
five-minute demo.

## How it works

```
email or chat -> GmailSyncWorkflow ---> ExtractionWorkflow ---------------> PromiseWorkflow (one per promise)
                 or webhook gateway     claim, filter, extract, match       reminders, due-day check,
                                        suggest links, spot absences        slip, kept, missed
                                               |                                   | slips or misses
                                               v                                   v
                                        HandoffWorkflow                     RippleWorkflow
                                        plan cover, ask, move work          warn everyone downstream
                                        out and back                               | a customer or 2+ teams affected
                                                                                   v
                                                      TeamWorkflow <------ NegotiationWorkflow
                                                      (one per team)       plans, leads approve, apply
                                                      holds helpers        as a saga, undo on failure

people read and answer everything in the web inbox (http://localhost:8787)
```

- **Links.** "I'll build the dashboard once the API is live" suggests that the dashboard depends on
  the API promise. The owner confirms or rejects the suggestion in their inbox; only confirmed
  links count.
- **Ripple.** When a promise slips or is missed, a `RippleWorkflow` follows confirmed links and
  tells each affected owner their likely new date, so they can keep their date or move it. It
  also spots promises that wait on each other in a loop.
- **Recovery plans.** When a slip reaches a promise made to a customer, or promises in two or more
  teams, a `NegotiationWorkflow` proposes two or three plans: accept the new dates, add a helper to
  win back time, or keep customer dates by delivering a first part. Every plan passes a verifier
  (real promises, valid dates, nothing due before the work it waits on, nobody over their workload)
  and each team's `TeamWorkflow` confirms it can spare the people a plan adds. The affected team
  leads choose in their inbox or on the Plans page. When they all choose the same plan, it is
  applied step by step; if a step fails, the steps already made are undone. Rejected plans are
  never offered again, and after three rounds or at the deadline nothing changes.
- **Handoffs.** "I'm on leave from 16 Nov to 27 Nov" (or the form in the web app) starts a
  `HandoffWorkflow`. Three days before, it plans who should cover each open promise, asks them,
  and escalates to the team lead if someone declines. On the first day away, accepted promises
  move to their cover; the day after the absence, they move back with a summary of what changed.
- **The agents.** By default, handoff cover and recovery plans come from transparent rules. With
  `PG_HANDOFF_PLANNER=agent` or `PG_NEGOTIATION_PLANNER=agent`, Claude plans instead, using
  read-only tools, with a step limit and the same checks as the rules. Anything it doesn't settle
  falls back to the rules.

| Package | What it holds |
| --- | --- |
| `domain` | Promise rules: lifecycle, IDs, timing, matching. No Temporal, Spring or I/O. |
| `extraction` | Commitment filter, date parsing, rule-based extractor; `model` validates and calls the model. |
| `links` | Link suggestions and the ripple calculation. Pure logic. |
| `handoff` | Absence detection, cover planning, the directory; `agent` is the Claude planner. |
| `recovery` | Recovery plans: the rule planner, the option agent, the verifier. Pure logic. |
| `inbox` | Inbox notifications and turning button presses into workflow signals. |
| `ingestion`, `gmail` | The webhook gateway and the read-only Gmail sync. |
| `temporal` | Workflows, activities, worker registration. |
| `store` | In-memory and Postgres stores. |
| `web` | The JSON API; the page itself is `src/main/resources/static/index.html`. |
| `sim`, `eval` | The simulated 20-person company and the Gate 1, 2 and 3 evaluations. |
| `ops`, `privacy` | Dead letters, the repair sweep and the audit log; redaction and who-sees-what rules. |

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
links, a dependency graph, handoffs and recovery plans. The Temporal UI shows every workflow and its history.

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
| `PG_NEGOTIATION_ENABLED` | `true` | `false`: slips only warn, as in Phase 2 |
| `PG_NEGOTIATION_PLANNER` | `rules` | `agent` lets Claude propose recovery plans (`ANTHROPIC_API_KEY`) |
| `PG_NEGOTIATION_MIN_TEAMS` | `2` | A slip affecting this many teams starts a negotiation (a customer promise always does) |
| `PG_NEGOTIATION_CAPACITY` | `8` | The most open promises a plan may give one person |
| `PG_NEGOTIATION_APPROVAL_HOURS` | `72` | How long leads have to choose, per round (reminder after 24) |
| `PG_CUSTOMERS` | `Acme,Globex,Initech` | Customer names; a promise naming one counts as made to a customer |
| `PG_ADMINS` | empty | People allowed on the Ops page besides team leads (e.g. yourself with personal Gmail) |
| `PG_MANAGEMENT_PORT` / `PG_MANAGEMENT_ADDRESS` | `8788` / `127.0.0.1` | Health and Prometheus metrics; use `0.0.0.0` on Linux for the Prometheus container |
| `PG_GMAIL_ENABLED` | `false` | Read a Gmail account (see the Gmail guide) |
| `PG_WEBHOOK_TOKEN` | empty | Required to accept `POST /messages` |
| `PG_CONFIDENCE_THRESHOLD` | `0.8` | Promises at or above this activate without confirmation |
| `SERVER_ADDRESS` | `127.0.0.1` | The web app has no login yet, so it only listens on this computer |
| `TEMPORAL_ADDRESS` | `localhost:7233` | Temporal frontend |

With `PG_STORE=jdbc`, a fresh database gets every migration automatically. An existing one needs
the newer migrations applied once, in order, for example:
`docker compose exec -T app-db psql -U promise -d promise_graph < migrations/004_phase4.sql`.

### Operations

- **Ops tab** (leads and `PG_ADMINS`): dead letters to retry or dismiss, the repair sweep, the audit log.
- **Metrics:** `http://localhost:8788/actuator/prometheus`. With
  `docker compose --profile observability up -d`, Prometheus (http://localhost:9090, with the alert
  rules in `ops/prometheus/alerts.yml`) and Grafana (http://localhost:3000, dashboard "Promise Graph").
- **Privacy:** every API call says who is viewing (the web app sends the person chosen at the top
  right) and gets only what that person may see. There is no sign-in yet, so keep the app on localhost.

## Tests and quality gates

```bash
./gradlew test        # unit, workflow (time-skipping server), Postgres (Testcontainers) and Spring tests
./gradlew evalGate    # Gate 1: extraction precision must be at least 0.85
./gradlew evalGate2   # Gate 2: links, ripple, absences and cover picks on the core scenario
./gradlew evalGate3   # Gate 3: simulated leads approve at least half the recovery plans
./gradlew loadTest -Pcount=50000 -PkillAt=0.5   # against a running Temporal: many promises, worker killed halfway
./gradlew check       # all of the above, as CI runs them
```

CI (`.github/workflows/ci.yml`) runs `./gradlew check` on every push and pull request. Besides
unit and workflow tests it runs the idempotency audit (every activity twice), load and failure
tests (300 promises with storage failing after writes; 120 promises with the worker down mid-run
and every workflow rebuilt from its history), and replay tests, including histories recorded from
the Phase 3 code (see [docs/versioning.md](docs/versioning.md)). The Postgres tests need Docker and are
skipped without it.

## Progress

| Phase | Tasks | Status |
| --- | --- | --- |
| Setup and Phase 1 | Capture promises, track them, reminders, digests, Gate 1 | Done, except Slack buttons (the web inbox covers them) |
| Gmail | Read sent and received mail from a personal account | Done; awaiting a first run on a real mailbox |
| Phase 2 | Links, ripple alerts, handoffs, the handoff agent, web inbox and app, Gate 2 | Done, except a ticket-system connector |
| Phase 3 | Recovery plans, verifier, option agent, approval, apply-and-undo saga, team workflows, Gate 3 | Done |
| Phase 4 | Idempotency audit, dead letters and sweep, metrics and alerts, load and failure tests, replay tests, privacy, demo | Done, except the pilot (needs real teams: docs/pilot.md) |

## Honest notes on quality

- Both gates pass, but they run on simulated data. Gate 1's messages mostly come from templates the
  rules were written against, and Gate 2 checks the one hand-written story. They catch regressions;
  they don't prove accuracy on real messages. Label realistic messages before trusting either.
- Link suggestions only come from explicit phrases ("once", "after", "waiting on"), and most real
  dependencies are never written that way. The web app lets people add links by hand.
- The ripple assumes each dependent promise needs the same gap after its upstream promise as
  before. It tells owners a likely date; they decide.
- The "add a helper" plan assumes one extra person wins back about half the delay. The plan says
  so; nothing here knows how long the work really takes, so leads judge it.
- Gate 3's leads are simulated: each scenario's rule (for example "Acme's date must hold") was
  written down, and the gate checks whether an offered plan meets it. 6 of 8 scenarios get an
  acceptable plan; the two misses are a full team that can't supply a helper and a delay rule the
  plans miss by a day. It shows the plans cover sensible choices, not that real leads would agree.
- Customer promises are spotted by name (`PG_CUSTOMERS`) or an outside recipient. A CRM connector
  would do this properly.
- Teams, roles and leads come from the simulated company. For a personal Gmail account there is
  no team, so handoffs ask you to choose your own cover.
- The web app has no sign-in. Visibility rules are enforced on the server, but the viewer is
  whoever the browser says, so it listens on localhost only; add authentication before exposing it.
- CI's load test runs 300 promises on the test server. 50,000 against a real server is what
  `./gradlew loadTest` is for; it has not been run yet. On the test server the worker going down is
  modelled by pausing it and rebuilding every workflow from history; `loadTest -PkillAt` stops a real one.
- Metrics and alerts are in place; distributed tracing is not.
- Times use UTC working hours (09:30 India time), and the app runs in UTC whatever the computer's
  time zone, so log times are UTC too. Per-person time zones come later.

See [docs/decisions.md](docs/decisions.md) for the main engineering decisions.
