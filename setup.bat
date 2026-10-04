@echo off
setlocal
cd /d "%~dp0"

echo.
echo 2009Scape Killer Edition - Windows Setup
echo ==========================================
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Tools\setup-windows.ps1"
set "ERR=%ERRORLEVEL%"

echo.
if not "%ERR%"=="0" (
    echo Setup failed. See the error above.
    if not defined CI pause
    exit /b %ERR%
)

echo Setup complete.
echo Run server.bat to start the server.
echo.
if not defined CI pause
exit /b 0
