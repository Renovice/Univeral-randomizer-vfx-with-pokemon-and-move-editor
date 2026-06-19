@echo off
pushd "%~dp0"
cd /d "%~dp0"

SET "JAVA_CMD="

REM === Priority 1: Check for bundled JRE ===
IF EXIST "%~dp0jre\bin\java.exe" (
    SET "JAVA_CMD=%~dp0jre\bin\java.exe"
    echo Found bundled Java 8 JRE
    goto :run
)

REM === Priority 2: Search common Java 8 installation locations ===
echo Searching for Java 8 installation...

REM Check Program Files
REM Prefer update builds (jdk1.8.0_xxx / jre1.8.0_xxx) over a bare "jdk1.8.0":
REM ancient GA builds lack the CA certificates needed for the update check.
FOR /D %%G IN ("C:\Program Files\Java\jdk1.8.0_*", "C:\Program Files\Java\jre1.8.0_*", "C:\Program Files\Java\jdk1.8*", "C:\Program Files\Java\jre1.8*") DO (
    IF EXIST "%%G\bin\java.exe" (
        SET "JAVA_CMD=%%G\bin\java.exe"
        echo Found Java 8 at: %%G
        goto :run
    )
)

REM Check Program Files (x86)
FOR /D %%G IN ("C:\Program Files (x86)\Java\jdk1.8.0_*", "C:\Program Files (x86)\Java\jre1.8.0_*", "C:\Program Files (x86)\Java\jdk1.8*", "C:\Program Files (x86)\Java\jre1.8*") DO (
    IF EXIST "%%G\bin\java.exe" (
        SET "JAVA_CMD=%%G\bin\java.exe"
        echo Found Java 8 at: %%G
        goto :run
    )
)

REM Check if JAVA_HOME environment variable points to Java 8
IF DEFINED JAVA_HOME (
    IF EXIST "%JAVA_HOME%\bin\java.exe" (
        "%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr /C:"1.8" >nul
        IF NOT ERRORLEVEL 1 (
            SET "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
            echo Found Java 8 via JAVA_HOME
            goto :run
        )
    )
)

REM === Priority 3: Fall back to system Java (with warning) ===
echo.
echo ========================================================================
echo WARNING: Java 8 not found!
echo The randomizer may have display issues (pixelated graphics, wrong scaling)
echo.
echo To fix this:
echo   1. Download Java 8 JRE from: https://adoptium.net/temurin/releases/?version=8
echo   2. Install it, then run this launcher again
echo.
echo Attempting to run with system Java...
echo ========================================================================
echo.
SET "JAVA_CMD=java"

:run
"%JAVA_CMD%" -Xmx4608M -jar UPR-FVX.jar please-use-the-launcher
echo.
echo Press any key to exit...
pause >nul