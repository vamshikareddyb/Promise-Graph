# Reading your Gmail

Promise Graph can read a personal Gmail account and track the promises in it, both ones you make
in emails you send and ones other people make in emails you receive.

It asks for **read-only** access. It cannot send, delete or change anything. It only keeps each
promise and the sentence that contains it, never whole emails. The sign-in token stays in a file
on your own computer.

Setup takes about 15 minutes, once.

## 1. Create a Google Cloud project

1. Go to <https://console.cloud.google.com/> and sign in with the Gmail account to read.
2. Open the project picker at the top, choose **New project**, name it `promise-graph`, and create it.
3. Make sure the new project is selected in the project picker.

## 2. Turn on the Gmail API

1. Open **APIs & Services → Library**.
2. Search for **Gmail API**, open it and click **Enable**.

## 3. Set up the consent screen

1. Open **APIs & Services → OAuth consent screen** (on newer consoles: **Google Auth Platform → Branding**).
2. Choose **External** as the user type.
3. Fill in an app name (`Promise Graph`) and your email for the support and developer contacts.
4. Under **Audience** (or **Test users**), add your own Gmail address as a test user.
5. Leave the app in **Testing**. You do not need Google's verification for your own account.

While the app is in Testing, Google expires the sign-in after 7 days. Run step 5 again when that
happens; the sync picks up where it stopped.

## 4. Create the OAuth client

1. Open **APIs & Services → Credentials** (or **Google Auth Platform → Clients**).
2. Choose **Create credentials → OAuth client ID**, application type **Desktop app**.
3. Download the JSON file and save it as:

   ```
   ~/.promise-graph/gmail-credentials.json
   ```

   Keep this file private. The repository's `.gitignore` blocks credential files, but store it
   outside the project folder anyway.

## 5. Sign in once

From the project folder:

```bash
./gradlew gmailAuth
```

It prints a Google link. Open it, choose your account, and allow read-only access. Google may warn
that the app is unverified: that is your own app in Testing, so choose **Continue**. The terminal
then shows `Signed in as …` and saves the token to `~/.promise-graph/gmail-token.json`.

## 6. Run Promise Graph with Gmail

```bash
docker compose up -d
PG_GMAIL_ENABLED=true ./gradlew bootRun
```

On start it reads the last 2 days of mail, then checks for new mail every minute. In the Temporal
UI (<http://localhost:8080>) you will see a `gmail-sync-demo` workflow, an extraction workflow per
batch of emails, and a `promise-…` workflow for each promise found.

| Setting | Default | Meaning |
| --- | --- | --- |
| `PG_GMAIL_ENABLED` | `false` | Turn Gmail reading on |
| `PG_GMAIL_CREDENTIALS` | `~/.promise-graph/gmail-credentials.json` | OAuth client file from step 4 |
| `PG_GMAIL_TOKEN` | `~/.promise-graph/gmail-token.json` | Token saved in step 5 |
| `PG_GMAIL_POLL_INTERVAL` | `60s` | How often to check for new mail |
| `PG_GMAIL_BACKFILL_DAYS` | `2` | How far back the first sync reads (1–30) |

## What gets read

- **Read:** your sent mail and your inbox's Primary category.
- **Skipped:** spam, trash, drafts, chats, the Promotions, Social, Updates and Forums tabs, mailing
  lists, and automated senders such as `noreply@…`.
- **From each email:** only the sender's own words. Quoted replies, forwarded history and
  signatures are removed, so an old promise quoted in a reply is not counted again.

## Stopping access

- Stop the app, or set `PG_GMAIL_ENABLED=false`.
- Remove access completely at <https://myaccount.google.com/permissions> and delete
  `~/.promise-graph/gmail-token.json`.
