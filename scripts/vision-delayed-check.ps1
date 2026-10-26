param(
    [string]$Serial = 'emulator-5580',
    [string]$AdbPath = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../output/qa-vision-delay')
)

$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'This fixture is restricted to an isolated emulator.' }
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw 'ADB is unavailable.' }
$fixtureOutput = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $fixtureOutput -Force | Out-Null

function Invoke-QaAdb {
    param([string[]]$Arguments)
    $response = & $AdbPath -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ') / $($response -join ' ')" }
    return ($response -join "`n")
}

$hardware = Invoke-QaAdb -Arguments @('shell', 'getprop', 'ro.hardware')
if ($hardware.Trim() -notin @('ranchu', 'goldfish')) { throw 'Not an Android emulator; stopping before app launch.' }
foreach ($setting in @{ airplane_mode_on = '1'; wifi_on = '0'; mobile_data = '0' }.GetEnumerator()) {
    $actual = Invoke-QaAdb -Arguments @('shell', 'settings', 'get', 'global', $setting.Key)
    if ($actual.Trim() -ne $setting.Value) { throw "Fixture must be offline before use: $($setting.Key)." }
}

$results = [System.Collections.Generic.List[object]]::new()
foreach ($scenario in @('manual_signature', 'manual_move', 'auto_move', 'source_revoke', 'background_revoke')) {
    $runId = [guid]::NewGuid().ToString('N')
    Invoke-QaAdb -Arguments @('shell', 'am', 'force-stop', 'com.sarab.vision') | Out-Null
    Invoke-QaAdb -Arguments @('shell', 'am', 'start', '-n', 'com.sarab.vision/.VisionDelayFixtureActivity',
        '--ez', 'allow_fixture', 'true', '--es', 'scenario', $scenario, '--es', 'run_id', $runId) | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(28)
    $snapshot = $null
    $backgroundSent = $false
    $resumeSent = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        $raw = & $AdbPath -s $Serial shell run-as com.sarab.vision cat "files/vision-delay-$scenario.json" 2>$null
        if ($LASTEXITCODE -eq 0) {
            try { $candidate = ($raw -join "`n") | ConvertFrom-Json } catch { $candidate = $null }
            if ($candidate -and $candidate.runId -eq $runId) { $snapshot = $candidate }
        }
        if ($snapshot) {
            if ($snapshot.phase -eq 'await_background' -and -not $backgroundSent) {
                Invoke-QaAdb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
                $backgroundSent = $true
            }
            if ($snapshot.phase -eq 'await_resume' -and -not $resumeSent) {
                Invoke-QaAdb -Arguments @('shell', 'am', 'start', '-n', 'com.sarab.vision/.VisionDelayFixtureActivity') | Out-Null
                $resumeSent = $true
            }
            if ($snapshot.result -in @('PASS', 'FAIL')) { break }
        }
        Start-Sleep -Milliseconds 300
    }
    if (-not $snapshot -or $snapshot.result -ne 'PASS') {
        $details = if ($snapshot) { $snapshot | ConvertTo-Json -Compress } else { 'No report for current run.' }
        throw "Delayed fixture failed ($scenario): $details"
    }
    if (-not $snapshot.encoderSourceSignatureVerified -or -not $snapshot.syntheticPixelsOnly -or $snapshot.networkTransport) {
        throw "Fixture boundary or encoder regression ($scenario)."
    }
    if ($scenario -eq 'manual_move' -and (-not $snapshot.askCapturedImageVerified -or -not $snapshot.pauseRestorationVerified)) {
        throw 'Captured-image follow-up or pre-pin pause restoration regressed.'
    }
    $snapshot | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $fixtureOutput "$scenario.json") -Encoding utf8
    # Wait for Compose's result panel before capturing the actual production UI.
    Start-Sleep -Milliseconds 500
    Invoke-QaAdb -Arguments @('shell', 'uiautomator', 'dump', '/sdcard/vision-delay-window.xml') | Out-Null
    Invoke-QaAdb -Arguments @('pull', '/sdcard/vision-delay-window.xml', (Join-Path $fixtureOutput "$scenario.xml")) | Out-Null
    Invoke-QaAdb -Arguments @('shell', 'screencap', '-p', '/sdcard/vision-delay-screen.png') | Out-Null
    Invoke-QaAdb -Arguments @('pull', '/sdcard/vision-delay-screen.png', (Join-Path $fixtureOutput "$scenario.png")) | Out-Null
    [xml]$uiTree = Get-Content -LiteralPath (Join-Path $fixtureOutput "$scenario.xml") -Raw
    $uiTexts = @($uiTree.SelectNodes('//node') | ForEach-Object { [string]$_.text })
    if ($scenario -ne 'auto_move') {
        if (-not ($uiTree.SelectNodes('//node') | Where-Object { $_.'content-desc' -eq 'العودة للبث' }) -or
            -not ($uiTexts -match 'اللقطة المحددة')) {
            throw "Controller passed but its captured-result panel did not open ($scenario)."
        }
    } elseif ($uiTree.SelectNodes('//node') | Where-Object { $_.'content-desc' -eq 'العودة للبث' }) {
        throw 'An automatic moved result unexpectedly opened the captured-result panel.'
    }
    $results.Add($snapshot)
    Write-Output "$scenario PASS; started=$($snapshot.requestsStarted), completed=$($snapshot.requestsCompleted), pinned=$($snapshot.pinned)"
}
Invoke-QaAdb -Arguments @('shell', 'run-as', 'com.sarab.vision', 'cat', 'files/diagnostics/events.log') |
    Set-Content -LiteralPath (Join-Path $fixtureOutput 'events.log') -Encoding utf8
Invoke-QaAdb -Arguments @('logcat', '-d', '-b', 'crash', '-v', 'brief') |
    Set-Content -LiteralPath (Join-Path $fixtureOutput 'crash-log.txt') -Encoding utf8
[ordered]@{
    completedAt = [DateTime]::UtcNow.ToString('o')
    result = 'PASS'
    serial = $Serial
    scenarios = $results
    productionNetworkRequests = 0
    pixels = 'Synthetic local bitmaps only'
    limitation = 'In-memory delayed transport exercises production encoder/controller/UI; it does not test a physical camera or Gemini service.'
} | ConvertTo-Json -Depth 9 | Set-Content -LiteralPath (Join-Path $fixtureOutput 'validation.json') -Encoding utf8
Write-Output "All $($results.Count) delayed scenarios passed. Artifacts: $fixtureOutput"
