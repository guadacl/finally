# Windows wrapper for apply-social-idps.sh: creates/updates the Google and Facebook identity providers
# on the running Keycloak (realm "finally") from the values in .env. Idempotent.
#
#   powershell -ExecutionPolicy Bypass -File keycloak\scripts\apply-social-idps.ps1
#   powershell -ExecutionPolicy Bypass -File keycloak\scripts\apply-social-idps.ps1 -Container other-keycloak -EnvFile C:\path\.env
#
# Needs Git Bash (Git for Windows); all the work happens in the .sh script.
param(
    [string]$Container = "finally-keycloak",
    [string]$EnvFile = ""
)
$ErrorActionPreference = "Stop"

$script = Join-Path $PSScriptRoot "apply-social-idps.sh"
$candidates = @(
    (Join-Path $env:ProgramFiles "Git\bin\bash.exe"),
    (Join-Path ${env:ProgramFiles(x86)} "Git\bin\bash.exe"),
    (Join-Path $env:LOCALAPPDATA "Programs\Git\bin\bash.exe")
)
$bash = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $bash) {
    $cmd = Get-Command bash.exe -ErrorAction SilentlyContinue
    # skip the WSL launcher in System32: it does not see Docker Desktop's CLI the same way
    if ($cmd -and $cmd.Source -notmatch "System32") { $bash = $cmd.Source }
}
if (-not $bash) {
    Write-Error "Git Bash not found. Install Git for Windows or run keycloak/scripts/apply-social-idps.sh from a bash shell."
    exit 1
}

$env:KEYCLOAK_CONTAINER = $Container
if ($EnvFile) { $env:ENV_FILE = (Resolve-Path $EnvFile).Path.Replace("\", "/") }
& $bash $script
exit $LASTEXITCODE
