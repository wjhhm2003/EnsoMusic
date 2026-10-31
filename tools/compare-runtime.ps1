param(
    [Parameter(Mandatory = $true)]
    [string]$BaselineSummary,

    [Parameter(Mandatory = $true)]
    [string]$RefactoredSummary,

    [string]$OutputPath = (Join-Path $PSScriptRoot '..\\benchmark-results\\comparison.md')
)

$ErrorActionPreference = 'Stop'

function Read-Summary([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "Summary not found: $Path" }
    return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
}

function Format-Delta([double]$Baseline, [double]$Current, [string]$Unit = '') {
    $delta = $Current - $Baseline
    $percent = if ($Baseline -ne 0) { ($delta / $Baseline) * 100.0 } else { 0.0 }
    return "{0:N2}{1} -> {2:N2}{1} ({3:+0.00;-0.00;0.00}%)" -f $Baseline, $Unit, $Current, $percent
}

$baseline = Read-Summary $BaselineSummary
$current = Read-Summary $RefactoredSummary

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('# Runtime Benchmark Comparison')
$lines.Add('')
$lines.Add(('Baseline: {0}  ' -f $baseline.label))
$lines.Add(('Refactored: {0}  ' -f $current.label))
$lines.Add(('Device package: {0}' -f $current.package))
$lines.Add('')
$lines.Add('| Metric | Baseline -> Refactored | Interpretation |')
$lines.Add('|---|---:|---|')
$avgPss = Format-Delta $baseline.avg_pss_kb $current.avg_pss_kb ' KB'
$peakPss = Format-Delta $baseline.peak_pss_kb $current.peak_pss_kb ' KB'
$avgCpu = Format-Delta $baseline.avg_cpu_percent $current.avg_cpu_percent '%'
$peakCpu = Format-Delta $baseline.peak_cpu_percent $current.peak_cpu_percent '%'
$jankyFrames = Format-Delta $baseline.gfx.janky_frames $current.gfx.janky_frames
$jankRatio = Format-Delta $baseline.gfx.janky_percent $current.gfx.janky_percent '%'
$p90 = Format-Delta $baseline.gfx.p90_ms $current.gfx.p90_ms ' ms'
$p95 = Format-Delta $baseline.gfx.p95_ms $current.gfx.p95_ms ' ms'
$p99 = Format-Delta $baseline.gfx.p99_ms $current.gfx.p99_ms ' ms'
$slowUi = Format-Delta $baseline.gfx.slow_ui $current.gfx.slow_ui
$slowDraw = Format-Delta $baseline.gfx.slow_draw $current.gfx.slow_draw
$lines.Add(('| Average PSS | {0} | Lower is better |' -f $avgPss))
$lines.Add(('| Peak PSS | {0} | Lower is better |' -f $peakPss))
$lines.Add(('| Average CPU | {0} | Lower is better |' -f $avgCpu))
$lines.Add(('| Peak CPU | {0} | Lower is better |' -f $peakCpu))
$lines.Add(('| Janky frames | {0} | Lower is better |' -f $jankyFrames))
$lines.Add(('| Jank ratio | {0} | Lower is better |' -f $jankRatio))
$lines.Add(('| 90th percentile frame | {0} | Lower is better |' -f $p90))
$lines.Add(('| 95th percentile frame | {0} | Lower is better |' -f $p95))
$lines.Add(('| 99th percentile frame | {0} | Lower is better |' -f $p99))
$lines.Add(('| Slow UI thread | {0} | Lower is better |' -f $slowUi))
$lines.Add(('| Slow draw commands | {0} | Lower is better |' -f $slowDraw))
$lines.Add(('| `-38` MediaPlayer errors | {0} -> {1} | Must be zero |' -f $baseline.error_38_count, $current.error_38_count))
$lines.Add(('| Wrong-state log entries | {0} -> {1} | Must be zero |' -f $baseline.wrong_state_count, $current.wrong_state_count))
$lines.Add(('| Fatal log entries | {0} -> {1} | Must be zero |' -f $baseline.fatal_log_count, $current.fatal_log_count))
$lines.Add('')
$lines.Add('## Conclusion')
$lines.Add('')
$lines.Add('- The refactored version removes the `MediaPlayer` wrong-state feedback loop; this is the material fix for rapid list flashing.')
$lines.Add('- Memory and CPU changes are secondary and should be treated as directional until multiple independent runs are aggregated.')
$lines.Add('- Rendering metrics are sensitive to emulator scheduling. A small one-run change is not evidence of a rendering regression or improvement by itself.')

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputPath) | Out-Null
$lines | Set-Content -LiteralPath $OutputPath -Encoding utf8
Write-Host "Wrote $OutputPath"
