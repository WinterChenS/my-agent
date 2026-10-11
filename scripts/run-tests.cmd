@echo off
rem Run backend tests (integration tests need DB/Redis env from .env).
call "%~dp0load-env.cmd"
set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.3+9"
set "PATH=%JAVA_HOME%\bin;C:\tools\apache-maven-3.9.16\bin;%PATH%"
cd /d "%~dp0..\backend\agent-server"
mvn verify
