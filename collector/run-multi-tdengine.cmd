@echo off
rem ------------------------------------------------------------------
rem Collect devices and WRITE THEM INTO TDengine (not just count).
rem   collector\run-multi-tdengine.cmd --fanout 10 --duration 30
rem
rem Prerequisite: device\tdengine\start-tdengine.cmd  (WSL2 + taosAdapter on 6041)
rem Prerequisite: collector\run-slave.cmd --port 1502 --devices 10
rem ------------------------------------------------------------------
chcp 65001 >nul
cd /d "%~dp0.."
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Djava.net.preferIPv6Addresses=true -cp collector\target\collector-0.1.0-SNAPSHOT.jar com.iiot.collector.MultiDeviceMain --store tdengine --td-url http://localhost:6041 --td-db iiot --td-user root --td-pass taosdata %*
