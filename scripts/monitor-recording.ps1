param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateRange(1, 1440)][int]$Minutes = 120,
    [string]$Output = (Join-Path $PSScriptRoot '../artifacts/device/continuity.csv')
)
$ErrorActionPreference = 'Stop'
$parent = Split-Path -Parent ([IO.Path]::GetFullPath($Output))
New-Item -ItemType Directory -Force -Path $parent | Out-Null
if (Test-Path -LiteralPath $Output) { throw "Refusing to overwrite an existing result: $Output" }
$start = [DateTimeOffset]::UtcNow
$previous = $null
$initial = $null
for ($sample = 0; $sample -le $Minutes; $sample++) {
    $raw = & adb -s $Serial shell run-as com.daytrace.app cat files/status.json
    if ($LASTEXITCODE -ne 0) { throw 'Unable to read recording state; check USB/ADB connection.' }
    $state = ($raw -join "`n") | ConvertFrom-Json
    if ($null -eq $initial) { $initial = $state }
    $now = [DateTimeOffset]::UtcNow
    $power = (& adb -s $Serial shell dumpsys power) -join "`n"
    $screen = [regex]::Match($power, 'mWakefulness=(\w+)').Groups[1].Value
    $record = [pscustomobject]@{
        timestampUtc = $now.ToString('o')
        elapsedMs = [long]($now - $start).TotalMilliseconds
        phase = $state.phase
        sessionId = $state.sessionId
        capturedMs = $state.capturedMs
        interruptions = $state.interruptions
        silenced = $state.silenced
        screen = $screen
    }
    $record | Export-Csv -LiteralPath $Output -NoTypeInformation -Append -Encoding utf8
    $record | Format-List
    if ($state.phase -ne 'RECORDING' -or $state.sessionId -ne $initial.sessionId) { throw 'Recording stopped or session changed.' }
    if ($state.silenced -or $state.interruptions -ne $initial.interruptions) { throw 'Microphone silence or interruption was reported.' }
    if ($null -ne $previous -and $state.capturedMs -le $previous.capturedMs) { throw 'Recorded duration did not increase.' }
    $previous = $state
    if ($sample -lt $Minutes) { Start-Sleep -Seconds 60 }
}
Write-Output "Monitoring completed. Results: $Output"
Write-Output 'This verifies sampled recording state. Export WAV segments to independently verify audio content and segment continuity.'
