@echo off
setlocal enabledelayedexpansion

set ANT_HOME=%USERPROFILE%\ant\apache-ant-1.10.15
set PATH=%PATH%;%ANT_HOME%\bin

cd /d C:\Users\Owner\CS640-Lab-3\assign3

echo Verifying Ant installation...
ant -version

if errorlevel 1 (
    echo Error: Ant not found or not working
    exit /b 1
)

echo.
echo Building project...
ant

pause
