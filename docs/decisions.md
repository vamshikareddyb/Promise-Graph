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

## 29. Team mode: Google sign-in, and the server decides who is viewing

With `PG_AUTH=google`, people sign in with Google: one consent covers who they are and read-only
Gmail, so signing in also starts reading their mailbox. The viewer comes from the session. A filter
replaces the `X-PG-Viewer` header with the signed-in user, so the API code and its visibility rules
stay the same in both modes while the header can no longer be forged. Refresh tokens are stored
encrypted (AES-256-GCM) with a key from `PG_SECRET_KEY` or a private key file.

## 30. Strict visibility in team mode

Each person sees promises they own, promises made to them, and promises found in their own mailbox.
Leads don't see their team's promises; they see a handover or a recovery plan only when they have to
approve it. The People page (names and teams) is visible to everyone signed in, because choosing
cover needs it.

## 31. One mailbox per person, and the mailbox travels with the message

Each signed-in person has their own Gmail sync workflow. The mailbox is part of each message's ID
(the same email in the sender's and the recipient's mailbox is read once for each of them) and of
its channel, so later steps know whose mailbox "you" is: a request emailed to Neha becomes Neha's
promise, and a promise someone made in an email to Neha records her as its recipient. The same
promise found in two mailboxes is merged by the existing duplicate check.

## 32. Leaving the company: the lead approves, then promises move for good

"My last day is 20 Nov", "I'll be leaving the team on 28 Nov" and "I've resigned…" (the sender's
own, with a date) start a permanent handoff. Unlike leave, nobody is asked until the leaving person's
lead approves the plan; until then the lead can change who takes what. If the last day passes
without approval, nothing moves and the lead is told once; after a late approval, new owners get a
day to answer. Promises move the day after the last day and never come back. The workflow change is
behind `getVersion("departure-approval")`.


## 33. Leave cover is asked for when the leave is entered

Cover used to be planned three days before a leave started, so a leave entered weeks ahead seemed to
do nothing, and a leave starting tomorrow handed over before anyone could answer (late answers were
ignored). Now the plan and the asks go out as soon as the absence is known. On the first day away,
promises made since then are planned and asked for too, and a cover who answers after the start
takes the promise over at once; the lead is told who hasn't answered yet. If someone has no open
promises when the leave is entered, the workflow looks again a few days before. Both changes are
behind `getVersion("plan-on-apply")` and `getVersion("late-accept")`, so running handoffs keep their
old timing.

## 34. The lead assigns: for leave and departures alike

People wanted their lead to decide who takes their work, not to have teammates asked directly. Now,
for leave as for a departure, the plan (each open promise with a suggested teammate) goes to the
lead, who changes who takes what and approves; teammates see nothing of it before then. Once
approved, each promise is simply the new owner's: they're told, not asked. It moves on the first day
away (or at once if approval comes later) and, for leave, back the day after. A lead's own absence,
or that of someone with no lead, goes to an admin. Promises made after approval go to the lead to
assign. Behind `getVersion("lead-assigns")`; the person away can't approve or assign their own.

## 35. Claude by default, and a second agent that drafts replies

Claude is now the default for extraction, handoff planning and recovery planning. A missing
`ANTHROPIC_API_KEY` no longer stops the app: each agent logs that it is using its rules instead.

After extraction records an email's promises, it starts a `DraftReplyWorkflow` per email (through an
activity, so extraction never waits on a model). The workflow gathers context first (the Gmail thread,
read with the existing read-only scope and redacted like everything else, plus promises from the
same thread and open ones between the same two people), then asks Claude for the reply on the model
queue, then saves it. It skips mail the person wrote, mail they already answered in Gmail, and mail
already drafted. If Claude stays unavailable, a deterministic template still leaves a starting point.
Drafts are only ever shown to the mailbox owner and are never sent: sending would need the
`gmail.compose` scope and a person's explicit click, which can come later. The extraction change is
behind `getVersion("draft-replies-after-extraction")`. Postgres needs `migrations/006_drafts.sql`.

## 36. Drafts are asked for, per promise

A page of drafts for every email was more than people wanted. Drafting is now a button on each
promise that came from email ("Draft a follow-up"); the draft opens under the promise. The same
`DraftReplyWorkflow` does the work, keyed by the promise instead of the email, and the prompt
changes with who owes it: an update when it's yours, a check-in when it's theirs. A follow-up may
start from the person's own email, so the "you wrote this" and "already answered" skips only apply
to automatic replies, which are now off unless `PG_DRAFTS_AUTO=true`. The Drafts and Plans tabs
are gone; recovery plans are still chosen from the Inbox.
