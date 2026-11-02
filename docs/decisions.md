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
