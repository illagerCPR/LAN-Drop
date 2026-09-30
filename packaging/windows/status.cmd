@echo off
rem Show LAN-Drop status: process, port, logon task, health check, last log lines.
rem ASCII-only on purpose (see start.cmd).
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0status.ps1"
pause
