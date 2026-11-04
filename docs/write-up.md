# Promise Graph: write-up

## The problem

Much of the coordination inside a company is made of small commitments in chat and email: "I'll
send the deck by Friday", "I'll build the dashboard once the API is live". Nobody writes them
down. When one slips quietly, the people who depend on it find out on the day they needed it, and
when someone goes on leave, their open commitments go with them.

## What Promise Graph does

- **Finds promises** in messages (rules by default, Claude optionally) and gives each one a durable
  Temporal workflow with reminders, a check on the due date, and a record of every change.
- **Links promises that depend on each other** from phrases like "once" and "after", confirmed by
  the owner.
- **Warns everyone downstream** the moment a promise slips, with their likely new date.
- **Negotiates recovery** when a slip reaches a customer or another team: two or three checked
  plans, approval by every affected lead, applied as a saga that undoes itself on failure.
- **Covers absences**: plans who covers each open promise, asks them, moves the work on the first
  day away and back the day after.

## How it is built

Java 21, Spring Boot, Temporal and Postgres. Each kind of long-running work is a workflow:
`PromiseWorkflow` (one per promise), `RippleWorkflow`, `NegotiationWorkflow`, `HandoffWorkflow`,
`TeamWorkflow` (one per team, answering capacity questions through updates), plus extraction,
digest, Gmail sync and reconciliation. Business rules (lifecycle, matching, ripple dates, plan
checks, visibility) are plain Java with no Temporal or Spring in them, so they are tested directly.

Agents are used where judgement helps and are always fenced in: read-only tools, a step limit,
every proposal checked by the same rules a person would apply, and a rule-based fallback, so the
system never stalls on a model.

## Making it dependable

- **Idempotent everywhere.** Deterministic IDs for every workflow, source IDs on every signal,
  keyed notifications and events. Every side-effecting activity has been run twice in a test to
  prove a repeat changes nothing (docs/idempotency.md).
- **Failures go somewhere.** Batches the model can't read become dead letters to retry. An hourly
  sweep restarts any promise workflow that died, and reports any that disagree with the store.
- **Safe to change.** Behaviour added to running workflows is versioned, and CI replays recorded
  histories against the current code (docs/versioning.md).
- **Tested under failure.** Hundreds of promises with storage writes failing after they succeed,
  and the worker going down mid-run: every promise ends in the right state, every version is recorded
  once, every notification is sent once. `./gradlew loadTest` runs the same check against a real
  Temporal server at any size, for example 50,000 promises; that larger run hasn't been done yet.
- **Observable.** Prometheus metrics from the app and the Temporal SDK, a Grafana dashboard and
  alert rules (`ops/`).
- **Private by default.** Contact details, card numbers and secrets are removed from messages
  before they enter a workflow. People see their own promises, those linked to theirs, and their
  team's if they lead it; someone covering sees only what was handed to them. Every change made
  through the app is in an audit log.

## Results

| Check | Result |
| --- | --- |
| Gate 1: extraction precision on the labelled set | 1.00 (gate 0.85) |
| Gate 2: links, ripple, absences and cover picks on the core story | all match |
| Gate 3: simulated leads approve a recovery plan | 6 of 8 scenarios (gate 4 of 8); every plan names real promises; 10 of 10 bad plans caught |
| Tests | unit, workflow (time-skipping), Postgres, replay, idempotency, load and chaos, all in CI |

## What it doesn't prove yet

All three gates run on simulated data written alongside the rules, so they catch regressions but
don't measure accuracy on real messages. There is no sign-in yet (the viewer is whoever the browser
says). The directory is the simulated company. Ripple and recovery dates assume work takes as long
after its upstream as before, and that a helper wins back half a delay; both are shown to people
as assumptions. A pilot with two real teams is the next step (docs/pilot.md).
