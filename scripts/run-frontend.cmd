@echo off
set "PATH=D:\Program Files\nodejs;%PATH%"
cd /d D:\project\my-agent\frontend
echo [%DATE% %TIME%] starting frontend... > D:\project\my-agent\logs\frontend.log
npm run dev >> D:\project\my-agent\logs\frontend.log 2>&1
