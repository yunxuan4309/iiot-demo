@echo off
rem Stop TDengine (taosAdapter first, then taosd). Data is kept.
rem NOTE: keep this file PURE ASCII + CRLF and free of  >  /  <  (see start script).
chcp 65001 >nul
echo Stopping TDengine ...
wsl -d Ubuntu-22.04 -- bash -lc "~/tdengine/stop.sh"
pause
