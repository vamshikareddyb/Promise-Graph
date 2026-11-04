#!/usr/bin/env bash
# Sends the demo story to a running Promise Graph, with dates relative to today.
# Usage: PG_WEBHOOK_TOKEN=demo-token ./scripts/demo.sh [step]
#   step 1: the promises and the leave   step 2: the slip
set -euo pipefail
URL="${PG_URL:-http://localhost:8787}"
TOKEN="${PG_WEBHOOK_TOKEN:?set PG_WEBHOOK_TOKEN to the token the app was started with}"
STEP="${1:-1}"
day() { date -u -d "+$1 days" +%F 2>/dev/null || date -u -v+"$1"d +%F; }
now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
send() { # id channel sender text
  curl -fsS -X POST "$URL/messages" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"id\":\"demo-$1\",\"channel\":\"$2\",\"sender\":\"$3\",\"ts\":\"$(now)\",\"text\":\"$4\"}" >/dev/null
  echo "sent $1: $4"
}
if [ "$STEP" = 1 ]; then
  send api platform neha "I'll ship the data export API by $(day 10)."
  send job analytics dev "I'll build the export ingestion job by $(day 15), once the API is live."
  send acme customer-success vikram "I will send Acme the usage dashboard walkthrough by $(day 17), after the export ingestion job is done."
  send faq product karan "I'll write the launch FAQ for support by $(day 12)."
  send leave product karan "Heads up, I'm on leave from $(day 2) to $(day 6)."
  echo "Wait about 30 seconds for the batch, then confirm the two suggested links in Dev's and Vikram's inboxes."
else
  send slip platform neha "The data export API will slip by two weeks, the auth changes took longer than planned."
  echo "Now open the Plans tab as Ravi, Meera or Priya."
fi
