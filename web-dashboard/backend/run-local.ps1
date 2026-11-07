$ErrorActionPreference = 'Stop'
$backendRoot = $PSScriptRoot
Push-Location $backendRoot
$dashboardProcess = $null
try {
    $running = docker inspect mongodb --format '{{.State.Running}}'
    if ($LASTEXITCODE -ne 0 -or $running.Trim() -ne 'true') {
        throw 'The Docker container named mongodb must be running in Docker Desktop.'
    }
    $mongoPing = docker exec mongodb mongosh --quiet --eval 'db.adminCommand({ ping: 1 }).ok'
    if ($LASTEXITCODE -ne 0 -or $mongoPing.Trim() -ne '1') {
        throw 'MongoDB in the mongodb container did not answer its ping.'
    }
    $python = Join-Path $backendRoot '.venv\Scripts\python.exe'
    if (-not (Test-Path -LiteralPath $python)) {
        throw 'The backend Python environment is missing. Create backend\.venv and install requirements.txt first.'
    }

    $dashboardOut = Join-Path $env:TEMP 'surakshanethra-dashboard.log'
    $dashboardErr = Join-Path $env:TEMP 'surakshanethra-dashboard-error.log'
    $dashboardProcess = Start-Process -FilePath $python `
        -ArgumentList @('-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', '8000') `
        -WorkingDirectory $backendRoot -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $dashboardOut -RedirectStandardError $dashboardErr
    Start-Sleep -Seconds 2
    if ($dashboardProcess.HasExited) {
        throw "Local dashboard failed to start. Check $dashboardErr"
    }

    Write-Host 'Local dashboard: http://localhost:8000 (loopback only; not tunneled)'
    Write-Host 'API listener:    http://localhost:8001 (loopback only; API routes only)'
    Write-Host 'Tunnel target:   http://localhost:8001'
    Write-Host 'Press Ctrl+C to stop the API; the local dashboard process will also be stopped.'
    & $python -m uvicorn app.main:api_app --host 127.0.0.1 --port 8001
}
finally {
    if ($dashboardProcess -and -not $dashboardProcess.HasExited) {
        Stop-Process -Id $dashboardProcess.Id -Force -ErrorAction SilentlyContinue
    }
    Pop-Location
}
