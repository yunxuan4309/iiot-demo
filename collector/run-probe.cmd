@echo off
rem 探测一次：读 4 个寄存器 + 四种字序解析 + 开关量；可加 --write-motor true 写线圈
chcp 65001 >nul
cd /d "%~dp0.."
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp collector\target\collector-0.1.0-SNAPSHOT.jar com.iiot.collector.ProbeMain %*
