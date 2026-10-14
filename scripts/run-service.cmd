@echo off
rem Run by scheduled task TrendPilot-Service (normal user). Keeps running until the service exits.
setlocal
cd /d "%~dp0.."
call "%~dp0..\tools-env.cmd"
if not exist app mkdir app
if not exist app\trendpilot.jar if exist target\trendpilot.jar copy /y target\trendpilot.jar app\trendpilot.jar >nul
java -jar app\trendpilot.jar > logs\console.log 2>&1
endlocal
