@echo off
rem ------------------------------------------------------------------
rem Start TDengine (taosd 6030 + taosAdapter 6041) inside WSL.
rem
rem NOTE: keep this file PURE ASCII + CRLF, and keep it free of  >  and  < .
rem   cmd.exe treats them as redirection EVEN INSIDE double quotes, which
rem   truncates the bash command ("syntax error near unexpected token 2").
rem   That is why all the real work lives in  ~/tdengine/start.sh  --
rem   this wrapper only calls it. Verified 2026-10-24 the hard way.
rem ------------------------------------------------------------------
chcp 65001 >nul
echo Starting TDengine in WSL ...
wsl -d Ubuntu-22.04 -- bash -lc "~/tdengine/start.sh"
echo.
echo Verify from Windows:
curl -sS -u root:taosdata -d "select server_version()" http://localhost:6041/rest/sql
echo.
pause
