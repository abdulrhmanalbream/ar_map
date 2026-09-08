<#
    Sarab Vision - build helper

    Mirrors source to an ASCII path, uses Android Studio JBR 21, and gives
    Java's local sockets a short ASCII temp path. Some Windows profile TEMP
    paths make Gradle fail before compilation with a loopback socket error.

    .\build.ps1               # debug APK (arm64 unless -Install selects a device)
    .\build.ps1 -Test         # debug APK + JVM unit tests + debug lint
    .\build.ps1 -Install      # debug APK + install + launch camera navigation
    .\build.ps1 -Release      # unsigned release APK
    .\build.ps1 -Clean        # safely recreate the build mirror
#>

param(
    [switch]$Release,
    [switch]$Install,
    [switch]$Clean,
    [switch]$Test
)

$PreviousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Stop"
$PreviousJavaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME", "Process")
$PreviousJavaToolOptions = [Environment]::GetEnvironmentVariable("JAVA_TOOL_OPTIONS", "Process")
$BuildExitCode = 0
$LocationPushed = $false
$SocketDir = $null
$SocketDirectoryCreated = $false

function Assert-BuildMirror {
    param([string]$SourcePath, [string]$MirrorPath, [string]$LocalDataRoot)

    $source = [IO.Path]::GetFullPath($SourcePath).TrimEnd([char[]]"\/")
    $target = [IO.Path]::GetFullPath($MirrorPath).TrimEnd([char[]]"\/")
    $expected = [IO.Path]::GetFullPath((Join-Path $LocalDataRoot "SarabVisionBuild")).TrimEnd([char[]]"\/")
    if (-not $target.Equals($expected, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing mirror operation outside the intended LOCALAPPDATA\SarabVisionBuild directory: $target"
    }
    if ($source.Equals($target, [StringComparison]::OrdinalIgnoreCase) -or
        $source.StartsWith($target + "\", [StringComparison]::OrdinalIgnoreCase) -or
        $target.StartsWith($source + "\", [StringComparison]::OrdinalIgnoreCase)) {
        throw "Source and build mirror must be separate, non-overlapping directories."
    }
    if (Test-Path -LiteralPath $target) {
        $item = Get-Item -LiteralPath $target -Force
        if (-not $item.PSIsContainer -or
            ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Refusing recursive changes to a file, symbolic link or junction: $target"
        }
        $resolved = (Resolve-Path -LiteralPath $target).ProviderPath.TrimEnd([char[]]"\/")
        if (-not $resolved.Equals($expected, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Resolved build mirror is outside the intended directory: $resolved"
        }
    }
}

try {
    if ($Release -and $Install) {
        throw "Release APKs are unsigned. Use -Install for debug, or sign a release APK separately."
    }
    if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        throw "LOCALAPPDATA is unavailable; cannot establish a safe build mirror."
    }
    $SourceDir = (Resolve-Path -LiteralPath $PSScriptRoot).ProviderPath
    $LocalDataRoot = (Resolve-Path -LiteralPath $env:LOCALAPPDATA).ProviderPath
    $BuildDir = [IO.Path]::GetFullPath((Join-Path $LocalDataRoot "SarabVisionBuild"))
    Assert-BuildMirror $SourceDir $BuildDir $LocalDataRoot
    if ($BuildDir -match '[^\x00-\x7F]') {
        throw "The intended build mirror must have an ASCII path: $BuildDir"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $SourceDir "gradlew.bat") -PathType Leaf)) {
        throw "No Gradle wrapper found in the source directory: $SourceDir"
    }

    # JDK 25 is incompatible with this Gradle/AGP combination.
    $Jbr = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path -LiteralPath (Join-Path $Jbr "bin\java.exe") -PathType Leaf) {
        $env:JAVA_HOME = $Jbr
    } else {
        Write-Warning "Android Studio JBR 21 is unavailable at $Jbr; using existing JAVA_HOME."
    }

    # Preserve caller JVM options; our socket setting wins if already specified.
    $SocketDir = [IO.Path]::GetFullPath((Join-Path $env:SystemRoot ("Temp\SarabJdk-{0}" -f $PID)))
    if ($SocketDir -match '[^\x21-\x7E]' -or $SocketDir.Length -gt 70) {
        throw "Java socket temp directory must be short ASCII without spaces: $SocketDir"
    }
    if (Test-Path -LiteralPath $SocketDir) {
        $socketItem = Get-Item -LiteralPath $SocketDir -Force
        if (-not $socketItem.PSIsContainer -or
            ($socketItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Socket temp directory must be a real directory: $SocketDir"
        }
    } else {
        New-Item -ItemType Directory -Path $SocketDir | Out-Null
        $SocketDirectoryCreated = $true
    }
    $socketOption = "-Djdk.net.unixdomain.tmpdir=" + $SocketDir.Replace('\', '/')
    $env:JAVA_TOOL_OPTIONS = (($PreviousJavaToolOptions, $socketOption) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " "

    $deviceAbi = "arm64-v8a"
    $deviceSerial = $null
    $adbPath = $null
    if ($Install) {
        $adbPath = Join-Path $LocalDataRoot "Android\Sdk\platform-tools\adb.exe"
        if (-not (Test-Path -LiteralPath $adbPath -PathType Leaf)) {
            $adbCommand = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue
            $adbPath = if ($adbCommand) { $adbCommand.Source } else { $null }
        }
        if (-not $adbPath) { throw "adb is unavailable. Install SDK platform-tools or add adb to PATH." }
        # Starting adb may write an informational daemon message to stderr.
        $ErrorActionPreference = "Continue"
        $devicesOutput = @(& $adbPath devices 2>&1)
        $adbExitCode = $LASTEXITCODE
        $ErrorActionPreference = "Stop"
        if ($adbExitCode -ne 0) { throw "Unable to enumerate adb devices: $devicesOutput" }
        $devices = @($devicesOutput | Where-Object { "$_" -match '^\S+\s+device\s*$' })
        if ($devices.Count -ne 1) {
            throw "Installation requires exactly one authorised adb device; found $($devices.Count)."
        }
        $deviceSerial = ("$($devices[0])" -split '\s+')[0]
        $deviceAbi = (& $adbPath -s $deviceSerial shell getprop ro.product.cpu.abi | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) { throw "Unable to read the ABI of device $deviceSerial." }
        if ($deviceAbi -notin @("arm64-v8a", "armeabi-v7a")) {
            throw "Device $deviceSerial uses unsupported ABI '$deviceAbi'. This app builds ARM APKs only."
        }
    }

    if ($Clean -and (Test-Path -LiteralPath $BuildDir)) {
        Assert-BuildMirror $SourceDir $BuildDir $LocalDataRoot
        Write-Host "Cleaning mirror at $BuildDir" -ForegroundColor Yellow
        Remove-Item -LiteralPath $BuildDir -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
    Assert-BuildMirror $SourceDir $BuildDir $LocalDataRoot
    Write-Host "Syncing source -> $BuildDir" -ForegroundColor Cyan
    # Never traverse junctions. Build caches and output APKs are not source.
    $roboArgs = @(
        $SourceDir, $BuildDir, "/MIR", "/XJ", "/R:2", "/W:1",
        "/XD", ".git", "build", ".gradle", ".idea", "output", "node_modules", "dist", "data",
        "/NFL", "/NDL", "/NJH", "/NJS", "/NP", "/NS", "/NC"
    )
    & robocopy @roboArgs | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed with exit code $LASTEXITCODE" }

    $tasks = @($(if ($Release) { "assembleRelease" } else { "assembleDebug" }))
    if ($Test) { $tasks += "testDebugUnitTest", "lintDebug" }
    Push-Location -LiteralPath $BuildDir
    $LocationPushed = $true
    Write-Host "Running gradlew $($tasks -join ' ')" -ForegroundColor Cyan
    & .\gradlew.bat @tasks --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }

    $variant = if ($Release) { "release" } else { "debug" }
    $apkDirectory = Join-Path $BuildDir "app\build\outputs\apk\$variant"
    $allApks = @(Get-ChildItem -LiteralPath $apkDirectory -Recurse -File -Filter "*.apk")
    $apk = $allApks | Where-Object { $_.Name -like "*-$deviceAbi-*" } | Select-Object -First 1
    if (-not $apk) { throw "Gradle produced no $variant APK for requested ABI $deviceAbi." }
    Write-Host "Selected $($apk.Name) for ABI $deviceAbi" -ForegroundColor DarkGray

    $outDir = Join-Path $SourceDir "output"
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $finalName = if ($Release) { "app-release-unsigned.apk" } else { "app-debug.apk" }
    $finalApk = Join-Path $outDir $finalName
    Copy-Item -LiteralPath $apk.FullName -Destination $finalApk -Force
    # Retain ABI identity alongside the stable download/install filename.
    Copy-Item -LiteralPath $apk.FullName -Destination (Join-Path $outDir $apk.Name) -Force
    $sizeMb = [math]::Round((Get-Item -LiteralPath $finalApk).Length / 1MB, 2)
    Write-Host "APK ready: $finalApk ($sizeMb MB)" -ForegroundColor Green
    $watchApk = Join-Path $BuildDir "wear\build\outputs\apk\$variant\wear-$variant$(if ($Release) { '-unsigned' }).apk"
    if (Test-Path -LiteralPath $watchApk) {
        Copy-Item -LiteralPath $watchApk -Destination (Join-Path $outDir "wear-$variant$(if ($Release) { '-unsigned' }).apk") -Force
        Write-Host "Watch APK ready in output" -ForegroundColor Green
    }

    if ($Install) {
        Write-Host "Installing to $deviceSerial..." -ForegroundColor Cyan
        & $adbPath -s $deviceSerial install -r $finalApk
        if ($LASTEXITCODE -ne 0) { throw "adb install failed with exit code $LASTEXITCODE" }
        & $adbPath -s $deviceSerial shell am start -W -n "com.sarab.vision/.ArNavActivity"
        if ($LASTEXITCODE -ne 0) { throw "Could not launch camera navigation on $deviceSerial." }
        Write-Host "Launched Sarab Vision camera navigation." -ForegroundColor Green
    }
}
catch {
    $BuildExitCode = 1
    Write-Error $_ -ErrorAction Continue
}
finally {
    [Environment]::SetEnvironmentVariable("JAVA_HOME", $PreviousJavaHome, "Process")
    [Environment]::SetEnvironmentVariable("JAVA_TOOL_OPTIONS", $PreviousJavaToolOptions, "Process")
    # Delete only our empty socket directory; a lingering JVM may still own it.
    if ($SocketDirectoryCreated -and $SocketDir) {
        try { [IO.Directory]::Delete($SocketDir, $false) } catch { }
    }
    if ($LocationPushed) { Pop-Location }
    $ErrorActionPreference = $PreviousErrorActionPreference
}

# Environment restoration runs before every success/failure exit.
exit $BuildExitCode
