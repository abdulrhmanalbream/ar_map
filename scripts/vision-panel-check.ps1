param(
    [string]$Serial = 'emulator-5580',
    [string]$AdbPath = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../output/qa-panel-readability'),
    [string[]]$Cases = @('ar-portrait-1', 'ar-landscape-1', 'en-portrait-1', 'en-landscape-1',
        'ar-portrait-1.3', 'ar-landscape-1.3', 'en-portrait-1.3', 'en-landscape-1.3'),
    [string[]]$ConversationCases = @('ar-portrait-1.3', 'en-landscape-1.3', 'en-portrait-1.3'),
    [ValidateSet('Opaque','Hologram')][string]$Material = 'Opaque'
)
$ErrorActionPreference = 'Stop'
if ($Serial -ne 'emulator-5580') { throw 'This fixture owns only emulator-5580.' }
$qaOutput = [IO.Path]::GetFullPath($OutputDirectory)
$qaRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$apk = Join-Path $qaRepo 'output/app-debug.apk'
$startingApkHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
New-Item -ItemType Directory -Path $qaOutput -Force | Out-Null
function Adb([string[]]$Arguments) {
    $response = & $AdbPath -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ') / $($response -join ' ')" }
    return $response -join "`n"
}
if ((Adb @('shell', 'getprop', 'ro.hardware')).Trim() -notin @('ranchu', 'goldfish')) { throw 'Not an emulator.' }
$installedPath = ((Adb @('shell','pm','path','com.sarab.vision')) -split "`n" | Where-Object { $_ -match '/base\.apk$' } | Select-Object -First 1) -replace '^package:', ''
if (-not $installedPath) { throw 'No installed test APK.' }
$installedHash = ((Adb @('shell','sha256sum',$installedPath.Trim())) -split '\s+')[0].ToUpperInvariant()
if ($installedHash -ne $startingApkHash) { throw 'Installed APK does not match the artifact being validated.' }
foreach ($setting in @{airplane_mode_on='1'; wifi_on='0'; mobile_data='0'}.GetEnumerator()) {
    if ((Adb @('shell', 'settings', 'get', 'global', $setting.Key)).Trim() -ne $setting.Value) { throw 'Offline emulator required.' }
}
# The owned emulator's first fullscreen tutorial can otherwise intercept a marker tap.
Adb @('shell','settings','put','secure','immersive_mode_confirmations','confirmed') | Out-Null
# Screenshot/touch coordinates must describe settled layouts, not Android's rotation crossfade.
foreach ($animationSetting in @('window_animation_scale','transition_animation_scale','animator_duration_scale')) {
    Adb @('shell','settings','put','global',$animationSetting,'0') | Out-Null
}
$ttsServices = Adb @('shell','cmd','package','query-services','--brief','-a','android.intent.action.TTS_SERVICE')
foreach ($ttsPackage in @($ttsServices -split "`n" | ForEach-Object {
    if ($_.Trim() -match '^([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+$') { $Matches[1] }
} | Sort-Object -Unique)) { Adb @('shell','pm','disable-user','--user','0',$ttsPackage) | Out-Null }
Adb @('logcat', '-b', 'crash', '-c') | Out-Null
function Ui([string]$name, [bool]$Screenshot = $true) {
    Adb @('shell','uiautomator','dump','/sdcard/vision-panel.xml') | Out-Null
    Adb @('pull','/sdcard/vision-panel.xml',"$qaOutput/$name.xml") | Out-Null
    if ($Screenshot) {
        Adb @('shell','screencap','-p','/sdcard/vision-panel.png') | Out-Null
        Adb @('pull','/sdcard/vision-panel.png',"$qaOutput/$name.png") | Out-Null
    }
    return [xml](Get-Content -LiteralPath "$qaOutput/$name.xml" -Raw)
}
function Tag([xml]$Tree, [string]$Id) {
    $node = $Tree.SelectNodes('//node') | Where-Object { $_.'resource-id' -eq $Id -or $_.'resource-id' -eq "com.sarab.vision:id/$Id" } | Select-Object -First 1
    if (-not $node) { throw "Missing visible control: $Id" }
    return $node
}
function Bounds($Node) {
    if ([string]$Node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Invalid target bounds.' }
    return @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
}
function Tap($Node) {
    $b = Bounds $Node
    Adb @('shell','input','tap',[string][int](($b[0]+$b[2])/2),[string][int](($b[1]+$b[3])/2)) | Out-Null
    Start-Sleep -Milliseconds 250
}
function Read-Report([string]$RunId) {
    $raw = & $AdbPath -s $Serial shell run-as com.sarab.vision cat files/vision-panel-preview.json 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    try { $value = ($raw -join "`n") | ConvertFrom-Json } catch { return $null }
    if ($value.runId -eq $RunId) { return $value }
    return $null
}
function Surface-Sample([string]$ImagePath, $Area) {
    if ($Material -eq 'Hologram') { return Hologram-Sample $ImagePath $Area }
    Add-Type -AssemblyName System.Drawing
    $bitmap = [System.Drawing.Bitmap]::new($ImagePath)
    $samples = 0
    $opaquePaper = 0
    try {
        for ($y = $Area[1]+40; $y -lt $Area[3]-40; $y += 13) {
            for ($x = $Area[0]+40; $x -lt $Area[2]-40; $x += 13) {
                $pixel = $bitmap.GetPixel($x,$y)
                $samples++
                if ($pixel.R -eq 255 -and $pixel.G -eq 253 -and $pixel.B -eq 243) { $opaquePaper++ }
            }
        }
    } finally { $bitmap.Dispose() }
    if ($samples -eq 0 -or $opaquePaper / $samples -lt .40) { throw 'The expected opaque reading color is not present over the busy scene.' }
    return [ordered]@{expectedRgb='FFFDF3'; sampledPixels=$samples; exactOpaquePaperPixels=$opaquePaper; exactColorFraction=[math]::Round($opaquePaper/$samples,3)}
}
function Hologram-Sample([string]$ImagePath, $Area) {
    # These exact checker colors and exclusions derive from VisionPanelPreviewActivity's
    # syntheticPicture(), not a camera guess. Sample only flat cell interiors; omit the
    # drawn people, central object, printed QA text, and interpolation at cell boundaries.
    Add-Type -AssemblyName System.Drawing
    $bitmap = [System.Drawing.Bitmap]::new($ImagePath)
    $scale = [math]::Max($bitmap.Width / 960.0, $bitmap.Height / 720.0)
    $cropX = (960 * $scale - $bitmap.Width) / 2
    $cropY = (720 * $scale - $bitmap.Height) / 2
    $checker = @(@(255,252,236),@(215,205,176),@(142,171,160),@(249,213,120))
    $textColors = @(@(243,255,249),@(216,244,229),@(183,255,224))
    $tint = @(16,44,37)
    $people = @(0..45 | ForEach-Object { ,@(($_ * 97 % 920 + 20),($_ * 71 % 620 + 80)) })
    $eligible = 0
    $matching = 0
    $paleText = 0
    $classes = [System.Collections.Generic.HashSet[int]]::new()
    $fit = [System.Collections.Generic.List[double]]::new()
    try {
        for ($y = $Area[1]+40; $y -lt $Area[3]-40; $y += 9) {
            $sy = ($y + $cropY) / $scale
            $relativeY = ($y-$Area[1]) / [double]($Area[3]-$Area[1])
            $alpha = if ($relativeY -lt .5) { .34 + (.18-.34) * $relativeY * 2 } else { .18 + (.26-.18) * ($relativeY-.5) * 2 }
            for ($x = $Area[0]+40; $x -lt $Area[2]-40; $x += 9) {
                $pixel = $bitmap.GetPixel($x,$y)
                $actual = @([int]$pixel.R,[int]$pixel.G,[int]$pixel.B)
                foreach ($textColor in $textColors) {
                    if ([math]::Abs($actual[0]-$textColor[0]) -le 2 -and [math]::Abs($actual[1]-$textColor[1]) -le 2 -and [math]::Abs($actual[2]-$textColor[2]) -le 2) {
                        $paleText++; break
                    }
                }
                $sx = ($x + $cropX) / $scale
                if ($sy -lt 75 -or $sy -gt 625 -or $sx % 30 -lt 3 -or $sx % 30 -gt 27 -or $sy % 30 -lt 3 -or $sy % 30 -gt 27) { continue }
                if ($sx -ge 353 -and $sx -le 607 -and $sy -ge 213 -and $sy -le 479) { continue }
                $personHit = $false
                foreach ($person in $people) {
                    if ($sx -ge $person[0]-19 -and $sx -le $person[0]+19 -and $sy -ge $person[1]-16 -and $sy -le $person[1]+58) { $personHit=$true; break }
                }
                if ($personHit) { continue }
                $eligible++
                $class = ([int][math]::Floor($sy/30)*3 + [int][math]::Floor($sx/30)) % 4
                $base = $checker[$class]
                $matches = $true
                $numerator = 0.0
                $denominator = 0.0
                for ($channel=0; $channel -lt 3; $channel++) {
                    $expected = $tint[$channel]*$alpha + $base[$channel]*(1-$alpha)
                    if ([math]::Abs($actual[$channel]-$expected) -gt 8) { $matches=$false }
                    $difference = $base[$channel]-$tint[$channel]
                    $numerator += ($base[$channel]-$actual[$channel])*$difference
                    $denominator += $difference*$difference
                }
                if ($matches) {
                    $matching++
                    [void]$classes.Add($class)
                    $fit.Add($numerator/$denominator)
                }
            }
        }
    } finally { $bitmap.Dispose() }
    if ($eligible -lt 80 -or $matching -lt 60 -or $matching/[double]$eligible -lt .20 -or $classes.Count -lt 3) {
        throw "Known camera checker pattern is not visible through the intended gradient: eligible=$eligible matches=$matching colors=$($classes.Count)."
    }
    if ($paleText -lt 12) { throw 'Expected white/mint foreground text is not rendered in the panel.' }
    $minimum = ($fit | Measure-Object -Minimum).Minimum
    $maximum = ($fit | Measure-Object -Maximum).Maximum
    return [ordered]@{material='translucent_hologram'; tintRgb='102C25'; intendedOpacityStops=@(.34,.18,.26);
        intendedCameraContributionPercent=@(66,82); blur=$false; knownCheckerSamples=$eligible; matchingBlendSamples=$matching;
        matchedUnderlyingColors=$classes.Count; matchingFraction=[math]::Round($matching/[double]$eligible,3);
        measuredOpacityRange=@([math]::Round($minimum,3),[math]::Round($maximum,3)); whiteMintTextSamples=$paleText;
        method='Expected pixels computed from flat synthetic checker cells after exact center-crop mapping; people/object/printed text/tile edges excluded. Gradient prediction tolerance8 per RGB channel; opaque text/button/shadow pixels are excluded by mismatch.';
        contrast='Variable with the underlying scene; this is transparency evidence, not a fixed contrast guarantee.'}
}
function Assert-Panel([xml]$Tree, $Snapshot, [bool]$Landscape) {
    $panel = Bounds (Tag $Tree 'vision-landmark-panel')
    $microphone = Bounds (Tag $Tree 'vision-microphone')
    $header = Bounds (Tag $Tree 'vision-landmark-header')
    $body = Bounds (Tag $Tree 'vision-landmark-body')
    foreach ($control in @($microphone, $header, $body)) {
        if ($control[0] -lt $panel[0] -or $control[1] -lt $panel[1] -or $control[2] -gt $panel[2] -or $control[3] -gt $panel[3]) { throw 'Panel control lies outside its reading surface.' }
    }
    if ($body[3] - $body[1] -lt 30 -or $body[1] -lt $header[3] -or $body[3] -gt $microphone[1]) { throw 'Scrollable body is obscured by fixed controls.' }
    if ($microphone[3] - $microphone[1] -lt 125) { throw 'Microphone is smaller than the 48dp target at density420.' }
    if ($Snapshot.windowRoots -ne 1 -or -not $Snapshot.resumed -or -not $Snapshot.pinned) { throw 'Selected panel must remain in the one resumed Activity window.' }
    if ($Landscape) {
        if (($panel[2]-$panel[0]) -gt $Snapshot.width * .47) { throw 'Landscape panel obscures over 47% of the camera width.' }
    } elseif (($panel[3]-$panel[1]) -gt $Snapshot.height * .60) { throw 'Portrait panel obscures over 60% of the camera height.' }
    $settingsName = if ($Snapshot.language -eq 'ar') { 'الإعدادات' } else { 'Settings' }
    $settings = $Tree.SelectNodes('//node') | Where-Object { $_.text -eq $settingsName } | Select-Object -First 1
    $counter = $Tree.SelectNodes('//node') | Where-Object { $_.'content-desc' -match '(الطواف|Tawaf).*·' } | Select-Object -First 1
    if (-not $settings -or -not $counter) { throw 'Settings or ritual counter disappeared behind the selected panel.' }
    foreach ($node in @($settings, $counter)) {
        $b = Bounds $node
        if ($b[3] -gt $panel[1] -and $b[0] -lt $panel[2] -and $b[2] -gt $panel[0]) { throw 'Selected panel overlaps a header control.' }
    }
    return [ordered]@{ panel=$panel; microphone=$microphone; header=$header; body=$body; windowRoots=$Snapshot.windowRoots }
}
$results = [System.Collections.Generic.List[object]]::new()
$conversations = [System.Collections.Generic.List[object]]::new()
$originalScale = (Adb @('shell','settings','get','system','font_scale')).Trim()
try {
    foreach ($case in $Cases) {
        if ($case -notmatch '^(ar|en)-(portrait|landscape)-(1|1\.3)$') { throw "Invalid case: $case" }
        $language = $Matches[1]
        $landscape = $Matches[2] -eq 'landscape'
        $fontScale = $Matches[3]
        $runId = [guid]::NewGuid().ToString('N')
        Adb @('shell','am','force-stop','com.sarab.vision') | Out-Null
        Adb @('shell','settings','put','system','font_scale',$fontScale) | Out-Null
        Adb @('shell','am','start','-n','com.sarab.vision/.VisionPanelPreviewActivity',
            '--ez','allow_fixture','true','--es','run_id',$runId,'--es','language',$language,
            '--ez','landscape',$landscape.ToString().ToLowerInvariant()) | Out-Null
        $deadline = [DateTime]::UtcNow.AddSeconds(18)
        $snapshot = $null
        do {
            $snapshot = Read-Report $runId
            # A requested orientation recreates the debug activity. Its canceled first instance can
            # briefly write its report before the new instance takes over with the same runId.
            if ($snapshot.phase -eq 'failed' -and $snapshot.failure -notmatch '^JobCancellationException:') { throw "Preview setup failed: $($snapshot.failure)" }
            if ($snapshot.phase -eq 'ready' -and $snapshot.markersVisible -and $snapshot.width -gt 0) { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $deadline)
        if ($snapshot.phase -ne 'ready' -or -not $snapshot.markersVisible) { throw "Preview did not acquire a marker: $case" }
        if ([math]::Abs($snapshot.fontScale - [double]$fontScale) -gt .02) { throw 'OS font scale was not applied.' }
        Start-Sleep -Milliseconds 1500
        $tree = Ui "$case-live"
        $fullScreenTip = $tree.SelectNodes('//node') | Where-Object { $_.'resource-id' -eq 'com.android.systemui:id/ok' } | Select-Object -First 1
        if ($fullScreenTip) { Tap $fullScreenTip; $tree = Ui "$case-live" }
        $label = if ($language -eq 'ar') { 'مقام إبراهيم · LOCAL QA' } else { 'Maqam Ibrahim · LOCAL QA' }
        $rect = $tree.SelectNodes('//node') | Where-Object { $_.'content-desc' -eq $label -and $_.clickable -eq 'true' } | Select-Object -First 1
        if (-not $rect) { throw "Marker rectangle is not an accessible hit target: $case" }
        $box = Bounds $rect
        # Only tap a visible piece of the outline's body, never its name or an overlaid HUD control.
        $excluded = @($tree.SelectNodes('//node') | Where-Object {
            ($_.clickable -eq 'true' -and -not [object]::ReferenceEquals($_,$rect)) -or
            $_.'resource-id' -eq 'vision-conversation-panel' -or $_.text -eq $label
        } | ForEach-Object { ,(Bounds $_) })
        $tapPoint = $null
        foreach ($fractionY in (@(1..19) | Sort-Object { [math]::Abs($_ - 10) })) {
            foreach ($fractionX in @(0.5,0.25,0.75)) {
                $x = [int]($box[0]+($box[2]-$box[0])*$fractionX)
                $y = [int]($box[1]+($box[3]-$box[1])*$fractionY/20)
                $blocked = @($excluded | Where-Object { $x -ge $_[0]-3 -and $x -le $_[2]+3 -and $y -ge $_[1]-3 -and $y -le $_[3]+3 }).Count -gt 0
                if (-not $blocked) { $tapPoint = @($x,$y); break }
            }
            if ($tapPoint) { break }
        }
        if (-not $tapPoint) { throw "The marker rectangle has no visible unobstructed body target: $case" }
        $tapX = $tapPoint[0]
        $tapY = $tapPoint[1]
        Adb @('shell','input','tap',"$tapX","$tapY") | Out-Null
        Start-Sleep -Milliseconds 300
        $tree = Ui "$case-rectangle"
        $snapshot = Read-Report $runId
        $geometry = Assert-Panel $tree $snapshot $landscape
        Adb @('shell','input','keyevent','KEYCODE_BACK') | Out-Null
        Start-Sleep -Milliseconds 300
        $tree = Ui "$case-after-back" $false
        if ($tree.SelectNodes('//node') | Where-Object { $_.'resource-id' -eq 'vision-landmark-panel' }) { throw 'Back did not close the selected panel.' }
        $snapshot = Read-Report $runId
        if (-not $snapshot.resumed -or $snapshot.pinned) { throw 'Back exited the Activity or left a hidden pin.' }
        $labelNode = $tree.SelectNodes('//node') | Where-Object { $_.text -eq $label } | Select-Object -First 1
        if (-not $labelNode) { throw 'Marker label is not visible after closing the panel.' }
        Tap $labelNode
        $tree = Ui "$case-reading"
        $reading = Assert-Panel $tree (Read-Report $runId) $landscape
        $initialDescription = (Tag $tree 'vision-landmark-body').SelectNodes('.//node') |
            Where-Object { $_.text -like 'LOCAL QA — *' } | Select-Object -First 1
        if (-not $initialDescription -or ((Bounds $initialDescription)[3] - (Bounds $initialDescription)[1]) -lt (72 * [double]$fontScale)) {
            throw 'Initial reading view does not expose a readable line of actual information.'
        }
        $surfaceSample = Surface-Sample "$qaOutput/$case-reading.png" $reading.panel
        Tap (Tag $tree 'vision-landmark-mode')
        $tree = Ui "$case-compact"
        $compact = Assert-Panel $tree (Read-Report $runId) $landscape
        if (($compact.panel[3]-$compact.panel[1]) -gt ($reading.panel[3]-$reading.panel[1])) { throw 'Compact mode expanded the panel.' }
        Tap (Tag $tree 'vision-landmark-mode')
        $tree = Ui "$case-expanded" $false
        $fixedMic = (Bounds (Tag $tree 'vision-microphone')) -join ','
        $fixedHeader = (Bounds (Tag $tree 'vision-landmark-header')) -join ','
        $texts = [System.Collections.Generic.List[string]]::new()
        foreach ($node in $tree.SelectNodes('//node')) { $texts.Add([string]$node.text) }
        $deepRead = $Material -eq 'Opaque' -and $case -in @('ar-portrait-1', 'en-landscape-1.3')
        $scrollLimit = if ($deepRead) { 30 } else { 2 }
        $sourceFound = $false
        for ($scroll = 1; $scroll -le $scrollLimit; $scroll++) {
            $body = Bounds (Tag $tree 'vision-landmark-body')
            $x = [int](($body[0]+$body[2])/2)
            # Deliberate reading scrolls avoid flinging entirely past short source/excerpt blocks.
            Adb @('shell','input','swipe',"$x",[string]($body[3]-5),"$x",[string]($body[1]+5),'600') | Out-Null
            Start-Sleep -Milliseconds 250
            $tree = Ui "$case-scroll-$scroll" ($scroll -le 2 -or $deepRead)
            if (((Bounds (Tag $tree 'vision-microphone')) -join ',') -ne $fixedMic -or
                ((Bounds (Tag $tree 'vision-landmark-header')) -join ',') -ne $fixedHeader) { throw 'Fixed microphone or header moved while body scrolled.' }
            foreach ($node in $tree.SelectNodes('//node')) { $texts.Add([string]$node.text) }
            $sourceFound = ($texts -contains 'القرآن الكريم — البقرة ١٢٥' -or $texts -contains 'The Quran — Al-Baqarah 2:125') -and
                ($texts -contains 'وَاتَّخِذُوا مِن مَّقَامِ إِبْرَاهِيمَ مُصَلًّى')
            if ($sourceFound) { break }
        }
        if ($deepRead -and -not $sourceFound) { throw "Long content did not scroll through to the curated excerpt/source: $case" }
        Tap (Tag $tree 'vision-landmark-close')
        $tree = Ui "$case-closed"
        $snapshot = Read-Report $runId
        if ($snapshot.pinned -or -not $snapshot.resumed -or $snapshot.windowRoots -ne 1) { throw 'Close failed to restore the live Activity.' }
        Tag $tree 'vision-microphone' | Out-Null
        if ($snapshot.networkTransport -or -not $snapshot.syntheticPixelsOnly -or $snapshot.speechRecognitionInvoked) { throw 'Fixture isolation boundary violated.' }
        $results.Add([ordered]@{case=$case; result='PASS'; runId=$runId; fontScale=$snapshot.fontScale;
            viewport=@($snapshot.width,$snapshot.height); rectangle=$box; rectangleTap=@($tapX,$tapY);
            reading=$reading; compact=$compact; labelTap=$true; backClosesPanel=$true; fixedControlsDuringScroll=$true; informationVisibleInitially=$true;
            surfaceSample=$surfaceSample;
            curatedExcerptAndSourceReached=$sourceFound; deepReadRequired=$deepRead; windowRoots=$snapshot.windowRoots})
        $results | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath "$qaOutput/cases.json" -Encoding utf8
        Write-Output "$case PASS; panel=$($reading.panel -join ','); viewport=$($snapshot.width)x$($snapshot.height); source=$sourceFound"
    }
    foreach ($case in $ConversationCases) {
        if ($case -notmatch '^(ar|en)-(portrait|landscape)-(1|1\.3)$') { throw "Invalid conversation case: $case" }
        $language = $Matches[1]
        $landscape = $Matches[2] -eq 'landscape'
        $fontScale = $Matches[3]
        $runId = [guid]::NewGuid().ToString('N')
        Adb @('shell','am','force-stop','com.sarab.vision') | Out-Null
        Adb @('shell','settings','put','system','font_scale',$fontScale) | Out-Null
        Adb @('shell','am','start','-n','com.sarab.vision/.VisionPanelPreviewActivity',
            '--ez','allow_fixture','true','--es','run_id',$runId,'--es','language',$language,
            '--ez','landscape',$landscape.ToString().ToLowerInvariant(),'--ez','conversation','true') | Out-Null
        $deadline = [DateTime]::UtcNow.AddSeconds(18)
        do {
            $snapshot = Read-Report $runId
            if ($snapshot.phase -eq 'failed' -and $snapshot.failure -notmatch '^JobCancellationException:') { throw "Conversation setup failed: $($snapshot.failure)" }
            if ($snapshot.phase -eq 'ready' -and $snapshot.conversationActive -and $snapshot.replyLength -gt 500) { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $deadline)
        if ($snapshot.phase -ne 'ready' -or -not $snapshot.conversationActive -or $snapshot.replyLength -le 500) { throw 'Long local conversation did not finish.' }
        $longReplyLength = $snapshot.replyLength
        Start-Sleep -Milliseconds 1500
        $tree = Ui "$case-conversation"
        $panel = Bounds (Tag $tree 'vision-conversation-panel')
        $mic = Bounds (Tag $tree 'vision-microphone')
        $closeBounds = Bounds (Tag $tree 'vision-close-conversation')
        $micText = (Tag $tree 'vision-microphone').SelectNodes('.//node') | Where-Object { $_.text } | Select-Object -First 1
        if (-not $micText) { throw 'Microphone has no visible text label.' }
        $micTextBounds = Bounds $micText
        # A large button rectangle can still squeeze its text into single-letter columns.
        if ($micTextBounds[2]-$micTextBounds[0] -lt (80 * [double]$fontScale)) { throw 'Microphone label is compressed into an unreadable narrow column.' }
        $surfaceSample = Surface-Sample "$qaOutput/$case-conversation.png" $panel
        if ($snapshot.windowRoots -ne 1 -or $snapshot.pinned -or -not $snapshot.resumed) { throw 'Live conversation opened a separate window or pinned scene.' }
        if (($panel[3]-$panel[1]) -gt $snapshot.height * .51) { throw 'Conversation hides over half of the camera height.' }
        if ($mic[3] -gt $panel[3] -or $mic[1] -lt $panel[1] -or $mic[3]-$mic[1] -lt 125 -or $mic[2]-$mic[0] -lt 125) { throw 'Conversation microphone is clipped or undersized.' }
        $replyNode = $tree.SelectNodes('//node') | Where-Object { $_.text -like 'LOCAL QA — *' } | Select-Object -First 1
        if (-not $replyNode -or (Bounds $replyNode)[3] - (Bounds $replyNode)[1] -lt 75) { throw 'The new answer is not initially visible.' }
        $scrollNode = (Tag $tree 'vision-conversation-panel').SelectNodes('.//node') | Where-Object { $_.scrollable -eq 'true' } | Select-Object -First 1
        if (-not $scrollNode) { throw 'The long conversation has no scrollable body.' }
        $body = Bounds $scrollNode
        $x = [int](($body[0]+$body[2])/2)
        Adb @('shell','input','swipe',"$x",[string]($body[3]-5),"$x",[string]($body[1]+5),'600') | Out-Null
        Start-Sleep -Milliseconds 400
        $tree = Ui "$case-conversation-scroll"
        if (((Bounds (Tag $tree 'vision-microphone')) -join ',') -ne ($mic -join ',')) { throw 'Conversation microphone moved when the answer scrolled.' }
        if (((Bounds (Tag $tree 'vision-close-conversation')) -join ',') -ne ($closeBounds -join ',')) { throw 'Conversation close action moved when the answer scrolled.' }
        $writeFieldVisible = $false
        if ($case -eq 'en-portrait-1.3') {
            $write = $tree.SelectNodes('//node') | Where-Object { $_.text -eq 'Write' } | Select-Object -First 1
            if (-not $write) { throw 'No visible Write control after the long answer.' }
            Tap $write
            $tree = Ui "$case-conversation-write"
            $field = Bounds (Tag $tree 'vision-live-question')
            if ($field[3]-$field[1] -lt 90 -or $field[1] -lt $panel[1] -or $field[3] -gt $mic[1]) { throw 'Write did not expose the question field inside the scrolling body.' }
            $writeFieldVisible = $true
        }
        $closeMethod = if ($case -eq 'en-portrait-1.3') { 'close-button' } else { 'system-back' }
        if ($closeMethod -eq 'close-button') { Tap (Tag $tree 'vision-close-conversation') }
        else { Adb @('shell','input','keyevent','KEYCODE_BACK') | Out-Null }
        Start-Sleep -Milliseconds 400
        $tree = Ui "$case-conversation-closed"
        $snapshot = Read-Report $runId
        if ($snapshot.conversationActive -or $snapshot.pinned -or -not $snapshot.resumed) { throw 'Back did not close only the conversation.' }
        Tag $tree 'vision-microphone' | Out-Null
        $conversations.Add([ordered]@{case=$case; result='PASS'; replyCharacters=$longReplyLength;
            viewport=@($snapshot.width,$snapshot.height); panel=$panel; microphone=$mic; microphoneText=$micTextBounds; close=$closeBounds;
            surfaceSample=$surfaceSample;
            writeAfterLongAnswerFieldVisible=$writeFieldVisible;
            fixedMicrophoneDuringScroll=$true; closeMethod=$closeMethod; closesOnlyConversation=$true; windowRoots=$snapshot.windowRoots})
        Write-Output "$case long conversation PASS"
    }
} finally {
    if ($originalScale -match '^\d+(\.\d+)?$') { Adb @('shell','settings','put','system','font_scale',$originalScale) | Out-Null }
}
$crashes = Adb @('logcat','-d','-b','crash','-v','brief')
$crashes | Set-Content -LiteralPath "$qaOutput/crash-log.txt" -Encoding utf8
if ($crashes -match 'FATAL EXCEPTION|Fatal signal|Process: com\.sarab\.vision') { throw 'An app crash occurred; inspect crash-log.txt.' }
if ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash -ne $startingApkHash) { throw 'APK artifact changed during validation; results must not be retagged.' }
[ordered]@{result='PASS'; completedAt=[DateTime]::UtcNow.ToString('o'); serial=$Serial;
    apkSha256=$startingApkHash; installedApkSha256=$installedHash; apkBytes=(Get-Item -LiteralPath $apk).Length;
    material=$Material; cases=$results; conversations=$conversations; singleActivityWindow=$true; crashes=0; syntheticPixelsOnly=$true; networkTransport=$false;
    speechRecognitionInvoked=$false; emulatorShutdownConfirmed=$false;
    limitation='Readability, geometry and actual touch/Back in the production Compose HUD on an offline emulator. No physical XREAL mirroring, Fold hardware, camera accuracy, real STT/TTS or live Gemini requests are tested. Earlier controller/native reports retain their original APK hashes.'
} | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath "$qaOutput/validation.json" -Encoding utf8
Write-Output "Panel readability PASS for $($results.Count) cases. Artifacts: $qaOutput"
