# Team mode: everyone signs in with Google

In team mode, any number of people use Promise Graph. Each person signs in with their Google account,
the app reads **their own** Gmail (read-only), and each person sees only their own promises. When
someone is on leave or leaves the company, their open promises go to a teammate, and the new owner
sees them in their own promise graph with a note saying why.

| | Personal Gmail (`PG_GMAIL_ENABLED=true`) | Team mode (`PG_AUTH=google`) |
| --- | --- | --- |
| Who uses it | You | Anyone who signs in |
| Mail read | One mailbox (`gradlew gmailAuth`) | Everyone's own mailbox, from their sign-in |
| Who sees what | You see everything | Each person sees their own promises only |
| Cover for leave and departures | Not useful (no team) | From the People page |

## 1. Google Cloud: a web sign-in client

Use the same Google Cloud project as for Gmail (see [gmail-setup.md](gmail-setup.md)).

1. **APIs & Services → Credentials → Create credentials → OAuth client ID.**
2. Application type: **Web application**.
3. Under **Authorized redirect URIs**, add `http://localhost:8787/login/callback`.
4. Create it, then **Download JSON** and save it as `~/.promise-graph/gmail-credentials.json`
   (on Windows: `C:\Users\<you>\.promise-graph\gmail-credentials.json`). It replaces the desktop
   client file; `gradlew gmailAuth` isn't used in team mode.
5. **OAuth consent screen → Audience → Test users:** add the Google address of **everyone** who will
   sign in. While the app is in Testing, Google only lets listed test users sign in (up to 100), and
   a sign-in lasts 7 days before Google asks again.

## 2. Start the app in team mode

```powershell
$env:PG_STORE = "jdbc"
$env:PG_AUTH = "google"
.\gradlew.bat bootRun
```

Don't set `PG_GMAIL_ENABLED`; team mode reads each person's mailbox after they sign in. An existing
database needs the new tables once:

```powershell
Get-Content migrations\005_team.sql | docker compose exec -T app-db psql -U promise -d promise_graph
```

Open http://localhost:8787 and click **Sign in with Google**. **The first person to sign in becomes
an admin.**

On Google's consent screen, each permission has its own checkbox. **Tick "View your email messages
and settings"** (or "Select all"). Without it the app can't read the mailbox, so it asks you to sign in
again. If access is withdrawn later, the page shows a banner, and that person's mail reading waits
(instead of stopping) until they sign in again.

Stored sign-ins are encrypted. The key is created at `~/.promise-graph/secret.key` on first start,
or set it yourself with `PG_SECRET_KEY` (32 random bytes, base64). If the key is lost, everyone just
signs in again.

## 3. Fill in the People page

Everyone who signs in appears on the **People** tab. An admin sets each person's:

- **Team:** cover is only suggested from the same team. People with no team can't cover anyone.
- **Role:** the same role is preferred, then the lightest workload.
- **Lead:** when someone in their team takes leave or leaves the company, the lead sees all their
  open promises, assigns each one to a teammate and approves. A lead's own leave goes to an admin.
- **Admin:** admins edit this page and see the Ops page.
- **Active:** untick it once someone has left, so they're no longer suggested as cover.

## 4. Leave and leaving the company

| Someone writes | What happens |
| --- | --- |
| "I'm on leave from 16 Nov to 27 Nov" | Straight away, their **lead** sees every open promise (with a suggested teammate for each), changes who takes what on the Handoffs page and approves. Teammates get nothing before that. After approval each new owner is told; on 16 Nov the promises move to them, noted "Covering for Neha while they're on leave (16–27 Nov)". On 28 Nov they move back. Promises made after approval go to the lead to assign. |
| "My last day is 20 Nov" / "I'll be leaving the team on 28 Nov" / "I've resigned; my notice period ends on 30 Nov" | The same: the **lead assigns and approves**. The day after the last day, promises move **for good**, noted "Taken over from Neha, who left on 21 Nov". |

Only the sender's own announcement counts ("Neha's last day is Friday" from someone else doesn't),
and a departure needs a date. A lead or the person can also plan either one by hand on the Handoffs
page (tick "leaving the company" for a departure).

## Using it with colleagues on other computers

By default the app listens only on your own computer, so you can try team mode yourself with two
Google accounts: one in a normal browser window, one in a private window. For colleagues on other
computers, it has to run somewhere they can all reach. Google requires HTTPS for any sign-in address
other than `localhost`, so:

1. Run it on a server behind HTTPS (for example a reverse proxy such as Caddy or nginx with a certificate).
2. Set `SERVER_ADDRESS=0.0.0.0` and `PG_AUTH_REDIRECT_URI=https://your-host/login/callback`.
3. Add that same address as an authorized redirect URI on the Google OAuth client.

## What team mode doesn't do yet

- Requests and promises in *chat* (the webhook) aren't tied to anyone's sign-in; team mode is about email.
- A Google Workspace admin could skip per-person consent with domain-wide delegation; that isn't built.
- Promises made to someone are visible to them, but promises made to someone who never signs in are
  only seen by the person who made them.
