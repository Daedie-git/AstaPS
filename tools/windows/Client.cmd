@echo off
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Client.ps1"
if errorlevel 1 echo AstaPS action failed. Read the error above before retrying.
pause
