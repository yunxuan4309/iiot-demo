@echo off
rem ------------------------------------------------------------------
rem Watch every northbound message:  iiot/{device}/{point}
rem Ctrl+C to stop. Append  -C 1  to grab just one message.
rem
rem NOTE: retain=true means you get the LATEST value IMMEDIATELY,
rem       without waiting for the next collection cycle.
rem ------------------------------------------------------------------
chcp 65001 >nul
wsl -d Ubuntu-22.04 -u root -- bash -lc "mosquitto_sub -h 127.0.0.1 -t 'iiot/#' -v"
