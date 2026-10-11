@echo off
rem Load repo-root .env (gitignored) into environment. ASCII only: cmd uses the ANSI codepage.
set "ENV_FILE=%~dp0..\.env"
if not exist "%ENV_FILE%" goto :eof
for /f "usebackq tokens=1,* delims==" %%a in (`findstr /v /b "#" "%ENV_FILE%"`) do call :setvar "%%a" "%%b"
echo [load-env] env vars loaded from .env
goto :eof

:setvar
if "%~1"=="" goto :eof
set "%~1=%~2"
goto :eof
