@echo off
setlocal
cd /d "%~dp0"
call "%~dp0tools-env.cmd"
rem Maven Wrapper settings (remote tools cannot write .mvn, so create it on first run).
if not exist .mvn\wrapper\maven-wrapper.properties (
    mkdir .mvn\wrapper 2>nul
    > .mvn\wrapper\maven-wrapper.properties (
        echo wrapperVersion=3.3.4
        echo distributionType=only-script
        echo distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip
    )
)
rem First run downloads Maven into D:\SOFT\tools\maven.
call "%~dp0mvnw.cmd" -B package
endlocal
