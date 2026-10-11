@echo off
rem 先载入 .env（本机凭据不进仓库），再启动后端
call "%~dp0load-env.cmd"
set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.3+9"
set "PATH=%JAVA_HOME%in;C:	oolspache-maven-3.9.16in;D:\Program Files
odejs;%PATH%"
cd /d %~dp0..ackendgent-server
if not exist "..\..\logs" mkdir "..\..\logs"
echo [%DATE% %TIME%] starting backend... > ..\..\logsackend.log
mvn spring-boot:run >> ..\..\logsackend.log 2>&1
