# XrMail backend — Windows startup script
# Run from the project root: .\backend\start.ps1

$envFile = "$PSScriptRoot\.env"

if (-Not (Test-Path $envFile)) {
    Write-Error ".env file not found at $envFile"
    Write-Host "Copy backend\.env.example to backend\.env and fill in your values."
    exit 1
}

# Load .env — skip blank lines and comments
Get-Content $envFile | Where-Object {
    $_ -notmatch '^\s*#' -and $_ -match '='
} | ForEach-Object {
    $name, $value = $_ -split '=', 2
    $name  = $name.Trim()
    $value = $value.Trim()
    [System.Environment]::SetEnvironmentVariable($name, $value, 'Process')
    Write-Host "  Set $name"
}

Write-Host ""
Write-Host "Starting XrMail backend..."
Set-Location $PSScriptRoot\..
& .\gradlew.bat :backend:run
