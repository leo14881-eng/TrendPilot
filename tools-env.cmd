@echo off
rem 所有工具和缓存都放在 D 盘
set "TP_TOOLS=D:\SOFT\tools"
set "MAVEN_USER_HOME=%TP_TOOLS%\maven"
set "MAVEN_OPTS=-Dmaven.repo.local=%TP_TOOLS%\maven\repository %MAVEN_OPTS%"

rem 找 JDK 21：优先 JAVA_HOME，其次 D:\SOFT\tools 或 D:\SOFT\java 下的 jdk-21*
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto :java_ok
for /d %%D in ("%TP_TOOLS%\jdk-21*" "D:\SOFT\java\jdk-21*" "D:\SOFT\jdk-21*") do (
    if exist "%%~D\bin\java.exe" set "JAVA_HOME=%%~D"
)
:java_ok
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
