param(
    [string]$Serial = 'emulator-5580',
    [string]$AdbPath = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [string]$ApkPath = (Join-Path $PSScriptRoot '../output/app-debug.apk'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../output/qa-vision-import')
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Only an isolated emulator is permitted.' }
$qaOutput = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $qaOutput | Out-Null
function Adb([string[]]$Arguments) {
    $response = & $AdbPath -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ') / $($response -join ' ')" }
    return ($response -join "`n")
}
if ((Adb @('shell','getprop','ro.hardware')).Trim() -notin @('ranchu','goldfish')) { throw 'Not an Android emulator.' }
foreach ($entry in @{airplane_mode_on='1';wifi_on='0';mobile_data='0'}.GetEnumerator()) {
    if ((Adb @('shell','settings','get','global',$entry.Key)).Trim() -ne $entry.Value) { throw 'Fixture must already be offline.' }
}
$qaHash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash
$qaInstalled = (Adb @('shell','pm','path','com.sarab.vision')).Trim() -replace '^package:',''
Adb @('pull',$qaInstalled,(Join-Path $qaOutput 'installed-base.apk')) | Out-Null
if ((Get-FileHash -LiteralPath (Join-Path $qaOutput 'installed-base.apk') -Algorithm SHA256).Hash -ne $qaHash) {
    throw 'Installed APK does not match the supplied artifact.'
}
Adb @('logcat','-c','-b','crash') | Out-Null
$qaResults = [Collections.Generic.List[object]]::new()
foreach ($scenario in @('photo_context','photo_background','photo_retry')) {
    $runId = [guid]::NewGuid().ToString('N')
    Adb @('shell','am','force-stop','com.sarab.vision') | Out-Null
    Adb @('shell','am','start','-n','com.sarab.vision/.VisionImportFixtureActivity',
        '--ez','allow_fixture','true','--es','run_id',$runId,'--es','scenario',$scenario) | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(48)
    $snapshot = $null
    $homeSent = $false
    $resumeSent = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        $raw = & $AdbPath -s $Serial shell run-as com.sarab.vision cat "files/vision-import-$scenario.json" 2>$null
        if ($LASTEXITCODE -eq 0) {
            try { $candidate = ($raw -join "`n") | ConvertFrom-Json } catch { $candidate = $null }
            if ($candidate -and $candidate.runId -eq $runId) { $snapshot = $candidate }
        }
        if ($snapshot) {
            if ($snapshot.phase -eq 'await_background' -and -not $homeSent) {
                Adb @('shell','input','keyevent','KEYCODE_HOME') | Out-Null
                $homeSent = $true
            }
            if ($snapshot.phase -eq 'await_resume' -and -not $resumeSent) {
                Adb @('shell','am','start','-n','com.sarab.vision/.VisionImportFixtureActivity') | Out-Null
                $resumeSent = $true
            }
            if ($snapshot.result -in @('PASS','FAIL')) { break }
        }
        Start-Sleep -Milliseconds 250
    }
    $snapshot | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $qaOutput "$scenario.json") -Encoding utf8
    if (-not $snapshot -or $snapshot.result -ne 'PASS') { throw "Import fixture failed: $scenario / $($snapshot.failure)" }
    if (-not $snapshot.syntheticPixelsOnly -or $snapshot.networkTransport -or $snapshot.speechRecognitionInvoked) {
        throw 'Synthetic/offline fixture boundary failed.'
    }
    if ($scenario -eq 'photo_background' -and (-not $homeSent -or -not $resumeSent)) { throw 'Actual background was not exercised.' }
    $qaResults.Add($snapshot)
    Adb @('shell','screencap','-p','/sdcard/vision-import-screen.png') | Out-Null
    Adb @('pull','/sdcard/vision-import-screen.png',(Join-Path $qaOutput "$scenario.png")) | Out-Null
    Write-Output "$scenario PASS; requests=$($snapshot.requestsStarted), checks=$($snapshot.checks.Count)"
}
$crashes = Adb @('logcat','-d','-b','crash','-v','brief')
$crashes | Set-Content -LiteralPath (Join-Path $qaOutput 'crash-log.txt') -Encoding utf8
if ($crashes -match 'FATAL EXCEPTION|Fatal signal|Process: com\.sarab\.vision') { throw 'Crash detected during fixture.' }
if ((Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash -ne $qaHash) { throw 'APK changed while checking.' }
[ordered]@{result='PASS';apkSha256=$qaHash;completedAt=[DateTime]::UtcNow.ToString('o');serial=$Serial;
    scenarios=$qaResults;realNetworkRequests=0;realSpeechRecognition=$false;crashes=0;
    scope='Actual controller/encoder and lifecycle; synthetic local responses, not photo picker or Gemini quality.'} |
    ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $qaOutput 'validation.json') -Encoding utf8
