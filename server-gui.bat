@echo off
setlocal
cd /d "%~dp0"

set "JAVA_HOME=%~dp0.runtime\jdk-11"
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
set "SERVER_JAR=%~dp0Server\server.jar"

if not exist "%JAVA_EXE%" (
    echo.
    echo The private Java runtime is not installed.
    echo Run setup.bat first.
    echo.
    pause
    exit /b 1
)

if not exist "%SERVER_JAR%" (
    echo.
    echo The server has not been built yet.
    echo Run setup.bat first.
    echo.
    pause
    exit /b 1
)

cd /d "%~dp0Server"
start "" "%JAVA_EXE%" -jar "server.jar" --gui
exit /b 0
