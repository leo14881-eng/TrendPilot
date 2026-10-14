@echo off
rem All tools and caches live on drive D.
set "TP_TOOLS=D:\SOFT\tools"
set "MAVEN_USER_HOME=%TP_TOOLS%\maven"
set "MAVEN_OPTS=-Dmaven.repo.local=%TP_TOOLS%\maven\repository %MAVEN_OPTS%"

rem Find JDK 21: JAVA_HOME first (only if it is 21), then known install folders on drive D.
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
    "%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr /c:"version \"21" >nul && goto :java_ok
)
set "JAVA_HOME="
for /d %%D in ("D:\Program Files\Microsoft\jdk-21*" "%TP_TOOLS%\jdk-21*" "D:\SOFT\java\jdk-21*" "D:\SOFT\jdk-21*") do (
    if exist "%%~D\bin\java.exe" set "JAVA_HOME=%%~D"
)
:java_ok
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
