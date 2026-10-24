@echo off
rem ------------------------------------------------------------------
rem Start the WHOLE demo in the right order, each part in its own window.
rem
rem   run-all.cmd
rem
rem Order matters:
rem   TDengine -> MQTT -> Modbus slave -> collectors -> platform -> browser
rem
rem NOTE 1: keep this file pure ASCII + CRLF (cmd.exe mangles UTF-8 Chinese).
rem NOTE 2: this is for a CLEAN start (e.g. after reboot). If something is
rem         already listening, that part will just fail in its own window --
rem         run stop-all.cmd first if you want a clean slate.
rem ------------------------------------------------------------------
chcp 65001 >nul
cd /d "%~dp0"

echo ================================================================
echo   iiot-demo  full start
echo ================================================================

echo.
echo [1/6] TDengine (taosd + taosAdapter, inside WSL) ...
start "iiot-TDengine" cmd /c "device\tdengine\start-tdengine.cmd"
call :sleep 10

echo [2/6] MQTT broker (Mosquitto, inside WSL) ...
start "iiot-MQTT" cmd /c "device\mqtt\start-mqtt.cmd"
call :sleep 4

echo [3/6] Modbus slave - 10 simulated devices on 1502 ...
start "iiot-SimSlave" cmd /c "collector\run-slave.cmd --port 1502 --devices 10 --base-temp 62"
call :sleep 8

echo [4/6] Collector - simulated devices -> TDengine ...
start "iiot-Collector-sim" cmd /c "collector\run-multi-tdengine.cmd --fanout 10 --duration 86400 --report-every 600"
call :sleep 3

echo [5/6] Collector - CODESYS PLC -> TDengine (optional; needs CODESYS running) ...
start "iiot-Collector-plc" cmd /c "collector\run-multi-tdengine.cmd --points collector\src\main\resources\codesys.properties --fanout 1 --duration 86400 --report-every 600"
call :sleep 3

echo [6/6] Monitoring platform ...
start "iiot-Platform" cmd /c "platform\run-platform.cmd"
call :sleep 10

echo.
echo Opening http://localhost:8080 ...
start "" http://localhost:8080

echo.
echo ----------------------------------------------------------------
echo All started. Windows opened:
echo   iiot-TDengine / iiot-MQTT / iiot-SimSlave
echo   iiot-Collector-sim / iiot-Collector-plc / iiot-Platform
echo.
echo To stop everything:  stop-all.cmd
echo NOTE: the CODESYS soft PLC must be started separately in the
echo       CODESYS IDE (Online - Login - Start), and its Modbus port
echo       is 1503.
echo ----------------------------------------------------------------
pause
exit /b 0

:sleep
rem %1 = seconds. ping is the most portable sleep on Windows.
set /a _n=%1+1
ping -n %_n% 127.0.0.1 >nul
exit /b 0
