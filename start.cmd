@echo off
setlocal
cd /d "%~dp0"
call "%~dp0tools-env.cmd"
if not exist target\trendpilot.jar call "%~dp0build.cmd"
java -jar target\trendpilot.jar
endlocal
