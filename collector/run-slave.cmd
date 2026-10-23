@echo off
rem 启动仿真 Modbus 从站（独立进程，Ctrl+C 停止 = 设备离线）
rem chcp 65001 是为了让控制台按 UTF-8 显示中文；java 的 stdout 也固定成 UTF-8，两者要一致。
chcp 65001 >nul
cd /d "%~dp0.."
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar collector\target\collector-0.1.0-SNAPSHOT.jar %*
