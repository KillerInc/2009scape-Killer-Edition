@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

echo.
echo 2009Scape Killer Edition - Windows Setup
echo ==========================================
echo.

set "ROOT=%~dp0"
set "SERVER_DIR=%ROOT%Server"
set "RUNTIME_DIR=%ROOT%.runtime"
set "JAVA_HOME=%RUNTIME_DIR%\jdk-11"
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
set "CACHE_FILE=%SERVER_DIR%\data\cache\main_file_cache.dat2"
set "SERVER_JAR=%SERVER_DIR%\server.jar"

if exist "%ROOT%.git\" (
    echo [1/4] Git clone detected - checking Git LFS...

    where git.exe >nul 2>&1
    if errorlevel 1 (
        echo ERROR: Git for Windows was not found.
        goto :fail
    )

    git lfs version >nul 2>&1
    if errorlevel 1 (
        echo ERROR: Git LFS is not available.
        echo Reinstall or update Git for Windows with Git LFS enabled.
        goto :fail
    )

    git lfs install
    if errorlevel 1 goto :lfsfail

    echo Pulling 2009Scape LFS files...
    git lfs pull
    if errorlevel 1 goto :lfsfail

    git lfs checkout
    if errorlevel 1 goto :lfsfail
) else (
    echo [1/4] Release ZIP detected - Git and Git LFS are not required.
)

if not exist "%CACHE_FILE%" (
    echo ERROR: RuneScape cache is missing:
    echo   %CACHE_FILE%
    echo.
    echo Use the Killer Edition release ZIP or run git lfs pull in a Git clone.
    goto :fail
)

findstr /b /c:"version https://git-lfs.github.com/spec/v1" "%CACHE_FILE%" >nul 2>&1
if not errorlevel 1 (
    echo ERROR: RuneScape cache is still a Git LFS pointer.
    goto :fail
)

echo [2/4] RuneScape cache OK.

if exist "%JAVA_EXE%" (
    echo [3/4] Private Temurin JDK 11 already installed.
    goto :java_ready
)

echo [3/4] Installing private Temurin JDK 11...

where curl.exe >nul 2>&1
if errorlevel 1 (
    echo ERROR: Windows curl.exe was not found.
    goto :fail
)

where tar.exe >nul 2>&1
if errorlevel 1 (
    echo ERROR: Windows tar.exe was not found.
    goto :fail
)

set "ADOPTIUM_ARCH=x64"
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "ADOPTIUM_ARCH=aarch64"

if not exist "%RUNTIME_DIR%" mkdir "%RUNTIME_DIR%"
set "JDK_ZIP=%RUNTIME_DIR%\jdk-11.zip"
set "JDK_EXTRACT=%RUNTIME_DIR%\jdk-11-extract"

if exist "%JDK_ZIP%" del /q "%JDK_ZIP%"
if exist "%JDK_EXTRACT%" rmdir /s /q "%JDK_EXTRACT%"
mkdir "%JDK_EXTRACT%"

set "JDK_URL=https://api.adoptium.net/v3/binary/latest/11/ga/windows/%ADOPTIUM_ARCH%/jdk/hotspot/normal/eclipse"

echo Downloading Temurin JDK 11...
curl.exe -L --fail --retry 3 --retry-delay 2 -o "%JDK_ZIP%" "%JDK_URL%"
if errorlevel 1 (
    echo ERROR: JDK download failed.
    goto :fail
)

echo Extracting Java...
tar.exe -xf "%JDK_ZIP%" -C "%JDK_EXTRACT%"
if errorlevel 1 (
    echo ERROR: JDK extraction failed.
    goto :fail
)

set "JDK_SOURCE="
for /d %%D in ("%JDK_EXTRACT%\*") do (
    if exist "%%~fD\bin\java.exe" set "JDK_SOURCE=%%~fD"
)

if not defined JDK_SOURCE (
    echo ERROR: Downloaded archive did not contain a usable JDK.
    goto :fail
)

if exist "%JAVA_HOME%" rmdir /s /q "%JAVA_HOME%"
move "!JDK_SOURCE!" "%JAVA_HOME%" >nul
if errorlevel 1 (
    echo ERROR: Could not install the private JDK.
    goto :fail
)

rmdir /s /q "%JDK_EXTRACT%" >nul 2>&1
del /q "%JDK_ZIP%" >nul 2>&1

:java_ready
"%JAVA_EXE%" -version
if errorlevel 1 (
    echo ERROR: Private Java runtime could not start.
    goto :fail
)

if exist "%ROOT%.git\" goto :build
if not exist "%SERVER_JAR%" goto :build

echo [4/4] Using prebuilt Server\server.jar from release ZIP.
goto :ready

:build
echo [4/4] Building 2009Scape server...
pushd "%SERVER_DIR%"

set "JAVA_HOME=%ROOT%.runtime\jdk-11"
set "PATH=%JAVA_HOME%\bin;%PATH%"

call mvnw.cmd clean package -DskipTests
if errorlevel 1 (
    popd
    echo ERROR: Maven build failed.
    goto :fail
)

set "BUILT_JAR="
for %%F in (target\*-jar-with-dependencies.jar) do set "BUILT_JAR=%%F"

if not defined BUILT_JAR (
    popd
    echo ERROR: Build completed but no server JAR was found.
    goto :fail
)

copy /y "!BUILT_JAR!" "server.jar" >nul
if errorlevel 1 (
    popd
    echo ERROR: Could not copy built server.jar.
    goto :fail
)

popd

:ready
echo.
echo ==========================================
echo READY
echo ==========================================
echo Java:  %JAVA_EXE%
echo Server: %SERVER_JAR%
echo.
echo Run server.bat to start 2009Scape.
echo.
if not defined CI pause
exit /b 0

:lfsfail
echo ERROR: Git LFS setup or download failed.
goto :fail

:fail
echo.
echo Setup failed.
echo.
if not defined CI pause
exit /b 1
