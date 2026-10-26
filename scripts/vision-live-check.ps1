param(
    [string]$Serial = 'emulator-5580',
    [string]$AdbPath = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../output/qa-vision-live'),
    [string[]]$Scenarios = @('live_without_scene', 'queued_turns', 'manual_queue', 'revoke_pending', 'pinned_permission', 'selected_ui', 'tracking_move')
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Only an isolated emulator is supported.' }
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw 'ADB is unavailable.' }
$fixtureOutput = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $fixtureOutput -Force | Out-Null

function Invoke-QaAdb {
    param([string[]]$Arguments)
    $response = & $AdbPath -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ') / $($response -join ' ')" }
    return ($response -join "`n")
}
if ((Invoke-QaAdb -Arguments @('shell', 'getprop', 'ro.hardware')).Trim() -notin @('ranchu', 'goldfish')) {
    throw 'Not an emulator. No app activity was launched.'
}
foreach ($setting in @{ airplane_mode_on = '1'; wifi_on = '0'; mobile_data = '0' }.GetEnumerator()) {
    if ((Invoke-QaAdb -Arguments @('shell', 'settings', 'get', 'global', $setting.Key)).Trim() -ne $setting.Value) {
        throw "The emulator must be offline: $($setting.Key)."
    }
}
# This isolated fixture calls controller turns directly. Disable real TTS callbacks so an older
# audio completion cannot finish a later synthetic turn; audible speech is explicitly outside QA.
$ttsServices = Invoke-QaAdb -Arguments @('shell', 'cmd', 'package', 'query-services', '--brief', '-a', 'android.intent.action.TTS_SERVICE')
$disabledTtsPackages = @($ttsServices -split "`n" | ForEach-Object {
    if ($_.Trim() -match '^([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+$') { $Matches[1] }
} | Sort-Object -Unique)
foreach ($ttsPackage in $disabledTtsPackages) {
    Invoke-QaAdb -Arguments @('shell', 'pm', 'disable-user', '--user', '0', $ttsPackage) | Out-Null
}
$ttsServicesAfter = Invoke-QaAdb -Arguments @('shell', 'cmd', 'package', 'query-services', '--brief', '-a', 'android.intent.action.TTS_SERVICE')
if ($ttsServicesAfter -match '(?m)^\s*[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+\s*$') { throw 'A real TTS engine remains active during direct-controller fixtures.' }
Invoke-QaAdb -Arguments @('logcat', '-b', 'crash', '-c') | Out-Null

function Capture-Fixture([string]$name) {
    Invoke-QaAdb -Arguments @('shell', 'uiautomator', 'dump', '/sdcard/vision-live-window.xml') | Out-Null
    Invoke-QaAdb -Arguments @('pull', '/sdcard/vision-live-window.xml', (Join-Path $fixtureOutput "$name.xml")) | Out-Null
    Invoke-QaAdb -Arguments @('shell', 'screencap', '-p', '/sdcard/vision-live-screen.png') | Out-Null
    Invoke-QaAdb -Arguments @('pull', '/sdcard/vision-live-screen.png', (Join-Path $fixtureOutput "$name.png")) | Out-Null
    [xml]$tree = Get-Content -LiteralPath (Join-Path $fixtureOutput "$name.xml") -Raw
    return $tree
}
function Read-Texts([xml]$tree) { return @($tree.SelectNodes('//node') | ForEach-Object { [string]$_.text }) }

$results = [System.Collections.Generic.List[object]]::new()
foreach ($scenario in $Scenarios) {
    $runId = [guid]::NewGuid().ToString('N')
    Invoke-QaAdb -Arguments @('shell', 'am', 'force-stop', 'com.sarab.vision') | Out-Null
    Invoke-QaAdb -Arguments @('shell', 'am', 'start', '-n', 'com.sarab.vision/.VisionLiveFixtureActivity',
        '--ez', 'allow_fixture', 'true', '--es', 'scenario', $scenario, '--es', 'run_id', $runId) | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(40)
    $snapshot = $null
    $backgroundSent = $false
    $resumeSent = $false
    $trackingCaptured = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        $raw = & $AdbPath -s $Serial shell run-as com.sarab.vision cat "files/vision-live-$scenario.json" 2>$null
        if ($LASTEXITCODE -eq 0) {
            try { $candidate = ($raw -join "`n") | ConvertFrom-Json } catch { $candidate = $null }
            if ($candidate -and $candidate.runId -eq $runId) { $snapshot = $candidate }
        }
        if ($snapshot) {
            if ($snapshot.phase -eq 'tracking_moved' -and -not $trackingCaptured) {
                Capture-Fixture 'tracking-moved' | Out-Null
                $trackingCaptured = $true
            }
            if ($snapshot.phase -eq 'await_background_clear' -and -not $backgroundSent) {
                Start-Sleep -Milliseconds 400
                Capture-Fixture 'pinned-before-background' | Out-Null
                Invoke-QaAdb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
                $backgroundSent = $true
            }
            if ($snapshot.phase -eq 'await_resume' -and -not $resumeSent) {
                Invoke-QaAdb -Arguments @('shell', 'am', 'start', '-n', 'com.sarab.vision/.VisionLiveFixtureActivity') | Out-Null
                $resumeSent = $true
            }
            if ($snapshot.result -in @('PASS', 'FAIL')) { break }
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $snapshot -or $snapshot.result -ne 'PASS') {
        $details = if ($snapshot) { $snapshot | ConvertTo-Json -Compress } else { 'No report for this run.' }
        throw "Live fixture failed ($scenario): $details"
    }
    if (-not $snapshot.syntheticPixelsOnly -or $snapshot.networkTransport -or $snapshot.speechRecognitionInvoked) {
        throw "Fixture boundary failed ($scenario)."
    }
    if ($scenario -eq 'tracking_move' -and (-not $trackingCaptured -or
        'occlusion_hides_eligible_target' -notin $snapshot.checks -or $snapshot.trackedCentersNormalized.Count -ne 5)) {
        throw 'Tracked motion or occlusion evidence is incomplete.'
    }
    $snapshot | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $fixtureOutput "$scenario.json") -Encoding utf8
    Start-Sleep -Milliseconds 500
    $tree = Capture-Fixture $scenario
    $texts = Read-Texts $tree
    $microphones = @($tree.SelectNodes('//node') | Where-Object { $_.'resource-id' -match '(^|/)vision-microphone$' })
    $selectedPanels = @($tree.SelectNodes('//node') | Where-Object { $_.'resource-id' -match '(^|/)vision-landmark-panel$' })
    if ($scenario -ne 'selected_ui') {
        if ($selectedPanels.Count -gt 0) { throw "Live conversation unexpectedly opened a selected-landmark panel: $scenario" }
        if ($microphones.Count -lt 1) { throw "Live microphone control was not rendered: $scenario" }
        if ($scenario -in @('live_without_scene', 'queued_turns', 'manual_queue', 'revoke_pending')) {
            if (-not $snapshot.conversationActive -or $snapshot.pinned -or $snapshot.historyEntries -lt 2) {
                throw "Live conversation state regressed: $scenario"
            }
            if (-not ($texts -match 'LOCAL QA — الرد المحلي')) { throw "The compact conversation reply is not visible: $scenario" }
        }
    } else {
        if ($selectedPanels.Count -ne 1 -or -not ($texts -match 'مقام إبراهيم') -or
            -not ($tree.SelectNodes('//node') | Where-Object { $_.'resource-id' -match '(^|/)vision-landmark-close$' })) {
            throw 'Selected landmark panel is not rendered.'
        }
        $allTexts = [System.Collections.Generic.List[string]]::new()
        foreach ($text in $texts) { $allTexts.Add($text) }
        $scrollTree = $tree
        for ($scroll = 1; $scroll -le 12; $scroll++) {
            $body = $scrollTree.SelectNodes('//node') | Where-Object { $_.'resource-id' -match '(^|/)vision-landmark-body$' } | Select-Object -First 1
            if (-not $body -or $body.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'No visible selected-panel scroll body.' }
            $x = [string][int](([int]$Matches[1] + [int]$Matches[3]) / 2)
            $startY = [string]([int]$Matches[4] - 5)
            $endY = [string]([int]$Matches[2] + 5)
            Invoke-QaAdb -Arguments @('shell', 'input', 'swipe', $x, $startY, $x, $endY, '150') | Out-Null
            Start-Sleep -Milliseconds 200
            $scrolled = Capture-Fixture "selected-ui-scroll-$scroll"
            $scrollTree = $scrolled
            $scrollTexts = Read-Texts $scrolled
            if (-not ($scrolled.SelectNodes('//node') | Where-Object { $_.'resource-id' -match '(^|/)vision-microphone$' })) {
                throw 'The selected-detail microphone did not remain visible while scrolling.'
            }
            foreach ($text in $scrollTexts) { $allTexts.Add($text) }
            if (($allTexts -contains 'تذكير من القرآن عن مقام إبراهيم') -and
                ($allTexts -contains 'وَاتَّخِذُوا مِن مَّقَامِ إِبْرَاهِيمَ مُصَلًّى') -and
                ($allTexts -contains 'القرآن الكريم — البقرة ١٢٥')) { break }
        }
        if ('تذكير من القرآن عن مقام إبراهيم' -notin $allTexts -or
            'وَاتَّخِذُوا مِن مَّقَامِ إِبْرَاهِيمَ مُصَلًّى' -notin $allTexts -or
            'القرآن الكريم — البقرة ١٢٥' -notin $allTexts -or
            -not ($allTexts -match 'ليس صيغة دعاء خاصة بالمقام')) {
            throw 'Selected landmark devotional copy or curated source control is absent.'
        }
    }
    $results.Add($snapshot)
    Write-Output "$scenario PASS; requests=$($snapshot.requestsStarted), revision=$($snapshot.conversationRevision), checks=$($snapshot.checks.Count)"
}
Invoke-QaAdb -Arguments @('shell', 'run-as', 'com.sarab.vision', 'cat', 'files/diagnostics/events.log') |
    Set-Content -LiteralPath (Join-Path $fixtureOutput 'events.log') -Encoding utf8
$crashes = Invoke-QaAdb -Arguments @('logcat', '-d', '-b', 'crash', '-v', 'brief')
$crashes | Set-Content -LiteralPath (Join-Path $fixtureOutput 'crash-log.txt') -Encoding utf8
if ($crashes -match 'FATAL EXCEPTION|Fatal signal|Process: com\.sarab\.vision') { throw 'A crash occurred during the live fixtures; inspect crash-log.txt.' }
[ordered]@{
    completedAt = [DateTime]::UtcNow.ToString('o')
    result = 'PASS'
    serial = $Serial
    scenarios = $results
    productionNetworkRequests = 0
    disabledEmulatorTtsPackages = $disabledTtsPackages
    resolvedTtsServicesAfter = $ttsServicesAfter.Trim()
    pixels = 'Synthetic local bitmaps only'
    limitation = 'Production controller/encoder/UI with delayed memory transport. Real speech recognition, speech audibility, OS permission grant sheets, Gemini accuracy and physical camera tracking are not validated by this fixture. Permission pause is simulated; HOME/onStop is actual.'
} | ConvertTo-Json -Depth 9 | Set-Content -LiteralPath (Join-Path $fixtureOutput 'validation.json') -Encoding utf8
Write-Output "All $($results.Count) live fixture scenarios passed. Artifacts: $fixtureOutput"
