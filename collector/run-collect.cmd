@echo off
rem 单设备持续采集：点表配置化 + 质量码（GOOD / STALE / BAD）
chcp 65001 >nul
cd /d "%~dp0.."
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp collector\target\collector-0.1.0-SNAPSHOT.jar com.iiot.collector.CollectMain %*
