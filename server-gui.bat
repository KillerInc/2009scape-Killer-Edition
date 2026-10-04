@echo off
setlocal
cd /d "%~dp0"

set "JAVA_EXE=%~dp0.runtime\jdk-11\bin\java.exe"
set "GUI_JAR=%~dp0Tools\ServerControl\ServerControl.jar"

if not exist "%JAVA_EXE%" (
    echo.
    echo The private Java runtime is not installed.
    echo Run setup.bat first.
    echo.
    pause
    exit /b 1
)

if not exist "%GUI_JAR%" (
    echo.
    echo ServerControl.jar is missing.
    echo Run setup.bat first.
    echo.
    pause
    exit /b 1
)

start "" "%JAVA_EXE%" -jar "%GUI_JAR%"
exit /b 0
