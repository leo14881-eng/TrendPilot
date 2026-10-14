@echo off
setlocal
cd /d "%~dp0"
call tools-env.cmd
rem Maven Wrapper 配置（远程工具无法写 .mvn 目录，所以在本机首次运行时生成）
if not exist .mvn\wrapper\maven-wrapper.properties (
    mkdir .mvn\wrapper 2>nul
    > .mvn\wrapper\maven-wrapper.properties (
        echo wrapperVersion=3.3.4
        echo distributionType=only-script
        echo distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip
    )
)
rem 第一次运行时 Maven Wrapper 会把 Maven 下载到 D:\SOFT\tools\maven
call mvnw.cmd -B package
endlocal
