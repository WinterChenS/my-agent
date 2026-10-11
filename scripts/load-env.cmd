@echo off
rem 把仓库根的 .env 载入为环境变量（.env 已被 .gitignore 忽略，内含本机真实凭据）。
rem 这样启动脚本与测试命令里都不需要写任何口令。
set "ENV_FILE=%~dp0..\.env"
if not exist "%ENV_FILE%" (
  echo [load-env] 未找到 .env，跳过（可用 .env.example 复制一份）
  goto :eof
)
for /f "usebackq tokens=1,* delims==" %%a in (`findstr /v /b "#" "%ENV_FILE%"`) do (
  if not "%%a"=="" set "%%a=%%b"
)
echo [load-env] 已从 .env 载入环境变量
