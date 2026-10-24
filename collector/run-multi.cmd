@echo off
rem ------------------------------------------------------------------
rem Multi-device concurrent collector (virtual threads).
rem   collector\run-multi.cmd --fanout 10  --duration 30
rem   collector\run-multi.cmd --fanout 100 --duration 60
rem   collector\run-multi.cmd --store tdengine --fanout 10 --duration 20
rem   collector\run-multi.cmd --help
rem Prerequisite: collector\run-slave.cmd --port 1502 --devices 100
rem
rem NOTE 1: keep this file pure ASCII + CRLF (cmd.exe mangles UTF-8 Chinese).
rem NOTE 2: -Djava.net.preferIPv6Addresses=true is REQUIRED for --store tdengine
rem         when TDengine runs in WSL2: Windows only listens on [::1]:6041,
rem         and the JVM otherwise prefers 127.0.0.1 and fails to connect.
rem ------------------------------------------------------------------
chcp 65001 >nul
cd /d "%~dp0.."
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Djava.net.preferIPv6Addresses=true -cp collector\target\collector-0.1.0-SNAPSHOT.jar com.iiot.collector.MultiDeviceMain %*
