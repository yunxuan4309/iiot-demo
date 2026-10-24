@echo off
rem ------------------------------------------------------------------
rem Stop TDengine (taosAdapter first, then taosd).
rem Data stays in WSL at ~/tdengine/data - nothing is deleted.
rem NOTE: keep this file pure ASCII + CRLF (cmd.exe mangles UTF-8 Chinese).
rem ------------------------------------------------------------------
chcp 65001 >nul

echo [1/2] Stopping taosAdapter...
wsl -d Ubuntu-22.04 -- bash -lc "pkill -x taosadapter 2>/dev/null; sleep 1; echo '  done'"

echo [2/2] Stopping taosd...
wsl -d Ubuntu-22.04 -- bash -lc "pkill -x taosd 2>/dev/null; sleep 2; echo '  done'"

echo.
echo Status:
wsl -d Ubuntu-22.04 -- bash -lc "echo -n '  taosd: '; pgrep -c taosd || echo 0; echo -n '  taosadapter: '; pgrep -c taosadapter || echo 0"
echo.
pause
