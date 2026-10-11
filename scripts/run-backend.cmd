@echo off
set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.3+9"
set "PATH=%JAVA_HOME%\bin;C:\tools\apache-maven-3.9.16\bin;D:\Program Files\nodejs;%PATH%"
cd /d D:\project\my-agent\backend\agent-server
echo [%DATE% %TIME%] starting backend... > D:\project\my-agent\logs\backend.log
mvn spring-boot:run >> D:\project\my-agent\logs\backend.log 2>&1
