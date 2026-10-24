@echo off
rem Wrapper so you can just double-click. The real work is in stop-all.ps1
rem NOTE: keep this file pure ASCII + CRLF.
chcp 65001 >nul
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-all.ps1"
pause
