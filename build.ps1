<#
    Sarab Vision - build helper
    ---------------------------
    WHY THIS SCRIPT EXISTS

    This project lives under a path containing Arabic characters:

        C:\Users\albre\Videos\سراب\test-app

    The JVM on this machine cannot load classpath JARs from a non-ASCII
    directory. Verified directly: the identical gradle-wrapper.jar loads fine
    from an ASCII path and fails with ClassNotFoundException from this one.
    That breaks `gradlew` before Gradle even starts, so it is not something
    a Gradle setting can fix.

    This script mirrors the project to an ASCII working directory, builds
    there, and copies the resulting APK back next to the source. Your source
    of truth stays where you keep it.

    USAGE
        .\build.ps1              # debug APK
        .\build.ps1 -Release     # release APK (unsigned)
        .\build.ps1 -Install     # debug APK + install to connected device
        .\build.ps1 -Clean       # wipe the mirror and rebuild from scratch
#>

param(
    [switch]$Release,
    [switch]$Install,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

$SourceDir = $PSScriptRoot
$BuildDir  = Join-Path $env:LOCALAPPDATA "SarabVisionBuild"

# AGP 8.7 requires a JDK 17-21. Android Studio's bundled JBR 21 is the safe
# choice here; the system JDK 25 is too new for Gradle 8.13.
$Jbr = "C:\Program Files\Android\Android Studio\jbr"
if (Test-Path $Jbr) {
    $env:JAVA_HOME = $Jbr
} else {
    Write-Warning "Android Studio JBR not found at $Jbr - falling back to JAVA_HOME=$env:JAVA_HOME"
}

if ($Clean -and (Test-Path $BuildDir)) {
    Write-Host "Cleaning mirror at $BuildDir" -ForegroundColor Yellow
    Remove-Item -Recurse -Force $BuildDir
}

New-Item -ItemType Directory -Force $BuildDir | Out-Null

Write-Host "Syncing source -> $BuildDir" -ForegroundColor Cyan
# /MIR mirrors the tree. We exclude build outputs and VCS metadata so the
# mirror stays small and never fights with Gradle's own caches.
$roboArgs = @(
    $SourceDir, $BuildDir, "/MIR",
    "/XD", ".git", "build", ".gradle", ".idea",
    "/NFL", "/NDL", "/NJH", "/NJS", "/NP", "/NS", "/NC"
)
& robocopy @roboArgs | Out-Null

# robocopy exit codes below 8 are success//informational, not failures.
if ($LASTEXITCODE -ge 8) {
    throw "robocopy failed with exit code $LASTEXITCODE"
}

Push-Location $BuildDir
try {
    $task = if ($Release) { "assembleRelease" } else { "assembleDebug" }
    Write-Host "Running gradlew $task" -ForegroundColor Cyan

    & .\gradlew.bat $task --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }

    $variant = if ($Release) { "release" } else { "debug" }
    $apk = Get-ChildItem -Recurse -Path (Join-Path $BuildDir "app\build\outputs\apk\$variant") -Filter "*.apk" |
           Select-Object -First 1

    if (-not $apk) { throw "Build reported success but no APK was produced." }

    $outDir = Join-Path $SourceDir "output"
    New-Item -ItemType Directory -Force $outDir | Out-Null
    Copy-Item $apk.FullName $outDir -Force

    $finalApk = Join-Path $outDir $apk.Name
    $sizeMb = [math]::Round((Get-Item $finalApk).Length / 1MB, 2)
    Write-Host ""
    Write-Host "APK ready: $finalApk ($sizeMb MB)" -ForegroundColor Green

    if ($Install) {
        $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
        Write-Host "Installing to connected device..." -ForegroundColor Cyan
        & $adb install -r $finalApk
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "adb install failed. Is a device connected with USB debugging on?"
        } else {
            & $adb shell am start -n "com.sarab.vision/.ArActivity"
            Write-Host "Launched Sarab Vision on device." -ForegroundColor Green
        }
    }
}
finally {
    Pop-Location
}
