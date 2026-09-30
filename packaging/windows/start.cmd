@echo off
rem ---------------------------------------------------------------------------
rem LAN-Drop portable server - start in THIS console window (foreground).
rem
rem Keep this file ASCII-only: cmd.exe on a Chinese Windows runs under code page
rem 936 and would garble UTF-8 text. Server output is Chinese, hence chcp 65001.
rem
rem Use this for debugging / first pairing: the pairing code is printed below.
rem For daily use, run install.ps1 once (logon autostart, no window).
rem ---------------------------------------------------------------------------
chcp 65001 >nul
setlocal EnableExtensions

set "PROGRAM_DIR=%~dp0"
set "NODE_EXE=%PROGRAM_DIR%node\node.exe"
set "SERVER_JS=%PROGRAM_DIR%app\server\server.js"
set "ENV_FILE=%LOCALAPPDATA%\LAN-Drop\lan-drop.env"

if not exist "%NODE_EXE%" (
    echo [ERROR] node\node.exe not found - please re-extract the whole package.
    pause
    exit /b 1
)
if not exist "%SERVER_JS%" (
    echo [ERROR] app\server\server.js not found - please re-extract the whole package.
    pause
    exit /b 1
)

rem Load KEY=VALUE settings written by install.ps1 (eol=# skips comment lines)
if exist "%ENV_FILE%" for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ENV_FILE%") do set "%%a=%%b"

echo Starting LAN-Drop server in this window. Press Ctrl+C to stop.
echo   settings: %ENV_FILE%
echo.

"%NODE_EXE%" "%SERVER_JS%"

echo.
echo Server exited.
pause
