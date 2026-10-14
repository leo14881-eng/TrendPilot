@echo off
setlocal
cd /d "%~dp0"
call tools-env.cmd
if not exist target\trendpilot.jar call build.cmd
java -jar target\trendpilot.jar
endlocal
