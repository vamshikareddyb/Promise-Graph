# Idempotency audit

Temporal runs an activity again when it can't tell whether the last attempt finished, for example
when a worker dies after the work was done but before it reported back. Every activity with a
side effect must therefore be safe to run twice with the same input. This table lists each one,
what makes it safe, and the test that proves it (`IdempotencyTest` runs each one twice and checks
that nothing anyone can see has changed).

| Activity | Side effect | Why a repeat changes nothing | Test |
| --- | --- | --- | --- |
| `claimMessages` | Claims messages for a batch | A claim is keyed by message; the same batch (workflow ID) re-claims its own | `claimingMessagesRunsInsideAnActivity` |
| `resolveCommitments` | Creates promises, suggests links | Promise IDs are derived from the message, owner and text; inserts never overwrite; a link is suggested once per pair | `promiseActivities` |
| `dispatchActions` | Starts promise workflows, sends signals | Starts use the promise's fixed workflow ID with reject-duplicate; signals carry the message ID, and the workflow ignores a signal it has applied | `promiseActivities` |
| `recordPromise` | Saves a snapshot, appends an event | Saves only a newer version; events are keyed by `promiseId:version` | `promiseActivities` |
| `sendNotification` | Notifies a person | Keyed per notification; a key already recorded is skipped. The inbox insert itself is keyed too, so even a crash between sending and recording leaves one inbox item | `promiseActivities` |
| `sendDigests` | One digest per person per day | Keyed by person and date | `promiseActivities` |
| `applyRipple` | Signals dependents, notifies owners | Signals and notifications are keyed by the change (`promise:cause:newDue`) | `rippleAndHandoffActivities` |
| `scheduleHandoffs` | Records absences, starts handoffs | Absences are keyed by person and start date; the handoff's workflow ID is fixed with reject-duplicate | `rippleAndHandoffActivities` |
| `saveHandoff`, `saveNegotiation` | Replaces a snapshot | A replace with the same value | both |
| `transfer`, `handBack` | Reassigns promises, sends a summary | Reassign signals are keyed by handoff, direction and promise. **Fixed in this audit:** a repeated hand-back used to report a promise that had already moved back as "closed"; it now counts it as returned | `rippleAndHandoffActivities` |
| `startNegotiation` | Starts a negotiation | Fixed workflow ID per slip, reject-duplicate | `NegotiationWorkflowTest` |
| `startRipple` | Starts a ripple | Fixed workflow ID per recorded change, reject-duplicate | `Phase2WorkflowTest`, `LoadAndChaosTest` |
| `supersede` | Signals older negotiations | Keyed by the newer negotiation's ID | `negotiationActivities` |
| `reserve`, `release` | Holds or frees people in team workflows | Reservations are keyed by negotiation, promise and person; releasing what is already free does nothing | `negotiationActivities` |
| `applyMove`, `undoMove` | Changes a promise, notifies people | Keyed by negotiation, round, plan and step (undo adds `:undo`) | `negotiationActivities` |
| `computeRipple`, `precheck`, `shouldNegotiate`, `teamWorkload`, `fetchNewEmails` | None (read-only) | Nothing to repeat | |
| `extract`, `planHandoff`, `proposePlans` | A model call (cost only) | Results are recorded in the workflow history, so a replay never calls the model again; a retried call may cost a second request | |

Workflow-level rules that go with this:

- Every workflow ID is deterministic (`promise-<id>`, `ripple-<id>-v<version>`, `handoff-<id>`,
  `negotiate-<id>-v<version>`, `team-<tenant>-<team>`), so a duplicate event can't start a second copy.
- Signals carry a source ID, and each workflow remembers the ones it has applied (the promise
  workflow carries the last 500 across continue-as-new).
- One known gap: a notifier that sends outside the inbox (Slack, email) could send twice if a
  worker dies between sending and recording. The inbox is safe because its insert is keyed.
