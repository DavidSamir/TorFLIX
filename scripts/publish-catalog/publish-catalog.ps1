#!/usr/bin/env pwsh
<#
.SYNOPSIS
Merges the catalogue folders into one catalogue and publishes it, signed, over the peer-to-peer DHT.

.DESCRIPTION
Just run it:   .\publish-catalog.ps1

Folders next to this script:
  add\        Every catalogue file to publish: .json, .json.gz, .jsonl or .ndjson, any number of them,
              in sub-folders too. When a title is in several files, the newest file wins.
  remove\     What to take out: ids ("catalog-inception-2010", or an episode's id), or objects with a
              "title" (and a "year"), such as a title copied from a catalogue file.
  published\  One folder per release pushed, "release-4 (2026-09-24 10-15)": the catalog.json the
              televisions receive, the merge report, the signed release, and published.txt saying
              when it was pushed.
  output\     Written by every run: catalog.json, merge-report.txt, merge-summary.properties.
  .release\   Every release built, torfilx-catalogue-<n>\ and its .torrent, and versions-used.txt, the
              record of every release number used. Never edited.

Each step stops everything when it fails; nothing is published unless every check passed:
  1. Find Java 17 or later: JAVA_HOME, else Android Studio's bundled JBR, else java on the PATH.
  2. Install the catalogue publisher (Gradle installDist; quick when nothing changed).
  3. Find the publisher seed and check it signs with a key the app trusts.
  4. Merge add\ and remove\ into output\catalog.json, checking every title.
  5. Build and sign the release in a staging folder, verify it with the app's key and version, check
     it holds exactly the merged catalogue, then move it into .release\.
  6. Stop any earlier publisher of this catalogue, file the release in published\, and publish it.

Everything is settled by itself:
  seed        -SeedFile, else $env:TORFILX_PUBLISHER_SEED, else the seed in ~\.torfilx\ the app
              trusts (catalogue-publisher.seed first)
  version     one higher than every release number used (.release\, dist\catalogue\ and
              .release\versions-used.txt). When nothing changed since the latest release, it is
              published again as it is.
  app build   the last release's minimum, and at least 17 when there are shows

.PARAMETER SeedFile
The publisher seed. Found by itself when not given.
.PARAMETER Version
Release number (default: one higher than every number used).
.PARAMETER MinVersionCode
Oldest app build that may install it (default: the last release's, and at least 17 with shows).
.PARAMETER Hours
How long to publish and seed (default 24).
.PARAMETER Listen
libtorrent listen interfaces for publishing, e.g. 0.0.0.0:6881.
.PARAMETER Tracker
Extra trackers for the release torrent.
.PARAMETER StripTrackers
Keep two trackers per magnet (when the catalogue is near its size limit).
.PARAMETER Order
How the newest file is decided: mtime (modification time, the default) or name. After a git
checkout or a copy, times say nothing: name files so they sort by age (2026-09-24-films.json) and
use -Order name.
.PARAMETER MergeEpisodes
A show in several files gets every episode any of them has, the newest copy of each (default: the
newest copy of the show is used whole).
.PARAMETER Strict
Any warning fails the run.
.PARAMETER AllowVanished
Allow titles of the last release to disappear without being in remove\.
.PARAMETER MaxVanishedPercent
How many may disappear before the run stops (default 5).
.PARAMETER Add
Use another add folder. It must exist.
.PARAMETER Remove
Use another remove folder. It must exist.
.PARAMETER Keys
Public keys (hex, comma-separated) the release must verify with (default: the app's).
.PARAMETER DryRun
Merge and check only; no seed needed, nothing built or published.
.PARAMETER NoPublish
Build and verify, but do not publish.

.EXAMPLE
.\publish-catalog.ps1
.EXAMPLE
.\publish-catalog.ps1 -DryRun
.EXAMPLE
.\publish-catalog.ps1 -Order name -Strict -Hours 48
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$SeedFile = "",
    [long]$Version = 0,
    [int]$MinVersionCode = 0,
    [double]$Hours = 24,
    [string]$Listen = "",
    [string[]]$Tracker = @(),
    [switch]$StripTrackers,
    [ValidateSet("mtime", "name")]
    [string]$Order = "",
    [switch]$MergeEpisodes,
    [switch]$Strict,
    [switch]$AllowVanished,
    [double]$MaxVanishedPercent = -1,
    [string]$Add = "",
    [string]$Remove = "",
    [string]$Keys = "",
    [switch]$DryRun,
    [switch]$NoPublish,
    # Gone: kept only to say so.
    [string]$Catalog = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$ScriptDir = $PSScriptRoot
$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $ScriptDir "..\.."))
$OnWindows = ($env:OS -eq "Windows_NT")
$Invariant = [Globalization.CultureInfo]::InvariantCulture

$OutputDir = Join-Path $ScriptDir "output"
$PublishedDir = Join-Path $ScriptDir "published"
$ReleaseDir = Join-Path $ScriptDir ".release"
$UsedVersions = Join-Path $ReleaseDir "versions-used.txt"
$DistReleaseDir = Join-Path $ProjectRoot "dist\catalogue"
$LockDir = Join-Path $ScriptDir ".publish.lock"
$SeedHome = Join-Path $HOME ".torfilx"
$ToolLib = Join-Path $ProjectRoot "tools\catalog-publisher\build\install\catalog-publisher\lib"
$MainClass = "com.torfilx.tools.catalog.CliKt"

function Say([string]$Text = "") { Write-Host $Text }

function Step([string]$Text) {
    Write-Host ""
    Write-Host "==> $Text"
}

function Fail([string]$Message, [int]$Code = 1) {
    Write-Host ""
    [Console]::Error.WriteLine("error: $Message")
    exit $Code
}

# A full path, relative to the current location when not absolute. The file need not exist.
function Get-FullPath([string]$Path) {
    $full = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
    if ($full.Length -gt 3) { $full = $full.TrimEnd('\', '/') }
    return $full
}

# --- arguments -------------------------------------------------------------------------------

if ($Catalog) { Fail "-Catalog is gone: put catalogue files in $(Join-Path $ScriptDir 'add') (any number of them); the newest copy of each title wins" 2 }
if ($Version -lt 0) { Fail "-Version must be 1 or more, got $Version" 2 }
if ($MinVersionCode -lt 0) { Fail "-MinVersionCode must be 1 or more, got $MinVersionCode" 2 }
if (-not ($Hours -gt 0) -or [double]::IsInfinity($Hours)) { Fail "-Hours must be a positive number, got $Hours" 2 }
if ($MaxVanishedPercent -ne -1 -and -not ($MaxVanishedPercent -ge 0 -and $MaxVanishedPercent -le 100)) {
    Fail "-MaxVanishedPercent must be between 0 and 100, got $MaxVanishedPercent" 2
}

# The default folders are made on first use. A folder named on the command line must exist: a typo in
# -Remove must not become an empty folder that silently removes nothing.
$AddGiven = [bool]$Add
$RemoveGiven = [bool]$Remove
$AddDir = if ($AddGiven) { Get-FullPath $Add } else { Join-Path $ScriptDir "add" }
$RemoveDir = if ($RemoveGiven) { Get-FullPath $Remove } else { Join-Path $ScriptDir "remove" }

# A seed named on the command line or in TORFILX_PUBLISHER_SEED must be there; otherwise it is found
# in ~\.torfilx\ once the publisher can read seeds (step 3).
if (-not $SeedFile -and $env:TORFILX_PUBLISHER_SEED) { $SeedFile = $env:TORFILX_PUBLISHER_SEED }
if (-not $DryRun -and $SeedFile) {
    $SeedFile = Get-FullPath $SeedFile
    if (-not (Test-Path -LiteralPath $SeedFile -PathType Leaf)) { Fail "no seed file at $SeedFile" }
    if ((Get-Item -LiteralPath $SeedFile).Length -eq 0) { Fail "the seed file $SeedFile is empty" }
}

# --- one run at a time -----------------------------------------------------------------------

# Two runs at once would overwrite each other's output and staging folder. The lock is held until
# publishing starts; a later run then replaces the publisher itself (step 6). A lock left by a run
# that died is taken over.
$LockHeld = $false
function Enter-Lock {
    try {
        New-Item -ItemType Directory -Path $LockDir -ErrorAction Stop | Out-Null
    } catch {
        $owner = @(Get-Content -LiteralPath (Join-Path $LockDir "owner") -ErrorAction SilentlyContinue)
        $ownerScript = "$($owner | Where-Object { $_ -like 'script=*' } | Select-Object -First 1)" -replace '^script=', ''
        $ownerPid = "$($owner | Where-Object { $_ -like 'pid=*' } | Select-Object -First 1)" -replace '^pid=', ''
        $running = $ownerPid -match '^\d+$' -and (Get-Process -Id ([int]$ownerPid) -ErrorAction SilentlyContinue)
        if ($ownerScript -eq "ps1" -and $ownerPid -match '^\d+$' -and -not $running) {
            Say "Taking over the lock of an earlier run (process $ownerPid) that is no longer running."
            Remove-Item -LiteralPath $LockDir -Recurse -Force
            New-Item -ItemType Directory -Path $LockDir -ErrorAction Stop | Out-Null
        } else {
            Fail "another run is merging or building (see $LockDir\owner). Wait for it; if none is running, delete $LockDir"
        }
    }
    $started = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ", $Invariant)
    Set-Content -LiteralPath (Join-Path $LockDir "owner") -Value @("script=ps1", "pid=$PID", "started=$started") -Encoding ASCII
}

function Exit-Lock {
    if ($script:LockHeld) {
        Remove-Item -LiteralPath $LockDir -Recurse -Force -ErrorAction SilentlyContinue
        $script:LockHeld = $false
    }
}

Enter-Lock
$LockHeld = $true
$SavedOutputEncoding = $null
try {
    # Titles are UTF-8; the tool writes UTF-8 and the console reads it as such while this runs.
    try {
        $SavedOutputEncoding = [Console]::OutputEncoding
        [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
    } catch {
        $SavedOutputEncoding = $null
    }

    # --- 1. Java ---------------------------------------------------------------------------------

    Step "1/6 Java"
    # Plain path joining: Join-Path fails outright on a drive that does not exist.
    function Get-JavaIn([string]$Dir) {
        if (-not $Dir) { return $null }
        foreach ($name in @("java.exe", "java")) {
            try {
                $candidate = [IO.Path]::Combine($Dir, "bin", $name)
                if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
            } catch {
                return $null
            }
        }
        return $null
    }
    $Java = Get-JavaIn $env:JAVA_HOME
    if (-not $Java) {
        if ($env:JAVA_HOME) { Say "JAVA_HOME ($env:JAVA_HOME) holds no Java; looking elsewhere." }
        Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
        $candidates = @()
        if ($env:ProgramFiles) { $candidates += Join-Path $env:ProgramFiles "Android\Android Studio\jbr" }
        if (${env:ProgramFiles(x86)}) { $candidates += Join-Path ${env:ProgramFiles(x86)} "Android\Android Studio\jbr" }
        if ($env:LOCALAPPDATA) { $candidates += Join-Path $env:LOCALAPPDATA "Programs\Android Studio\jbr" }
        $candidates += @(
            "/Applications/Android Studio.app/Contents/jbr/Contents/Home",
            "$HOME/android-studio/jbr",
            "/opt/android-studio/jbr",
            "/usr/local/android-studio/jbr",
            "/snap/android-studio/current/jbr"
        )
        foreach ($candidate in $candidates) {
            $found = Get-JavaIn $candidate
            if ($found) {
                $env:JAVA_HOME = $candidate
                $Java = $found
                break
            }
        }
        if (-not $Java) {
            $command = Get-Command java -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($command) { $Java = $command.Path }
        }
    }
    if (-not $Java) { Fail "no Java found. Install JDK 17 or later, or set JAVA_HOME (Android Studio's bundled JBR works)" }

    $saved = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $versionText = (& $Java -version 2>&1 | ForEach-Object { "$_" }) -join " "
    $ErrorActionPreference = $saved
    if ($versionText -notmatch 'version "(\d+)(?:\.(\d+))?') { Fail "cannot tell the version of $Java (it said: $versionText)" }
    $JavaMajor = [int]$Matches[1]
    if ($JavaMajor -eq 1 -and $Matches[2]) { $JavaMajor = [int]$Matches[2] }
    if ($JavaMajor -lt 17) { Fail "$Java is Java $JavaMajor; the publisher needs 17 or later (Android Studio's bundled JBR works)" }
    Say "Java ${JavaMajor}: $Java"

    # --- 2. the publisher ------------------------------------------------------------------------

    Step "2/6 Installing the catalogue publisher"
    Push-Location $ProjectRoot
    try {
        $saved = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        if ($OnWindows) {
            & .\gradlew.bat --quiet --console=plain :tools:catalog-publisher:installDist | Out-Host
        } else {
            & sh ./gradlew --quiet --console=plain :tools:catalog-publisher:installDist | Out-Host
        }
        $gradleExit = $LASTEXITCODE
        $ErrorActionPreference = $saved
    } finally {
        Pop-Location
    }
    if ($gradleExit -ne 0) { Fail "Gradle could not build the publisher (see above). On a machine short of memory, try `$env:GRADLE_OPTS = '-Xmx1536m'" }
    if (-not (Test-Path -LiteralPath (Join-Path $ToolLib "catalog-publisher.jar"))) { Fail "the publisher was not installed at $ToolLib" }
    Say "Installed: $ToolLib"

    function Get-JavaArguments([string[]]$ToolArgs) {
        $options = @()
        if ($env:JAVA_OPTS) { $options = @($env:JAVA_OPTS -split '\s+' | Where-Object { $_ }) }
        return @($options) + @("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", (Join-Path $ToolLib "*"), $MainClass) + $ToolArgs
    }

    # Runs the publisher, showing its output as it comes, and returns its exit code.
    function Invoke-Tool([string[]]$ToolArgs) {
        $javaArgs = Get-JavaArguments $ToolArgs
        $saved = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & $Java @javaArgs | Out-Host
            return $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $saved
        }
    }

    # Runs the publisher and returns what it printed, and its exit code. Its errors are not shown.
    function Get-ToolOutput([string[]]$ToolArgs) {
        $javaArgs = Get-JavaArguments $ToolArgs
        $saved = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $lines = @(& $Java @javaArgs 2>$null | ForEach-Object { "$_" })
            return @{ Code = $LASTEXITCODE; Lines = $lines }
        } finally {
            $ErrorActionPreference = $saved
        }
    }

    # --- 3. the key ------------------------------------------------------------------------------

    $AppGradle = Join-Path $ProjectRoot "app\build.gradle.kts"
    $DataGradle = Join-Path $ProjectRoot "core\data\build.gradle.kts"
    $AppVersionCode = ""
    if (Test-Path -LiteralPath $AppGradle) {
        $match = Select-String -LiteralPath $AppGradle -Pattern '^\s*versionCode\s*=\s*(\d+)' | Select-Object -First 1
        if ($match) { $AppVersionCode = $match.Matches[0].Groups[1].Value }
    }
    if (-not $Keys -and (Test-Path -LiteralPath $DataGradle)) {
        $match = Select-String -LiteralPath $DataGradle -Pattern '^\s*val productionCataloguePublisherKeys\s*=\s*"([^"]*)"' | Select-Object -First 1
        if ($match) { $Keys = $match.Matches[0].Groups[1].Value }
    }
    $Keys = ($Keys -replace '\s', '').ToLowerInvariant()
    $TrustedKeys = @($Keys -split ',' | Where-Object { $_ })

    # The public key a seed file signs with, or "" when it is not a seed.
    function Get-PublicKey([string]$Path) {
        $result = Get-ToolOutput @("pubkey", "--seed", $Path)
        if ($result.Code -ne 0 -or $result.Lines.Count -eq 0) { return "" }
        return $result.Lines[-1].Trim().ToLowerInvariant()
    }

    Step "3/6 Publisher key"
    $PublicKey = ""
    if ($DryRun) {
        Say "Dry run: the seed is not needed and not checked."
    } else {
        if ($TrustedKeys.Count -eq 0) { Fail "no trusted key found in $DataGradle (productionCataloguePublisherKeys); pass -Keys <hex>" }
        if ($SeedFile) {
            $PublicKey = Get-PublicKey $SeedFile
            if (-not $PublicKey) { Fail "$SeedFile is not a publisher seed (64 hexadecimal characters)" }
            if ($TrustedKeys -notcontains $PublicKey) {
                Fail "the seed $SeedFile signs as $PublicKey, which the app does not trust ($Keys). Every television would refuse the release. Use the right seed, or -Keys for a test build"
            }
        } else {
            $candidates = @(Join-Path $SeedHome "catalogue-publisher.seed")
            if (Test-Path -LiteralPath $SeedHome -PathType Container) {
                $candidates += @(Get-ChildItem -LiteralPath $SeedHome -Filter "*.seed" -File | Sort-Object Name | ForEach-Object { $_.FullName })
            }
            $untrusted = @()
            foreach ($candidate in ($candidates | Select-Object -Unique)) {
                if (-not (Test-Path -LiteralPath $candidate -PathType Leaf) -or (Get-Item -LiteralPath $candidate).Length -eq 0) { continue }
                $key = Get-PublicKey $candidate
                if ($key -and $TrustedKeys -contains $key) {
                    $SeedFile = $candidate
                    $PublicKey = $key
                    break
                }
                $untrusted += $candidate
            }
            if (-not $SeedFile -and $untrusted.Count -gt 0) {
                Fail "no seed in $SeedHome signs with a key the app trusts ($Keys); found: $($untrusted -join ', ')"
            }
            if (-not $SeedFile) {
                Fail "no publisher seed found. Put it at $(Join-Path $SeedHome 'catalogue-publisher.seed'), set `$env:TORFILX_PUBLISHER_SEED, or pass -SeedFile <file>"
            }
        }
        Say "Seed: $SeedFile"
        Say "It signs as $PublicKey, a key the app trusts."
        if ($SeedFile.StartsWith($ProjectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            Say "warning: the seed is inside the repository. It signs every release: keep it somewhere else."
        }
    }
    if ($AppVersionCode) {
        Say "The app is at build $AppVersionCode."
    } else {
        Say "warning: no versionCode found in $AppGradle; the release is not checked against the app's build."
    }

    # --- 4. merge --------------------------------------------------------------------------------

    Step "4/6 Merging add\ and remove\"
    if (-not $AddGiven) { New-Item -ItemType Directory -Force -Path $AddDir | Out-Null }
    if (-not $RemoveGiven) { New-Item -ItemType Directory -Force -Path $RemoveDir | Out-Null }
    New-Item -ItemType Directory -Force -Path $OutputDir, $ReleaseDir | Out-Null
    $CatalogFile = Join-Path $OutputDir "catalog.json"
    $Report = Join-Path $OutputDir "merge-report.txt"
    $Summary = Join-Path $OutputDir "merge-summary.properties"
    $mergeArgs = @(
        "merge",
        "--add", $AddDir,
        "--remove", $RemoveDir,
        "--out", $CatalogFile,
        "--report", $Report,
        "--summary", $Summary,
        "--releases", $ReleaseDir,
        "--releases", $DistReleaseDir
    )
    if ($Version -gt 0) { $mergeArgs += @("--version", "$Version") }
    if ($MinVersionCode -gt 0) { $mergeArgs += @("--min-version-code", "$MinVersionCode") }
    if ($AppVersionCode) { $mergeArgs += @("--app-version-code", $AppVersionCode) }
    if ($Order) { $mergeArgs += @("--order", $Order) }
    if ($MaxVanishedPercent -ne -1) { $mergeArgs += @("--max-vanished-percent", $MaxVanishedPercent.ToString($Invariant)) }
    if ($StripTrackers) { $mergeArgs += "--strip-trackers" }
    if ($MergeEpisodes) { $mergeArgs += "--merge-episodes" }
    if ($Strict) { $mergeArgs += "--strict" }
    if ($AllowVanished) { $mergeArgs += "--allow-vanished" }

    $mergeExit = Invoke-Tool $mergeArgs
    if ($mergeExit -ne 0) { Fail "the merge failed; nothing was built or published. Everything it found is in $Report" $mergeExit }

    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Summary)) {
        $at = $line.IndexOf('=')
        if ($at -gt 0) { $values[$line.Substring(0, $at)] = $line.Substring($at + 1) }
    }
    if ($values["status"] -ne "ok") { Fail "the merge summary ($Summary) does not say ok; nothing was built or published" }
    if (-not (Test-Path -LiteralPath $CatalogFile -PathType Leaf)) { Fail "the merge reported success but wrote no $CatalogFile" }
    $NewVersion = "$($values['version'])"
    $NewMinCode = "$($values['min_version_code'])"
    $Republish = "$($values['republish'])"
    $CatalogSha = "$($values['sha256'])"
    if ($NewVersion -notmatch '^[1-9]\d*$') { Fail "the merge settled no release number; see $Report" }

    if ($DryRun) {
        Step "Dry run finished"
        $forBuild = if ($NewMinCode) { ", for app build $NewMinCode or later" } else { "" }
        Say "The merged catalogue is $CatalogFile (release $NewVersion$forBuild); nothing was built or published."
        exit 0
    }

    # --- 5. build and verify ---------------------------------------------------------------------

    function Get-UnpackedSha([string]$Path) {
        $file = [IO.File]::OpenRead($Path)
        try {
            $gzip = New-Object IO.Compression.GZipStream($file, [IO.Compression.CompressionMode]::Decompress)
            $sha = [Security.Cryptography.SHA256]::Create()
            return ([BitConverter]::ToString($sha.ComputeHash($gzip)) -replace '-', '').ToLowerInvariant()
        } finally {
            $file.Dispose()
        }
    }

    function Confirm-Release([string]$Root) {
        $verifyArgs = @("verify", "--dir", $Root, "--keys", $Keys)
        if ($AppVersionCode) { $verifyArgs += @("--app-version-code", $AppVersionCode) }
        if ((Invoke-Tool $verifyArgs) -ne 0) {
            $build = if ($AppVersionCode) { " and build $AppVersionCode" } else { "" }
            Fail "release $Root does not verify with the app's key$build; it was not published"
        }
    }

    # Adds a release number to the record of numbers used, which outlives any release folder.
    function Add-UsedVersion([string]$Number, [string]$Note) {
        if (-not (Test-Path -LiteralPath $UsedVersions)) {
            Set-Content -LiteralPath $UsedVersions -Encoding ASCII -Value @(
                "# Release numbers already used. A television ignores a release that is not newer than the one it",
                "# has, so the next release is always higher than every number here, even when a folder is deleted."
            )
        }
        $stamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ", $Invariant)
        Add-Content -LiteralPath $UsedVersions -Encoding ASCII -Value "$Number  $stamp  $Note"
    }

    if ($Republish) {
        Step "5/6 Nothing changed since release $NewVersion"
        $ReleaseRoot = [IO.Path]::GetFullPath($Republish)
        if (-not (Test-Path -LiteralPath $ReleaseRoot -PathType Container)) { Fail "release $NewVersion is not at $ReleaseRoot" }
        if (-not (Test-Path -LiteralPath "$ReleaseRoot.torrent" -PathType Leaf)) {
            Fail "release $NewVersion has no torrent at $ReleaseRoot.torrent; rebuild it with -Version $([long]$NewVersion + 1)"
        }
        Confirm-Release $ReleaseRoot
        Say "Publishing release $NewVersion again, as it is."
    } else {
        Step "5/6 Building release $NewVersion"
        $Staging = Join-Path $ReleaseDir ".staging"
        $ReleaseName = "torfilx-catalogue-$NewVersion"
        $ReleaseRoot = Join-Path $ReleaseDir $ReleaseName
        if ((Test-Path -LiteralPath $ReleaseRoot) -or (Test-Path -LiteralPath "$ReleaseRoot.torrent")) {
            Fail "$ReleaseRoot already exists; a release never changes. Use a higher -Version"
        }
        if (Test-Path -LiteralPath $Staging) { Remove-Item -LiteralPath $Staging -Recurse -Force }
        New-Item -ItemType Directory -Force -Path $Staging | Out-Null
        $buildArgs = @("build", "--catalog", $CatalogFile, "--version", $NewVersion, "--seed", $SeedFile, "--out", $Staging)
        if ($NewMinCode) { $buildArgs += @("--min-version-code", $NewMinCode) }
        foreach ($url in $Tracker) { $buildArgs += @("--tracker", $url) }
        if ((Invoke-Tool $buildArgs) -ne 0) { Fail "building release $NewVersion failed (see above); nothing was published" }

        $StagedRoot = Join-Path $Staging $ReleaseName
        if (-not (Test-Path -LiteralPath $StagedRoot -PathType Container) -or -not (Test-Path -LiteralPath "$StagedRoot.torrent" -PathType Leaf)) {
            Fail "the build left no release in $Staging"
        }
        Say ""
        Confirm-Release $StagedRoot
        $BuiltSha = Get-UnpackedSha (Join-Path $StagedRoot "catalog.json.gz")
        if ($BuiltSha -ne $CatalogSha) {
            Fail "release $NewVersion does not hold exactly the merged catalogue (sha-256 $BuiltSha, merged $CatalogSha); it was not published"
        }
        Say "The release holds exactly the merged catalogue (sha-256 $BuiltSha)."

        Move-Item -LiteralPath $StagedRoot -Destination $ReleaseRoot
        Move-Item -LiteralPath "$StagedRoot.torrent" -Destination "$ReleaseRoot.torrent"
        Remove-Item -LiteralPath $Staging -Recurse -Force
        Add-UsedVersion $NewVersion "built: $($values['titles']) titles, sha-256 $CatalogSha"
        Say "Release $NewVersion is in $ReleaseRoot"
    }

    # --- 6. publish ------------------------------------------------------------------------------

    $HoursText = $Hours.ToString($Invariant)
    if ($NoPublish) {
        Step "Built, not published (-NoPublish)"
        Say "To publish it, run .\publish-catalog.ps1 again: nothing changed, so this release is published as it is."
        exit 0
    }

    Step "6/6 Publishing release $NewVersion for $HoursText hours"

    # Only one release may be published at a time: two publishers of the same key take turns
    # overwriting the DHT pointer, and televisions may miss the newer release. Rehearsals (--private,
    # --salt) are left alone, and so is seed-titles, which serves the titles themselves.
    $publishPattern = 'com\.torfilx\.tools\.catalog\.CliKt\s+publish\s'
    $rehearsalPattern = '\s--(private|salt)(\s|=)'
    if ($OnWindows) {
        $earlier = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
            Where-Object { $_.CommandLine -match $publishPattern -and $_.CommandLine -notmatch $rehearsalPattern } |
            ForEach-Object { @{ Id = [int]$_.ProcessId; CommandLine = $_.CommandLine } })
    } else {
        $earlier = @(& ps -eo "pid=,args=" | ForEach-Object { "$_".Trim() } |
            Where-Object { $_ -match $publishPattern -and $_ -notmatch $rehearsalPattern } |
            ForEach-Object { @{ Id = [int]($_ -split '\s+', 2)[0]; CommandLine = $_ } })
    }
    foreach ($process in $earlier) {
        $release = if ($process.CommandLine -match '--release[= ]+("[^"]+"|\S+)') { $Matches[1] } else { "an earlier release" }
        Say "Stopping the earlier publisher of $release (process $($process.Id))"
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }

    # Files the release in published\: one folder per release, the first time it is pushed. The lock is
    # still held, so output\ is this run's merge.
    New-Item -ItemType Directory -Force -Path $PublishedDir | Out-Null
    $localTime = Get-Date -Format "yyyy-MM-dd HH:mm"
    $existing = Get-ChildItem -LiteralPath $PublishedDir -Directory | Where-Object { $_.Name -like "release-$NewVersion (*" } | Select-Object -First 1
    if ($existing) {
        Add-Content -LiteralPath (Join-Path $existing.FullName "published.txt") -Encoding ASCII -Value "Published again $localTime"
        Say "Filed: $($existing.FullName) (published again)"
    } else {
        $folder = Join-Path $PublishedDir ("release-$NewVersion (" + (Get-Date -Format "yyyy-MM-dd HH-mm") + ")")
        New-Item -ItemType Directory -Force -Path (Join-Path $folder "release") | Out-Null
        Copy-Item -LiteralPath $CatalogFile, $Report -Destination $folder
        Copy-Item -LiteralPath $ReleaseRoot -Destination (Join-Path $folder "release") -Recurse
        Copy-Item -LiteralPath "$ReleaseRoot.torrent" -Destination (Join-Path $folder "release")
        $appBuild = if ($NewMinCode) { $NewMinCode } else { "any" }
        Set-Content -LiteralPath (Join-Path $folder "published.txt") -Encoding ASCII -Value @(
            "Release $NewVersion",
            "",
            "  published    $localTime (publishing started; it runs for $HoursText hours)",
            "  titles       $($values['titles']): $($values['films']) films, $($values['shows']) shows with $($values['episodes']) episodes",
            "  app build    $appBuild or later",
            "  catalog.json sha-256 $CatalogSha",
            "  signed by    $PublicKey",
            "",
            "catalog.json      what televisions receive",
            "merge-report.txt  everything the merge found and did, file by file",
            "release\          the signed release and its torrent",
            ""
        )
        Say "Filed: $folder"
    }

    # From here on a later run may replace this one, so the lock is let go.
    Exit-Lock

    Say "Stop with Ctrl+C. The DHT forgets the pointer about 2 hours after publishing stops; run this again"
    Say "to publish the same release again."
    $publishArgs = @("publish", "--release", $ReleaseRoot, "--seed", $SeedFile, "--hours", $HoursText)
    if ($Listen) { $publishArgs += @("--listen", $Listen) }
    $publishExit = Invoke-Tool $publishArgs
    if ($publishExit -ne 0) {
        $newest = Get-ChildItem -LiteralPath $ReleaseDir -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^torfilx-catalogue-(\d+)$' } |
            ForEach-Object { [long]($_.Name -replace '^torfilx-catalogue-', '') } |
            Sort-Object | Select-Object -Last 1
        if ($newest -and $newest -gt [long]$NewVersion) {
            Say ""
            Say "Release $NewVersion is no longer published: release $newest, published by a later run, replaced it."
            exit 0
        }
        Fail "publishing release $NewVersion failed (see above). The release is built and verified; run this again to publish it" $publishExit
    }

    Say ""
    Say "OK: release $NewVersion was published for $HoursText hours."
} finally {
    if ($null -ne $SavedOutputEncoding) {
        try { [Console]::OutputEncoding = $SavedOutputEncoding } catch { Write-Verbose "could not restore the console encoding" }
    }
    Exit-Lock
}
