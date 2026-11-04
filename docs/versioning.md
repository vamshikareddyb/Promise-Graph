# Changing workflow code safely

Promise, handoff, negotiation and team workflows run for days or weeks. When a worker restarts,
Temporal rebuilds each one by replaying its history against the code deployed now, so workflow
code must make the same decisions, in the same order, as the code that wrote the history.

## The rule

Any change that adds, removes or reorders what a workflow does (an activity, a timer, a child
workflow, a signal it sends) in a path that existing workflows may be on is wrapped in
`Workflow.getVersion`:

```java
if (Workflow.getVersion("ripple-on-slip", Workflow.DEFAULT_VERSION, 1) == Workflow.DEFAULT_VERSION) {
    return; // workflows that started before the change carry on as they were
}
startTheRipple();
```

The change IDs used so far: `ripple-on-slip` (Phase 2), `negotiate-after-ripple` (Phase 3) and
`dead-letter-on-extraction-failure` (Phase 4). Code that only runs inside activities, or in a new
workflow type, needs no versioning.

## How CI checks it

`ReplayTest` does two things on every build:

1. It runs one story through every workflow type (extraction, promises that are kept, replanned,
   missed and handed over, a ripple, an applied negotiation, a team workflow, a handoff, a digest
   and a sweep) and replays each history straight away. That catches non-determinism within the
   current code.
2. It replays every history in `src/test/resources/histories/`. These were recorded from earlier
   versions; if one stops replaying, the change needs `getVersion`.

To add histories from the current version, run the tests with `PG_RECORD_HISTORIES=1`. Each
history is printed as `HISTORY <name> <gzip+base64>`; decode it with `ReplayTest.gunzip` and save
it as `src/test/resources/histories/<phase>-<name>.json`.

Remove a history (and its `getVersion` branch) only after every workflow that could be on the old
path has finished; the Temporal UI can list them with a search on the workflow type.

## Not used: worker versioning

Temporal's worker versioning (build IDs) pins each workflow to the worker build it started on.
It avoids `getVersion`, but every old build has to keep running until its workflows finish, which
for promises means weeks. `getVersion` plus replay tests is simpler at this size.
