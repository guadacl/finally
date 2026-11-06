<#
  Start Tradex (app + postgres + camunda) with Docker Compose. Idempotent.
    .\scripts\start_windows.ps1            start (builds the image the first time)
    .\scripts\start_windows.ps1 -Build     force a rebuild
    .\scripts\start_windows.ps1 -NoOpen    don't open the browser
#>
param(
    [switch]$Build,
    [switch]$NoOpen
)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error 'docker is not installed or not on PATH.'
    exit 1
}

if (-not (Test-Path '.env')) {
    Copy-Item '.env.example' '.env'
    Write-Warning '.env was missing - created it from .env.example. Set OPENROUTER_API_KEY in .env for AI chat (or LLM_MOCK=true).'
}

$appPort = '8000'
$line = Get-Content '.env' | Where-Object { $_ -match '^\s*APP_PORT\s*=' } | Select-Object -Last 1
if ($line) {
    $value = ($line -split '=', 2)[1].Trim().Trim('"').Trim("'")
    if ($value) { $appPort = $value }
}
$url = "http://localhost:$appPort"

$composeArgs = @('compose', 'up', '-d')
$imageExists = $true
docker image inspect finally-app:latest *> $null
if ($LASTEXITCODE -ne 0) { $imageExists = $false }
if ($Build -or -not $imageExists) { $composeArgs += '--build' }

Write-Host 'Starting Tradex...'
& docker @composeArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error 'docker compose up failed.'
    exit 1
}

Write-Host -NoNewline "Waiting for $url/api/health "
for ($i = 0; $i -lt 90; $i++) {
    try {
        $r = Invoke-WebRequest -Uri "$url/api/health" -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200) {
            Write-Host ' ok'
            Write-Host ''
            Write-Host "Tradex is running:  $url"
            Write-Host 'Camunda Operate:     http://localhost:8081  (demo / demo)'
            if (-not $NoOpen) { Start-Process $url }
            exit 0
        }
    } catch {
        # not up yet
    }
    Write-Host -NoNewline '.'
    Start-Sleep -Seconds 2
}

Write-Host ''
Write-Error 'Tradex did not become healthy in time. Check the logs with: docker compose logs app'
exit 1
