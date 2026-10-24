@echo off
rem ------------------------------------------------------------------
rem Start the monitoring platform (Spring Boot + ECharts) on :8080
rem   http://localhost:8080
rem Prerequisites:
rem   1) device\tdengine\start-tdengine.cmd   (TDengine in WSL, REST on 6041)
rem   2) some data in db "iiot" (run collector\run-multi-tdengine.cmd first)
rem
rem NOTE 1: keep this file pure ASCII + CRLF.
rem NOTE 2: -Djava.net.preferIPv6Addresses=true is required because WSL2 only
rem         publishes TDengine on [::1]; the JVM would otherwise pick 127.0.0.1.
rem ------------------------------------------------------------------
chcp 65001 >nul
cd /d "%~dp0.."
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Djava.net.preferIPv6Addresses=true -jar platform\target\platform.jar %*
