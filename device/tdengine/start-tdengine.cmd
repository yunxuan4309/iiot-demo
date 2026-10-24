@echo off
rem ------------------------------------------------------------------
rem Start TDengine inside WSL (taosd + taosAdapter), no sudo needed.
rem   taosd       -> 6030 (native protocol)
rem   taosAdapter -> 6041 (REST / WebSocket)  <-- Java collector uses this
rem Idempotent: safe to run when already running.
rem NOTE: keep this file pure ASCII + CRLF (cmd.exe mangles UTF-8 Chinese).
rem ------------------------------------------------------------------
chcp 65001 >nul

echo [1/3] Starting WSL distro (if not running)...
wsl -d Ubuntu-22.04 -- bash -lc "true"

echo [2/3] Starting taosd (6030)...
wsl -d Ubuntu-22.04 -- bash -lc "pgrep -x taosd >/dev/null || (cd $HOME/tdengine && setsid nohup ./bin/taosd -c $HOME/tdengine/cfg > $HOME/tdengine/log/taosd.stdout 2>&1 &)"

echo [3/3] Starting taosAdapter (6041)...
wsl -d Ubuntu-22.04 -- bash -lc "pgrep -x taosadapter >/dev/null || (cd $HOME/tdengine && setsid nohup env LD_LIBRARY_PATH=$HOME/tdengine/lib ./bin/taosadapter -c $HOME/tdengine/cfg/taosadapter.toml > $HOME/tdengine/log/adapter.stdout 2>&1 &)"

echo.
echo Waiting for TDengine to accept connections...
wsl -d Ubuntu-22.04 -- bash -lc "for i in $(seq 1 20); do curl -s -o /dev/null -u root:taosdata -d 'select 1' http://127.0.0.1:6041/rest/sql && { echo '  OK: taosAdapter is ready on 6041'; exit 0; }; sleep 1; done; echo '  TIMEOUT: check $HOME/tdengine/log/adapter.stdout'; exit 1"

echo.
echo Verify from Windows:
curl -sS -u root:taosdata -d "select server_version()" http://localhost:6041/rest/sql
echo.
pause
