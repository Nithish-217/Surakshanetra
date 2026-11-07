$ErrorActionPreference = 'Stop'
$health = Invoke-RestMethod -Uri 'http://127.0.0.1:8001/health' -TimeoutSec 5
if ($health.status -ne 'ok') {
    throw 'The API health check did not return status=ok. Start backend\run-local.ps1 first.'
}
$cloudflared = Get-Command 'cloudflared' -ErrorAction SilentlyContinue
if (-not $cloudflared) {
    throw 'cloudflared is not installed or not on PATH. Install it from https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/downloads/ and retry.'
}
Write-Host 'Starting a temporary Cloudflare Quick Tunnel to the API-only listener.'
Write-Host 'Only API port 8001 is tunneled. The dashboard at localhost:8000 stays local.'
& $cloudflared.Source tunnel --url 'http://127.0.0.1:8001'
