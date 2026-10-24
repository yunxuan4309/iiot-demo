@echo off
rem ---------------------------------------------------------------
rem Single-device continuous collection: point table + quality codes
rem   collector\run-collect.cmd --cycles 5
rem   collector\run-collect.cmd --points D:\my-points.properties
rem   collector\run-collect.cmd --points collector\src\main\resources\points-rtu.properties
rem
rem NOTE: keep this file PURE ASCII + CRLF.
rem cmd.exe reads UTF-8 Chinese comments under the OEM code page (GBK),
rem splits them at full-width punctuation, and then tries to EXECUTE the
rem fragments -- which prints spurious "not recognized" errors.
rem Always put Chinese text in the Java --help output, never in .cmd files.
rem ---------------------------------------------------------------
chcp 65001 >nul
cd /d "%~dp0.."
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp collector\target\collector-0.1.0-SNAPSHOT.jar com.iiot.collector.CollectMain %*
