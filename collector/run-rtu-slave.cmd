@echo off
rem ---------------------------------------------------------------
rem RTU slave TEST STUB - only for validating RtuModbusDriver
rem without real hardware (com0com virtual serial pair).
rem
rem   collector\run-rtu-slave.cmd                        (COM5, 25.6)
rem   collector\run-rtu-slave.cmd --com COM8 --temperature 12.34
rem
rem This is NOT the product slave. The product slave is SimSlaveMain (TCP).
rem NOTE: keep this file PURE ASCII + CRLF (cmd.exe mangles UTF-8 Chinese).
rem ---------------------------------------------------------------
chcp 65001 >nul
cd /d "%~dp0.."
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp collector\target\collector-0.1.0-SNAPSHOT.jar com.iiot.collector.sim.RtuSlaveMain %*
pause
