@echo off
rem ---------------------------------------------------------------
rem Modbus probe: read registers / guess word order / write coil
rem   TCP:  collector\run-probe.cmd --port 1502
rem   RTU:  collector\run-probe.cmd --com COM3 --baud 9600
rem   Write: collector\run-probe.cmd --port 1502 --write-motor true
rem
rem NOTE: keep this file PURE ASCII + CRLF (cmd.exe mangles UTF-8 Chinese).
rem ---------------------------------------------------------------
chcp 65001 >nul
cd /d "%~dp0.."
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp collector\target\collector-0.1.0-SNAPSHOT.jar com.iiot.collector.ProbeMain %*
