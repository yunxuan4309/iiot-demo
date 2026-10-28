@echo off
rem ---------------------------------------------------------------
rem Data bridge TEST/BRIDGE tool:
rem   read a point from one device, write it to another device's point.
rem
rem Use case: feed a REAL sensor value into the CODESYS soft PLC
rem           (sensor has RS485; the soft PLC only speaks Modbus TCP).
rem
rem   collector\run-bridge.cmd
rem   collector\run-bridge.cmd --cycles 10 --interval 1000
rem
rem NOTE: keep this file PURE ASCII + CRLF (cmd.exe mangles UTF-8 Chinese).
rem ---------------------------------------------------------------
chcp 65001 >nul
cd /d "%~dp0.."
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp collector\target\collector-0.1.0-SNAPSHOT.jar com.iiot.collector.BridgeMain %*
