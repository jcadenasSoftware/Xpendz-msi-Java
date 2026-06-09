param(
    [string]$UpgradeUuid = '',
    [string]$MavenCmd = '',
    [string]$JdkHome = '',
    [string]$JPackageCmd = '',
    [string]$AppVersionOverride = '',
    [switch]$WinConsole,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

# Build MSI using Maven version (pom.xml) + jpackage.
# Usage (PowerShell):
#   powershell -ExecutionPolicy Bypass -File .\build-msi.ps1
#
# IMPORTANT (updates):
# To allow MSI upgrades over previous installations you MUST keep the same Upgrade UUID
# across releases. If you already have an installer/updater flow, reuse its existing UUID.

function Normalize-Uuid([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    $t = $Value.Trim()
    $m = [regex]::Match($t, '(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')
    if ($m.Success) { return $m.Value.ToLowerInvariant() }
    return $null
}

function Get-ProjectVersion {
    $pom = Join-Path $projectDir 'pom.xml'
    if (!(Test-Path $pom)) { throw "pom.xml not found: $pom" }
    $xml = Get-Content -Raw -Encoding UTF8 $pom

    $m = [regex]::Match($xml, '(?s)<project[^>]*>.*?<groupId>\s*com\.myfinances\s*</groupId>.*?<artifactId>\s*MyFinances\s*</artifactId>.*?<version>\s*([^<\s]+)\s*</version>')
    if (!$m.Success) {
        $m = [regex]::Match($xml, '(?s)<artifactId>\s*MyFinances\s*</artifactId>\s*<version>\s*([^<\s]+)\s*</version>')
    }
    if (!$m.Success) { throw 'Could not parse project version from pom.xml' }
    return $m.Groups[1].Value.Trim()
}

function Resolve-MavenCmd {
    if (![string]::IsNullOrWhiteSpace($MavenCmd)) {
        if (Test-Path $MavenCmd) {
            return (Resolve-Path $MavenCmd).Path
        }
        throw "MavenCmd not found: $MavenCmd"
    }

    $mvnw = Join-Path $projectDir 'mvnw.cmd'
    if (Test-Path $mvnw) {
        return (Resolve-Path $mvnw).Path
    }

    $cmd = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $cmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    throw "Maven not found. Install Apache Maven or pass -MavenCmd (e.g. -MavenCmd 'C:\\Apache\\maven\\bin\\mvn.cmd')"
}

function Resolve-JPackageCmd {
    if (![string]::IsNullOrWhiteSpace($JPackageCmd)) {
        if (Test-Path $JPackageCmd) {
            return (Resolve-Path $JPackageCmd).Path
        }
        throw "JPackageCmd not found: $JPackageCmd"
    }

    $cmd = Get-Command jpackage.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $cmd = Get-Command jpackage -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $jdkCandidates = @()
    if (![string]::IsNullOrWhiteSpace($JdkHome)) { $jdkCandidates += $JdkHome }
    if (![string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { $jdkCandidates += $env:JAVA_HOME }

    foreach ($jdk in $jdkCandidates) {
        try {
            $jp = Join-Path $jdk 'bin\jpackage.exe'
            if (Test-Path $jp) { return (Resolve-Path $jp).Path }
        } catch {
        }
    }

    $javaCmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCmd) {
        try {
            $javaPath = $javaCmd.Source
            $javaDir = Split-Path -Parent $javaPath
            $jp = Join-Path $javaDir 'jpackage.exe'
            if (Test-Path $jp) { return (Resolve-Path $jp).Path }
        } catch {
        }
    }

    $roots = @()
    if ($env:ProgramFiles) { $roots += (Join-Path $env:ProgramFiles 'Java') }
    if ($env:ProgramFiles) { $roots += (Join-Path $env:ProgramFiles 'Eclipse Adoptium') }
    if ($env:ProgramFiles) { $roots += (Join-Path $env:ProgramFiles 'Adoptium') }
    if ($env:ProgramFiles) { $roots += (Join-Path $env:ProgramFiles 'Zulu') }
    if ($env:LOCALAPPDATA) { $roots += (Join-Path $env:LOCALAPPDATA 'Programs\Java') }
    $roots = $roots | Where-Object { Test-Path $_ } | Select-Object -Unique

    foreach ($root in $roots) {
        try {
            $found = Get-ChildItem -Path $root -Filter 'jpackage.exe' -Recurse -File -ErrorAction SilentlyContinue |
                Sort-Object -Property LastWriteTime -Descending |
                Select-Object -First 1
            if ($null -ne $found) {
                return (Resolve-Path $found.FullName).Path
            }
        } catch {
        }
    }

    throw "jpackage not found. Install a JDK that includes jpackage (Java 14+) and ensure JAVA_HOME/bin is available, or add jpackage to PATH."
}

function Get-UpgradeCodeFromMsi([string]$MsiPath) {
    if ([string]::IsNullOrWhiteSpace($MsiPath) -or !(Test-Path $MsiPath)) {
        return $null
    }
    try {
        $wi = New-Object -ComObject WindowsInstaller.Installer
        $db = $wi.OpenDatabase($MsiPath, 0)
        $view = $db.OpenView("SELECT `Value` FROM `Property` WHERE `Property`='UpgradeCode'")
        $view.Execute()
        $rec = $view.Fetch()
        if ($null -eq $rec) {
            return $null
        }
        return $rec.StringData(1)
    } catch {
        return $null
    }
}

function Find-ExistingUpgradeUuid {
    try {
        $uuidFile = Join-Path $projectDir '.win-upgrade-uuid'
        if (Test-Path $uuidFile) {
            $u = (Get-Content -Raw $uuidFile).Trim()
            if (![string]::IsNullOrWhiteSpace($u)) { return $u }
        }
    } catch {
    }

    try {
        $dist = Join-Path $projectDir 'dist'
        if (!(Test-Path $dist)) { return $null }
        $msi = Get-ChildItem -Path $dist -Filter 'MisFinanzas-*.msi' -File |
            Sort-Object -Property LastWriteTime -Descending |
            Select-Object -First 1
        if ($null -eq $msi) { return $null }
        return (Get-UpgradeCodeFromMsi $msi.FullName)
    } catch {
        return $null
    }
}

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectDir

$ver = Get-ProjectVersion
$appVer = $ver
if (![string]::IsNullOrWhiteSpace($AppVersionOverride)) {
    $appVer = $AppVersionOverride.Trim()
}
Write-Host "Building Xpendz version $appVer" -ForegroundColor Cyan

# Build jar + copy dependencies to target/lib + copy config to target/config
# If Maven is not available in PATH, build from NetBeans first (Clean and Build),
# then run this script with -SkipBuild.
if (-not $SkipBuild) {
    try {
        $mvn = Resolve-MavenCmd
        & $mvn -DskipTests package
    } catch {
        throw "Maven not available. Build the project from NetBeans (Clean and Build) and re-run with: powershell -ExecutionPolicy Bypass -File .\\build-msi.ps1 -SkipBuild"
    }
}

$targetDir = Join-Path $projectDir 'target'
$jarName = "MyFinances-$ver.jar"
$jarPath = Join-Path $targetDir $jarName
if (!(Test-Path $jarPath)) { throw "Jar not found: $jarPath" }

$libDir = Join-Path $targetDir 'lib'
if (!(Test-Path $libDir)) { throw "Dependencies folder not found: $libDir" }

# Prepare jpackage input folder
$jpIn = Join-Path $targetDir 'jpackage-input'
if (Test-Path $jpIn) { Remove-Item -Recurse -Force $jpIn }
New-Item -ItemType Directory -Path $jpIn | Out-Null

Copy-Item -Force $jarPath $jpIn
Copy-Item -Recurse -Force $libDir (Join-Path $jpIn 'lib')

$cfgDir = Join-Path $targetDir 'config'
if (Test-Path $cfgDir) {
    Copy-Item -Recurse -Force $cfgDir (Join-Path $jpIn 'config')
}

$distDir = Join-Path $projectDir 'dist'
if (!(Test-Path $distDir)) { New-Item -ItemType Directory -Path $distDir | Out-Null }

$iconPath = Join-Path $projectDir 'build-resources\app.ico'
$iconArgs = @()
if (Test-Path $iconPath) {
    $iconArgs = @('--icon', $iconPath)
}

if ([string]::IsNullOrWhiteSpace($UpgradeUuid)) {
    $UpgradeUuid = Find-ExistingUpgradeUuid
}

$UpgradeUuid = Normalize-Uuid $UpgradeUuid

if ([string]::IsNullOrWhiteSpace($UpgradeUuid)) {
    $uuidFile = Join-Path $projectDir '.win-upgrade-uuid'
    throw "Missing Upgrade UUID. Provide it via -UpgradeUuid or create $uuidFile with the UUID. If you have a previous MSI, place it under dist/ so this script can auto-detect the UpgradeCode."
}

$upgradeUuid = $UpgradeUuid

try {
    $uuidFile = Join-Path $projectDir '.win-upgrade-uuid'
    Set-Content -Path $uuidFile -Value $upgradeUuid -Encoding UTF8
} catch {
}

$jpackageArgs = @(
    '--type', 'msi',
    '--dest', $distDir,
    '--name', 'Xpendz',
    '--app-version', $appVer,
    '--vendor', 'JCadenas Software',
    '--input', $jpIn,
    '--main-jar', $jarName,
    '--main-class', 'com.myfinaces.MyFinances',
    '--win-menu',
    '--win-shortcut',
    '--win-upgrade-uuid', $upgradeUuid
) + $iconArgs

$hasJavaFx = $false
try {
    $hasJavaFx = (Get-ChildItem -Path $libDir -Filter 'javafx-*.jar' -File -ErrorAction SilentlyContinue | Measure-Object).Count -gt 0
} catch {
}

$hasSqlite = $false
try {
    $hasSqlite = (Get-ChildItem -Path $libDir -Filter 'sqlite-jdbc*.jar' -File -ErrorAction SilentlyContinue | Measure-Object).Count -gt 0
} catch {
}

if ($hasJavaFx) {
    $modules = @('javafx.controls','javafx.fxml','javafx.media','javafx.graphics','javafx.base','java.net.http','jdk.httpserver','jdk.crypto.ec','java.naming')
    if ($hasSqlite) {
        $modules += @('java.sql','java.logging')
    }
    $jpackageArgs += @(
        '--module-path', $libDir,
        '--add-modules', ($modules -join ',')
    )
}

if ($WinConsole) {
    $jpackageArgs += '--win-console'
}

Write-Host "Running jpackage..." -ForegroundColor Cyan
$jpackageCmd = Resolve-JPackageCmd
& $jpackageCmd @jpackageArgs

$expected = Join-Path $distDir ("Xpendz-$appVer.msi")
if (!(Test-Path $expected)) {
    $latest = Get-ChildItem -Path $distDir -Filter 'Xpendz-*.msi' -File -ErrorAction SilentlyContinue |
        Sort-Object -Property LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -ne $latest) {
        throw "jpackage finished but expected MSI was not generated: $expected. Latest MSI found: $($latest.FullName)"
    }
    throw "jpackage finished but expected MSI was not generated: $expected"
}

Write-Host "Done. MSI generated: $expected" -ForegroundColor Green
