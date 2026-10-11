@echo off
rem Start frontend dev server (vite proxies /api and /agui to localhost:8080).
set "ROOT=%~dp0.."
if not exist "%ROOT%\logs" mkdir "%ROOT%\logs"
cd /d "%ROOT%\frontend"
if not exist node_modules (echo [frontend] npm install first... & call npm install)
echo [%DATE% %TIME%] starting frontend... > "%ROOT%\logs\frontend.log"
npm run dev >> "%ROOT%\logs\frontend.log" 2>&1
