#!/usr/bin/env pwsh
# Publish a catalogue release through the peer-to-peer DHT network.
#
# This script:
#   1. Reads a catalog.json file from the same directory
#   2. Builds a signed release using the catalog-publisher tool
#   3. Seeds and publishes it to the DHT
#
# Usage: .\publish-catalog.ps1 -SeedFile <path> [-Hours <h>] [-Version <n>] [-MinVersionCode <code>]
#
# Example:
#   .\publish-catalog.ps1 -SeedFile C:\my-publisher.seed
#
# The catalog.json must be in the same directory as this script.

param(
    [Parameter(Mandatory=$true)]
    [string]$SeedFile,

    [string]$Catalog = "",
    [int]$Version = 1,
    [int]$Hours = 24,
    [int]$MinVersionCode = 0,
    [string[]]$Tracker = @()
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $ScriptDir)

if ($Catalog -eq "") {
    $Catalog = Join-Path $ScriptDir "catalog.json"
}

$ReleaseDir = Join-Path $ScriptDir ".release"

# Verify inputs
if (-not (Test-Path $Catalog)) {
    Write-Error "catalog not found at $Catalog"
    exit 1
}

if (-not (Test-Path $SeedFile)) {
    Write-Error "seed file not found at $SeedFile"
    exit 1
}

# Clean and prepare release directory
if (Test-Path $ReleaseDir) {
    Remove-Item $ReleaseDir -Recurse -Force
}
New-Item $ReleaseDir -ItemType Directory -Force | Out-Null

Write-Host "Building catalogue release..."
Write-Host "  catalog:  $Catalog"
Write-Host "  version:  $Version"
Write-Host "  seed:     $SeedFile"

# Build the release
$BuildArgs = @(
    "build",
    "--catalog", $Catalog,
    "--version", $Version,
    "--seed", $SeedFile,
    "--out", $ReleaseDir
)

if ($MinVersionCode -gt 0) {
    $BuildArgs += "--min-version-code", $MinVersionCode
}

foreach ($t in $Tracker) {
    $BuildArgs += "--tracker", $t
}

Push-Location $ProjectRoot
try {
    & .\gradlew.bat :tools:catalog-publisher:run --args="$($BuildArgs -join ' ')"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to build catalogue release (exit code $LASTEXITCODE)"
    }

    Write-Host ""
    Write-Host "Publishing to DHT..."
    Write-Host "  release: $ReleaseDir"
    Write-Host "  hours:   $Hours"

    # Publish to DHT (will seed for the specified hours)
    $PublishArgs = @(
        "publish",
        "--release", $ReleaseDir,
        "--seed", $SeedFile,
        "--hours", $Hours
    )

    & .\gradlew.bat :tools:catalog-publisher:run --args="$($PublishArgs -join ' ')"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to publish catalogue (exit code $LASTEXITCODE)"
    }

    Write-Host ""
    Write-Host "[OK] Catalogue published successfully"
} finally {
    Pop-Location
}
