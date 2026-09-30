@echo off
rem Stop the running LAN-Drop portable server (keeps the logon task registered).
rem ASCII-only on purpose (see start.cmd). Chinese messages come from stop.ps1.
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1"
pause
