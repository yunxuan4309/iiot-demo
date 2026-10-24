# ------------------------------------------------------------------
# Stop everything this demo started -- and ONLY our processes.
#
#   stop-all.ps1        (or run stop-all.cmd)
#
# IMPORTANT: we must not kill every java.exe -- that would also kill
# IntelliJ IDEA / Maven / anything else the user is running. So we
# filter on the command line: only processes running OUR jars.
# ------------------------------------------------------------------

$ErrorActionPreference = 'SilentlyContinue'

Write-Host 'Stopping collectors and platform (only our jars) ...'
$stopped = 0
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match 'collector-0\.1\.0-SNAPSHOT|platform\.jar' } |
    ForEach-Object {
        try {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop
            Write-Host ("  stopped java PID {0}" -f $_.ProcessId)
            $stopped++
        } catch {
            Write-Host ("  could not stop PID {0}: {1}" -f $_.ProcessId, $_.Exception.Message)
        }
    }
if ($stopped -eq 0) { Write-Host '  (none running)' }

Write-Host 'Stopping Mosquitto (WSL) ...'
& wsl -d Ubuntu-22.04 -u root -- bash -lc "systemctl stop mosquitto; systemctl is-active mosquitto" 2>$null

Write-Host 'Stopping TDengine (WSL) ...'
& wsl -d Ubuntu-22.04 -u root -- bash -lc "pkill -x taosadapter; sleep 1; pkill -9 -x taosd; sleep 2; pgrep -c taosd || echo taosd-stopped" 2>$null

Write-Host ''
Write-Host 'Port status now:'
foreach ($p in 1502, 1883, 6030, 6041, 8080, 1503) {
    $listening = (Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue)
    if ($listening) {
        $note = if ($p -eq 1503) { "  (CODESYS soft PLC - not ours, expected)" } else { "  <-- still up, check manually" }
        Write-Host ("  {0,-6} still listening{1}" -f $p, $note)
    }
    else { Write-Host ("  {0,-6} free" -f $p) }
}

Write-Host ''
Write-Host 'NOTE: the CODESYS soft PLC is NOT stopped by this script.'
Write-Host '      Use the CODESYS IDE (Online - Logout / Stop) if you need to.'
