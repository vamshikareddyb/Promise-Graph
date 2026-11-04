# Sends the demo story to a running Promise Graph, with dates relative to today.
# Usage (PowerShell):  $env:PG_WEBHOOK_TOKEN = "demo-token"; .\scripts\demo.ps1 1   (then later)  .\scripts\demo.ps1 2
param([int]$Step = 1)
$url = if ($env:PG_URL) { $env:PG_URL } else { "http://localhost:8787" }
if (-not $env:PG_WEBHOOK_TOKEN) { throw "Set PG_WEBHOOK_TOKEN to the token the app was started with" }
function Day([int]$n) { (Get-Date).ToUniversalTime().AddDays($n).ToString("yyyy-MM-dd") }
function Send($id, $channel, $sender, $text) {
  $body = @{ id = "demo-$id"; channel = $channel; sender = $sender; ts = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ"); text = $text } | ConvertTo-Json
  Invoke-RestMethod -Method Post -Uri "$url/messages" -Headers @{ Authorization = "Bearer $($env:PG_WEBHOOK_TOKEN)" } -ContentType "application/json" -Body $body | Out-Null
  Write-Host "sent ${id}: $text"
}
if ($Step -eq 1) {
  Send "api" "platform" "neha" "I'll ship the data export API by $(Day 10)."
  Send "job" "analytics" "dev" "I'll build the export ingestion job by $(Day 15), once the API is live."
  Send "acme" "customer-success" "vikram" "I will send Acme the usage dashboard walkthrough by $(Day 17), after the export ingestion job is done."
  Send "faq" "product" "karan" "I'll write the launch FAQ for support by $(Day 12)."
  Send "leave" "product" "karan" "Heads up, I'm on leave from $(Day 2) to $(Day 6)."
  Write-Host "Wait about 30 seconds for the batch, then confirm the two suggested links in Dev's and Vikram's inboxes."
} else {
  Send "slip" "platform" "neha" "The data export API will slip by two weeks, the auth changes took longer than planned."
  Write-Host "Now open the Plans tab as Ravi, Meera or Priya."
}
