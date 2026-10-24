@echo off
rem ------------------------------------------------------------------
rem Start Mosquitto (northbound MQTT broker) inside WSL.
rem NOTE 1: keep this file pure ASCII + CRLF.
rem NOTE 2: do NOT use  >  or  <  inside the wsl argument below.
rem         cmd.exe treats them as redirection and truncates the bash
rem         command -- this broke the first version of this script.
rem ------------------------------------------------------------------
chcp 65001 >nul

echo [1/2] Starting mosquitto in WSL...
wsl -d Ubuntu-22.04 -u root -- bash -lc "systemctl start mosquitto; sleep 1; systemctl is-active mosquitto"

echo [2/2] Checking port 1883...
wsl -d Ubuntu-22.04 -u root -- bash -lc "ss -lnt | grep 1883 || echo '  NOT LISTENING - check: systemctl status mosquitto'"

echo.
echo To watch the message stream:  device\mqtt\subscribe.cmd
pause
