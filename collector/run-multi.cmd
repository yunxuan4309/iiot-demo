@echo off
rem ------------------------------------------------------------------
rem Multi-device concurrent collector (virtual threads).
rem   collector\run-multi.cmd --fanout 10  --duration 30
rem   collector\run-multi.cmd --fanout 100 --duration 60
rem   collector\run-multi.cmd --help
rem Prerequisite: collector\run-slave.cmd --port 1502 --devices 100
rem NOTE: keep this file pure ASCII + CRLF. Chinese text belongs in the
rem       Java --help output; cmd.exe mangles UTF-8 Chinese in .cmd files.
rem ------------------------------------------------------------------
chcp 65001 >nul
cd /d "%~dp0.."
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp collector\target\collector-0.1.0-SNAPSHOT.jar com.iiot.collector.MultiDeviceMain %*
