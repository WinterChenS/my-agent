@echo off
rem 载入 .env 后跑后端测试（集成测试需要数据库/Redis 连接信息）
call "%~dp0load-env.cmd"
set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.3+9"
set "PATH=%JAVA_HOME%in;C:	oolspache-maven-3.9.16in;%PATH%"
cd /d %~dp0..ackendgent-server
mvn verify
