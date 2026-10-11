@echo off
rem Run backend tests (integration tests need DB/Redis env from .env).
call "%~dp0load-env.cmd"
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.3+9"
if not defined MAVEN_HOME set "MAVEN_HOME=C:\tools\apache-maven-3.9.16"
set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"
cd /d "%~dp0..\backend\agent-server"
mvn verify
