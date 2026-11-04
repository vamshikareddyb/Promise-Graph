# Five-minute demo

One story shows everything: promises found in messages, a dependency, a slip that reaches a
customer, three leads agreeing a recovery plan, a colleague covering during leave, and the work
surviving a crash.

## Before you start (once)

Java 21 and Docker. In two terminals:

```bash
docker compose up -d                                   # Temporal, its UI, Postgres
PG_WEBHOOK_TOKEN=demo-token PG_STORE=jdbc ./gradlew bootRun
```

On Windows PowerShell: `$env:PG_WEBHOOK_TOKEN="demo-token"; $env:PG_STORE="jdbc"; .\gradlew.bat bootRun`.

Open the web app at http://localhost:8787 and the Temporal UI at http://localhost:8080 side by side.

## The script

| Time | Do | Say |
| --- | --- | --- |
| 0:00 | Run `./scripts/demo.sh 1` (or `.\scripts\demo.ps1 1`). | "Five ordinary chat messages. Nobody fills in a tracker." |
| 0:30 | Promises tab, viewing as Ravi (a lead). | "Each promise is now a durable workflow with its own timers. Ravi sees his team's; teammates don't see each other's." |
| 0:50 | Viewing as Dev: confirm the suggested link in the inbox. Same as Vikram. Show the Graph tab. | "'Once the API is live' became a suggested dependency. People confirm; nothing is assumed." |
| 1:30 | Viewing as Nikhil: accept covering Karan's FAQ. Handoffs tab as Nikhil, then as Karan. | "Karan's leave was read from a message. Nikhil was picked by role and workload, and sees only the item handed to him." |
| 2:10 | Run `./scripts/demo.sh 2` (the slip). Wait ~30 s. Inbox as Dev. | "The API slips two weeks. Dev is told his likely new date, with the reason." |
| 2:40 | Plans tab as Ravi. Walk through plans A, B, C and the dropped plan. | "It reached a customer promise in three teams, so the leads get three checked plans with their costs." |
| 3:20 | **Stop the app (Ctrl+C).** Show the negotiation still waiting in the Temporal UI. Start the app again. | "The server died mid-negotiation. Nothing is lost: the workflow is waiting exactly where it was." |
| 3:50 | Approve plan B as Ravi, Meera and Priya. Inbox as Arjun and Vikram. Promises tab. | "Unanimous, so it's applied step by step, and undone if a step fails. Everyone gets just their change." |
| 4:30 | Temporal UI: open `negotiate-…`, show the history. Ops tab as Ravi: audit log. | "Every decision is in the workflow history and the audit log, so a retro can replay exactly what happened." |

## If something doesn't appear

- Messages are batched for up to 30 seconds; refresh after that.
- A suggested link needs both promises to exist first, which is why step 1 sends them in order.
- Check the app log for `to <person>: …` lines: every notification is logged as it is sent.

## Recording

To record the demo, run through the script once and record the screen with the browser and the
Temporal UI side by side (on Windows, Win+Alt+R records the active window; OBS records both).
Nothing in the script depends on the clock except the 30-second batch wait.
