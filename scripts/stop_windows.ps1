<#
  Stop Tradex. Containers are removed; volumes (database, Camunda data) are kept. Idempotent.
#>
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error 'docker is not installed or not on PATH.'
    exit 1
}

docker compose down
if ($LASTEXITCODE -ne 0) {
    Write-Error 'docker compose down failed.'
    exit 1
}
Write-Host 'Tradex stopped. Data volumes are preserved.'
