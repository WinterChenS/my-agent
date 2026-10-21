@echo off
rem Start frontend dev server (vite proxies /api and /agui to localhost:8080).
rem
rem Node version guard: PATH may resolve to another node first (on this machine it is
rem the v16 bundled with WeChat devtools), and vite then dies with
rem "node:fs/promises does not provide an export named 'constants'". Prefer the real Node.
set "NODE_HOME=C:\Program Files\nodejs"
if exist "%NODE_HOME%\node.exe" set "PATH=%NODE_HOME%;%PATH%"

set "ROOT=%~dp0.."
if not exist "%ROOT%\logs" mkdir "%ROOT%\logs"
cd /d "%ROOT%\frontend"
if not exist node_modules (echo [frontend] npm install first... & call npm install)
echo [%DATE% %TIME%] starting frontend... > "%ROOT%\logs\frontend.log"
npm run dev >> "%ROOT%\logs\frontend.log" 2>&1
