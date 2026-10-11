@echo off
rem Start backend. Credentials come from .env via load-env.cmd (never stored in the repo).
call "%~dp0load-env.cmd"
set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.3+9"
set "PATH=%JAVA_HOME%\bin;C:\tools\apache-maven-3.9.16\bin;D:\Program Files\nodejs;%PATH%"
set "ROOT=%~dp0.."
if not exist "%ROOT%\logs" mkdir "%ROOT%\logs"
cd /d "%ROOT%\backend\agent-server"
echo [%DATE% %TIME%] starting backend... > "%ROOT%\logs\backend.log"
mvn spring-boot:run >> "%ROOT%\logs\backend.log" 2>&1
