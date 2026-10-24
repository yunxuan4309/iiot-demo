@echo off
rem ---------------------------------------------------------------
rem Modbus TCP simulation slave (stand-in for real devices)
rem   collector\run-slave.cmd --port 1502 --devices 10
rem   collector\run-slave.cmd --port 1502 --devices 10 --base-temp 74 --spike-prob 0.10
rem   collector\run-slave.cmd --port 1502 --devices 10 --hang-unit 5 --hang-after 8
rem Ctrl+C = device offline (devices go STALE, not zero).
rem
rem NOTE: keep this file PURE ASCII + CRLF (cmd.exe mangles UTF-8 Chinese).
rem ---------------------------------------------------------------
chcp 65001 >nul
cd /d "%~dp0.."
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar collector\target\collector-0.1.0-SNAPSHOT.jar %*
