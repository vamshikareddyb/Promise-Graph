# Running a pilot

A pilot needs two real teams, so it can't be done from the code alone. This is the checklist.

1. **Choose two teams that depend on each other** (for example platform and analytics) and one
   customer-facing team if possible. Agree a three-week window.
2. **Connect their messages.** Point a chat export or bot at `POST /messages` with
   `PG_WEBHOOK_TOKEN`, or connect a Gmail account (docs/gmail-setup.md). Set `PG_CUSTOMERS` to
   their real customer names.
3. **Replace the directory.** The simulated company in `sim/Company.java` defines teams, roles and
   leads. Put the pilot teams there (or add a directory sync) so visibility, handoffs and approvals
   use the right people.
4. **Add sign-in before anyone else opens the web app.** The viewer is currently whoever the
   browser says; the server enforces the rules, but identity is not verified.
5. **Run with Postgres and the observability profile** (`PG_STORE=jdbc`,
   `docker compose --profile observability up -d`), and watch the Promise Graph dashboard.
6. **Measure**, every week:
   - precision of detected promises (how many dismissed as "not a promise")
   - links confirmed versus rejected
   - negotiations started, approved, expired, and how often an approved plan still slipped
   - handoffs where the proposed cover accepted
   - one question to each person: "did it tell you anything you didn't already know?"
7. **Decide** at the end: which agents earned their keep (extraction, handoff planner, option
   agent) and which should stay rule-based.
