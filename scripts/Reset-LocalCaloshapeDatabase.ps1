[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$databaseName = 'caloshape'
$mysqlPort = 3307
$projectRoot = Split-Path -Parent $PSScriptRoot

if (-not $Force) {
    throw "This permanently deletes every table and row in the local '$databaseName' database. Re-run with -Force to continue."
}

$backendListeners = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($backendListeners) {
    throw 'A backend process is already listening on port 8080. Stop it first, then run this reset command again.'
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker CLI was not found. Start Docker Desktop and make sure the docker command is available.'
}

& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Desktop is not available. Start Docker Desktop, then run this command again.'
}

$mysqlContainers = @(
    & docker ps --filter "publish=$mysqlPort" --format '{{.ID}}' |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
)

if ($mysqlContainers.Count -ne 1) {
    throw "Expected exactly one local MySQL container published on port $mysqlPort, but found $($mysqlContainers.Count)."
}

$mysqlContainerId = $mysqlContainers[0]
$resetSql = @"
DROP DATABASE IF EXISTS ``$databaseName``;
CREATE DATABASE ``$databaseName`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
"@

Write-Host "Resetting local MySQL database '$databaseName' in Docker container $mysqlContainerId..." -ForegroundColor Yellow
& docker exec $mysqlContainerId mysql -uroot -proot -e $resetSql
if ($LASTEXITCODE -ne 0) {
    throw "MySQL reset failed. The '$databaseName' database was not rebuilt."
}

Write-Host 'Database reset complete. Starting the dev backend so Flyway can run V1 and V2...' -ForegroundColor Green
Push-Location $projectRoot
try {
    & .\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'
    if ($LASTEXITCODE -ne 0) {
        throw "The dev backend exited with code $LASTEXITCODE. Check the Flyway output above."
    }
} finally {
    Pop-Location
}
