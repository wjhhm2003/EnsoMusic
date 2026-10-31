param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [string]$Label,

    [string]$OutputDir = (Join-Path $PSScriptRoot '..\\benchmark-results'),
    [string]$AdbPath = 'E:\\Android\\Sdk\\platform-tools\\adb.exe',
    [string]$PackageName = 'com.enso.music',
    [int]$WarmupSeconds = 5,
    [int]$ScenarioSeconds = 30,
    [int]$SampleIntervalSeconds = 1
)

$ErrorActionPreference = 'Stop'

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $output = & $AdbPath @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed ($LASTEXITCODE): adb $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return @($output)
}

function Invoke-AdbBestEffort {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $AdbPath @Arguments 2>$null | Out-Null
}

function Tap([int]$X, [int]$Y) {
    Invoke-Adb shell input tap $X $Y | Out-Null
}

function Swipe([int]$X1, [int]$Y1, [int]$X2, [int]$Y2, [int]$DurationMs = 450) {
    Invoke-Adb shell input swipe $X1 $Y1 $X2 $Y2 $DurationMs | Out-Null
}

function Wait-Seconds([int]$Seconds) {
    Start-Sleep -Seconds $Seconds
}

function Get-Pid {
    $pidText = ((Invoke-Adb shell pidof $PackageName) -join ' ').Trim()
    if ($pidText -match '\d+') {
        return [int]$Matches[0]
    }
    return 0
}

function Get-MemoryPssKb {
    $text = (Invoke-Adb shell dumpsys meminfo $PackageName) -join "`n"
    $match = [regex]::Match($text, '(?m)^\s*TOTAL\s+(\d+)\s+')
    if ($match.Success) { return [int]$match.Groups[1].Value }
    $match = [regex]::Match($text, '(?m)^\s*TOTAL:\s+(\d+)\s+')
    if ($match.Success) { return [int]$match.Groups[1].Value }
    return 0
}

function Get-ProcessCpuTicks([int]$ProcessId) {
    if ($ProcessId -le 0) { return 0L }
    $line = (Invoke-Adb shell cat "/proc/$ProcessId/stat") -join ' '
    $match = [regex]::Match($line, '^\d+\s+\(.*\)\s+[A-Z]\s+(.+)$')
    if (-not $match.Success) { return 0L }
    $fields = $match.Groups[1].Value -split '\s+'
    if ($fields.Count -lt 12) { return 0L }
    return ([int64]$fields[10] + [int64]$fields[11])
}

function Get-CpuCoreCount {
    $count = @((Invoke-Adb shell cat /proc/cpuinfo) | Where-Object { $_ -match '^processor\s*:' }).Count
    if ($count -lt 1) { return 1 }
    return $count
}

function Get-GfxInfo {
    $text = (Invoke-Adb shell dumpsys gfxinfo $PackageName) -join "`n"
    $result = [ordered]@{
        total_frames = 0
        janky_frames = 0
        janky_percent = 0.0
        p50_ms = 0
        p90_ms = 0
        p95_ms = 0
        p99_ms = 0
        missed_vsync = 0
        slow_ui = 0
        slow_bitmap = 0
        slow_draw = 0
        total_views = 0
        display_list_kb = 0.0
    }

    $patterns = @{
        total_frames = 'Total frames rendered:\s+(\d+)'
        janky_frames = 'Janky frames:\s+(\d+)\s+\(([0-9.]+)%\)'
        p50_ms = '50th percentile:\s+(\d+)ms'
        p90_ms = '90th percentile:\s+(\d+)ms'
        p95_ms = '95th percentile:\s+(\d+)ms'
        p99_ms = '99th percentile:\s+(\d+)ms'
        missed_vsync = 'Number Missed Vsync:\s+(\d+)'
        slow_ui = 'Number Slow UI thread:\s+(\d+)'
        slow_bitmap = 'Number Slow bitmap uploads:\s+(\d+)'
        slow_draw = 'Number Slow issue draw commands:\s+(\d+)'
        total_views = 'Total Views:\s+(\d+)'
        display_list_kb = 'Total DisplayList:\s+([0-9.]+)\s+kB'
    }

    foreach ($key in $patterns.Keys) {
        $match = [regex]::Match($text, $patterns[$key])
        if ($match.Success) {
            $result[$key] = if ($key -eq 'janky_frames') {
                [int]$match.Groups[1].Value
            } elseif ($key -eq 'display_list_kb') {
                [double]$match.Groups[1].Value
            } else {
                [int]$match.Groups[1].Value
            }
            if ($key -eq 'janky_frames') {
                $result.janky_percent = [double]$match.Groups[2].Value
            }
        }
    }
    return $result
}

function Write-CsvRow([hashtable]$Row, [string]$Path) {
    [pscustomobject]$Row | Export-Csv -Path $Path -NoTypeInformation -Append
}

if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "APK not found: $ApkPath"
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$safeLabel = $Label -replace '[^A-Za-z0-9_.-]', '_'
$runDir = Join-Path $OutputDir $safeLabel
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$samplesPath = Join-Path $runDir 'samples.csv'
$gfxPath = Join-Path $runDir 'gfxinfo.txt'
$logPath = Join-Path $runDir 'logcat.txt'
$summaryPath = Join-Path $runDir 'summary.json'

Write-Host "[$Label] Installing $ApkPath"
Invoke-Adb @('install', '-r', $ApkPath) | Out-Null
Invoke-Adb shell pm clear $PackageName | Out-Null
Invoke-AdbBestEffort shell pm grant $PackageName android.permission.READ_EXTERNAL_STORAGE
Invoke-AdbBestEffort shell pm grant $PackageName android.permission.WRITE_EXTERNAL_STORAGE

Write-Host "[$Label] Preparing the shared Music folder through the app UI"
Invoke-Adb @('shell', 'monkey', '-p', $PackageName, '1') | Out-Null
Wait-Seconds 2

# Now playing -> Queue/Folder page -> Scan Directory tab -> add folder.
Tap 382 347
Wait-Seconds 1
Tap 183 61
Wait-Seconds 1
Tap 398 111
Wait-Seconds 1
Swipe 220 300 220 120 400
Wait-Seconds 1
Tap 110 229
Wait-Seconds 1
Tap 214 353
Wait-Seconds 2
Tap 99 111
Wait-Seconds 12

# Queue/Folder page -> Now Playing -> Library page.
Swipe 100 205 430 205 500
Wait-Seconds 1
Swipe 100 205 430 205 500
Wait-Seconds 2

Invoke-AdbBestEffort shell dumpsys gfxinfo $PackageName reset
Invoke-AdbBestEffort @('shell', 'logcat', '-c')
$pidAtStart = Get-Pid
if ($pidAtStart -eq 0) { throw "Could not find process $PackageName" }

$rows = New-Object System.Collections.Generic.List[object]
$start = Get-Date
$end = $start.AddSeconds($WarmupSeconds + $ScenarioSeconds)
$nextAction = $start.AddSeconds($WarmupSeconds)
$actionIndex = 0
$actionPoints = @(
    @{ Offset = 0; X = 180; Y = 122 },
    @{ Offset = 4; X = 180; Y = 195 },
    @{ Offset = 8; X = 180; Y = 122 },
    @{ Offset = 12; X = 180; Y = 195 },
    @{ Offset = 16; X = 180; Y = 122 },
    @{ Offset = 20; X = 180; Y = 195 }
)

Write-Host "[$Label] Measuring PID $pidAtStart"
$cpuCores = Get-CpuCoreCount
$previousCpuTicks = Get-ProcessCpuTicks $pidAtStart
$previousCpuAt = Get-Date
while ((Get-Date) -lt $end) {
    $elapsed = ((Get-Date) - $start).TotalSeconds
    while ($actionIndex -lt $actionPoints.Count -and $elapsed -ge ($WarmupSeconds + $actionPoints[$actionIndex].Offset)) {
        Tap $actionPoints[$actionIndex].X $actionPoints[$actionIndex].Y
        Wait-Seconds 1
        Swipe 100 205 430 205 500
        Wait-Seconds 1
        $actionIndex++
    }

    $now = Get-Date
    $currentCpuTicks = Get-ProcessCpuTicks $pidAtStart
    $cpuElapsedSeconds = ($now - $previousCpuAt).TotalSeconds
    $cpuPercent = if ($cpuElapsedSeconds -gt 0 -and $currentCpuTicks -ge $previousCpuTicks) {
        (($currentCpuTicks - $previousCpuTicks) / (100.0 * $cpuElapsedSeconds * $cpuCores)) * 100.0
    } else {
        0.0
    }
    $rows.Add([pscustomobject][ordered]@{
        label = $Label
        timestamp = $now.ToString('o')
        elapsed_s = [math]::Round(($now - $start).TotalSeconds, 2)
        pid = $pidAtStart
        pss_kb = Get-MemoryPssKb
        cpu_percent = [math]::Round($cpuPercent, 3)
        action_index = $actionIndex
    })
    $previousCpuTicks = $currentCpuTicks
    $previousCpuAt = $now
    Start-Sleep -Seconds $SampleIntervalSeconds
}

$rows | Export-Csv -Path $samplesPath -NoTypeInformation
$gfx = Get-GfxInfo
(Invoke-Adb shell dumpsys gfxinfo $PackageName) | Set-Content -Path $gfxPath -Encoding utf8
$rawLog = Invoke-Adb @('shell', 'logcat', '-d', '-v', 'threadtime')
$rawLog | Set-Content -Path $logPath -Encoding utf8
$pidLines = @($rawLog | Where-Object { $_ -match "\s$pidAtStart\s" })
$error38 = @($pidLines | Where-Object { $_ -match 'Error \(-38' }).Count
$wrongState = @($pidLines | Where-Object { $_ -match 'wrong state' }).Count
$fatal = @($pidLines | Where-Object { $_ -match 'FATAL EXCEPTION|AndroidRuntime' }).Count

$pssValues = @($rows | ForEach-Object { [double]$_.pss_kb })
$cpuValues = @($rows | ForEach-Object { [double]$_.cpu_percent })
$summary = [ordered]@{
    label = $Label
    package = $PackageName
    pid = $pidAtStart
    sample_count = $rows.Count
    avg_pss_kb = [math]::Round((($pssValues | Measure-Object -Average).Average), 2)
    peak_pss_kb = (($pssValues | Measure-Object -Maximum).Maximum)
    avg_cpu_percent = [math]::Round((($cpuValues | Measure-Object -Average).Average), 3)
    peak_cpu_percent = (($cpuValues | Measure-Object -Maximum).Maximum)
    gfx = $gfx
    error_38_count = $error38
    wrong_state_count = $wrongState
    fatal_log_count = $fatal
    output_dir = (Resolve-Path $runDir).Path
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -Path $summaryPath -Encoding utf8
Write-Host "[$Label] Summary: $summaryPath"
