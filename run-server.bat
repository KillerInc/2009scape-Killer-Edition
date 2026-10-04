@echo off
rem Compatibility launcher. The simplified launcher is now server.bat.
call "%~dp0server.bat" %*
exit /b %ERRORLEVEL%
