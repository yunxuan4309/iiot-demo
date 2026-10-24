@echo off
rem Stop Mosquitto inside WSL.
chcp 65001 >nul
wsl -d Ubuntu-22.04 -u root -- bash -lc "systemctl stop mosquitto; systemctl is-active mosquitto || echo stopped"
pause
