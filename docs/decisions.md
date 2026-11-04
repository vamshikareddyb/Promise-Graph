# Engineering decisions

Short records of choices that shape the code. Newest last.

## 1. Business rules live outside Temporal and Spring

The `domain` and `extraction` packages import nothing from Temporal, Spring, a database or the
network. Workflows and activities call into them. This keeps the rules fast to test, lets the
same logic run in the evaluation, and keeps workflow code small and deterministic.

## 2. Deterministic IDs everywhere

Promise IDs hash the tenant, source message, owner and normalised text. Extraction batch IDs
hash the sorted message IDs. Starting the same thing twice is therefore harmless, which is the
base of the system's idempotency.

## 3. One workflow owns each promise's state

Only `PromiseWorkflow` changes a promise's status. The database row is created once
(insert-if-absent) and then updated only with newer versions from that workflow, so there is
never a race between two writers.

## 4. Every side effect is safe to repeat

- Messages are claimed per batch; a retry by the same batch is allowed, another batch is not.
- Events are written once per `promiseId:version` key.
- Notifications are sent once per key. A crash between sending and recording can repeat one
  message; we accept that over losing it.
- Signals carry a `sourceId`; the workflow ignores repeats.

## 5. Model output is never trusted

Model responses must match the expected shape, every commitment must quote words that exist in
its source message, and owners must exist in the directory. Malformed output is retried;
ungrounded items are dropped; rejected requests are not retried.

## 6. Model calls get their own task queue

Extraction runs on `pg-llm` with a concurrency cap, so a slow or rate-limited model backs up
only that queue.

## 7. Confidence decides how much to ask

High-confidence promises activate after a quiet period unless dismissed; low-confidence ones
need confirmation and expire after three days without it. This keeps prompts few without
tracking things people never promised.

## 8. Plain records across Temporal boundaries

Workflow and activity inputs are Java records with ISO strings for dates, which Temporal's
default Jackson converter handles without custom configuration. Records expose no is-getters,
so nothing extra is serialised.

## 9. Java, Gradle and Spring Boot

The project moved from Python to Java 21 after Phase 1. Gradle (Kotlin DSL) builds it; Spring
Boot provides configuration, the webhook endpoint and JDBC. Temporal workers are wired by hand
rather than through the Spring Boot starter, so the same `Workers.register` code runs in the
application and in the time-skipping workflow tests.

## 10. Links are suggestions until a person confirms them

A wrong dependency causes false alarms, which teach people to ignore the system. So links found in
text start as suggestions, the owner confirms or rejects them in the inbox, and only confirmed
links drive ripple alerts. A rejected link is never suggested again.

## 11. Ripple runs as its own workflow, started by the promise that changed

When a promise's date moves later or it is missed, its workflow starts a `RippleWorkflow` as a
child with an "abandon" parent-close policy, so the ripple finishes even if the promise closes
right away. Its ID includes the promise's version, so each change ripples once. Affected promises
learn about it through a signal and change their own state; nothing else writes to them.

## 12. New workflow behavior is versioned

Starting a ripple adds commands to `PromiseWorkflow`'s history. It is wrapped in
`Workflow.getVersion("ripple-on-slip", …)`, so promise workflows that started before Phase 2
replay without it and keep running safely. Future changes to running workflows follow the same rule.

## 13. Handoffs move ownership, then move it back

A handoff changes the promise's owner on the first day away and back the day after, through a
`reassign` signal, rather than adding a second "delegate" field. Reminders and inbox items then
simply go to whoever owns the promise. On return, a promise goes back only if the cover still owns
it, so a later reassignment is never undone.

## 14. The agent plans; rules check and back it up

The handoff agent only gets read-only tools, a step limit, and a `propose` tool that refuses
anyone the rules consider unavailable. Promise text is treated as data. If the model fails,
stops early or skips a promise, the rule-based plan fills the gap, so a handoff never stalls on
the model. Tests drive the loop with a scripted model.

## 15. One web inbox for every question

Every notification is stored as an inbox item with the buttons that answer it. A button press
becomes a signal whose source is the item and action, so a double click acts once, and the item
is marked done only after the signal is delivered.

## 16. Records crossing Temporal have exactly one constructor

Jackson builds records through their canonical constructor. Extra constructors on those records
were replaced with named factories (`Notification.about`), so deserialization never has to choose.

## 17. A negotiation starts only when a slip crosses team or customer lines

Every slip still warns the owners downstream (Phase 2). A `NegotiationWorkflow` starts only when
the ripple reaches a promise made to a customer, or promises in at least two teams
(`PG_NEGOTIATION_MIN_TEAMS`). A slip inside one team is left to its owners; asking leads to
approve plans for every small slip would train them to ignore the requests. A slip with no new
date has nothing to plan around, so it only warns.

A negotiation is started by an activity as a workflow of its own, not as a child of the ripple:
it runs for days after the ripple has finished, and nothing about it belongs to the ripple.

## 18. One verifier for every plan, whoever proposed it

Rule plans and agent plans pass through the same `PlanVerifier`: real open promises only, no date
in the past, nobody's date pulled earlier than they promised, the late promise brought forward
only if someone is added to help, nobody over the workload limit or asked while away, and nothing
due before the work it waits on unless it delivers a first part. What a promise is and when it is
due always come from storage; the agent only names promise IDs. The agent can call `check_plan`
before `submit_plan`, so it learns the rules from the verifier's answers.

## 19. Approval is unanimous, and disagreement is a new round

Every affected team's lead approves. One plan chosen by all of them is applied. If they all reject,
the next round never offers those plans or those helpers again, and their reasons go to the
planner. If they split, the next round offers only the plans someone chose. After three rounds,
or at the deadline, the negotiation stops and changes nothing. Owners can still move their own
dates, as before.

## 20. An approved plan is applied as a saga

Each move is one activity that sends one signal, keyed by negotiation, round, plan and step, so a
retry never applies a move twice. The plan is checked again against the current state first. If
a step fails because a promise has closed, the earlier steps are undone in reverse order with
their own keyed signals, and everyone is told. Changes a plan makes don't ripple again: the plan
already covered every promise downstream, and a ripple would start a second negotiation about
the same slip.

## 21. Teams answer capacity questions through workflow updates

Each team has a long-running `TeamWorkflow` that holds its workload, refreshed daily, and the
people that open negotiations have asked for. Before plans reach approvers, the negotiation asks
each team, through a Temporal update, to hold the people a plan adds. Two negotiations running at
once therefore can't both count on the same free person, and every answer is in the team's
history. Holds for plans not chosen are released; the chosen plan's holds last until the late
promise's new date.

## 22. A dependent is never likely before the work it waits on

Ripple keeps each dependent's gap after its upstream promise. After an earlier slip that gap can
be negative (the upstream was already later than the dependent), which gave dates before the
upstream finished. The gap now has a floor of zero.

## 23. Failures end up in front of a person, not in a log

A batch the model can't read after its retries becomes a dead letter holding its messages, which
an operator can retry under the same workflow ID (so the messages are still its own to claim).
An hourly `ReconcileWorkflow` checks that every open promise has a running workflow; one that
failed, was terminated or never started is restarted from the stored promise, and one that
finished while the store still shows it open is reported rather than guessed at.

## 24. Idempotency is tested, not just designed

`IdempotencyTest` runs every activity with a side effect twice and compares everything a person
could see. The audit found one real bug: a retried hand-back reported an already-returned promise
as closed. docs/idempotency.md lists every activity and why a repeat is safe.

## 25. Sensitive details are removed before they enter a workflow

Workflow inputs and activity results live in Temporal's history, so redaction happens at the two
entry points (the webhook gateway and the Gmail fetch), not later. Email addresses keep their
domain because it can show a message is about a customer.

## 26. Visibility is decided on the server, by relationship

A person sees promises they own or were made to them, ones linked to theirs, their team's if they
lead it, ones in a negotiation they approve, and ones they handed over while away. Teammates don't
see each other's promises by default, which is what lets someone covering see only what was
handed to them. Quotes from messages are shown only to the owner and the person promised. Until
sign-in exists the viewer comes from a header, so the server stays on localhost.

## 27. Metrics go through Micrometer, including Temporal's

The app's counters and gauges and the Temporal SDK's metrics share one Prometheus endpoint on a
separate management port. The dashboard and alert rules live in `ops/` and start with
`docker compose --profile observability up -d`. Tracing is not set up yet.

## 28. Ripples start from an activity too

Ripples are now started the same way as negotiations (from an activity, as workflows of their own). A missed
promise closes right after starting its ripple, so the ripple always outlived its parent; under
load the time-skipping test server stalled on those orphaned children. Promise workflows started
before Phase 4 keep the child path through `getVersion("ripple-on-slip", …)` version 1.

