@echo off
rem One-time setup, no admin needed. Registers TrendPilot-Service and TrendPilot-AutoUpdate scheduled tasks.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-autoupdate.ps1"
