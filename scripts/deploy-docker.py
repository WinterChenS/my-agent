#!/usr/bin/env python
"""把当前仓库打包上传到服务器并用 docker compose 部署。

用法（密码从环境变量读，不落盘、不进版本库）：
    MA_SSH_HOST=192.168.3.10 MA_SSH_USER=winterchen MA_SSH_PASSWORD=... \
    python scripts/deploy-docker.py [--rebuild-frontend] [--skip-build]

流程：
  1) 本地打包需要的源码（排除 target/node_modules/dist/.git）
  2) sftp 上传到 <remote-dir>/_upload.tgz 并解包
  3) 首次部署时用本地 .env 生成服务器上的 .env（已存在则不覆盖）
  4) docker compose up -d --build，然后探活
"""

import argparse
import os
import posixpath
import stat
import sys
import tarfile
import time

import paramiko

HOST = os.environ.get("MA_SSH_HOST", "192.168.3.10")
USER = os.environ.get("MA_SSH_USER", "winterchen")
PASSWORD = os.environ.get("MA_SSH_PASSWORD", "")
REMOTE_DIR = os.environ.get("MA_REMOTE_DIR", "/home/winterchen/docker/my-agent")

LOCAL_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

INCLUDE = [
    # 运行期镜像只需要这两个产物（在本机用 scripts/build-artifacts.sh 生成）
    "deploy/artifacts/app.jar",
    "deploy/artifacts/dist",
    "deploy/Dockerfile.backend",
    "deploy/Dockerfile.backend.from-source",
    "deploy/Dockerfile.frontend",
    "deploy/nginx.conf",
    "deploy/.env.example",
    # Agent Reach 源码包：后端镜像在构建时 pip 安装它（Agent 访问互联网的唯一出口）
    "vendor/agent-reach-1.6.0.tar.gz",
    "deploy/README.md",
    "docker-compose.yml",
    ".dockerignore",
]

EXCLUDE_DIRS = {"node_modules", "target", "e2e", "coverage", ".git", "__pycache__"}


def log(message):
    print(f"[deploy] {message}", flush=True)


def build_tarball(path):
    with tarfile.open(path, "w:gz") as tar:
        for relative in INCLUDE:
            absolute = os.path.join(LOCAL_ROOT, relative)
            if not os.path.exists(absolute):
                log(f"跳过不存在的路径: {relative}")
                continue
            if os.path.isfile(absolute):
                tar.add(absolute, arcname=relative)
                continue
            for root, dirs, files in os.walk(absolute):
                dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]
                for name in files:
                    full = os.path.join(root, name)
                    tar.add(full, arcname=os.path.relpath(full, LOCAL_ROOT))


def read_local_env():
    path = os.path.join(LOCAL_ROOT, ".env")
    if not os.path.exists(path):
        raise SystemExit("本地没有 .env，无法生成服务器配置")
    lines = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            lines.append(stripped)
    return "\n".join(lines) + "\n"


def run(client, command, timeout=1800, quiet=False):
    log(f"$ {command}")
    transport = client.get_transport()
    channel = transport.open_session()
    channel.settimeout(timeout)
    channel.set_combine_stderr(True)
    channel.exec_command(command)
    output = []
    while True:
        if channel.recv_ready():
            data = channel.recv(65535).decode("utf-8", "replace")
            output.append(data)
            if not quiet:
                # Windows 控制台是 GBK，docker 输出里的 ✓/→ 会抛 UnicodeEncodeError，直接写字节
                sys.stdout.buffer.write(data.encode("utf-8", "replace"))
                sys.stdout.buffer.flush()
        if channel.exit_status_ready() and not channel.recv_ready():
            break
    code = channel.recv_exit_status()
    return code, "".join(output)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-build", action="store_true", help="只重启，不重建镜像")
    parser.add_argument("--only", choices=["backend", "frontend"], help="只重建某个服务")
    args = parser.parse_args()

    if not PASSWORD:
        raise SystemExit("请通过环境变量 MA_SSH_PASSWORD 提供密码")

    archive = os.path.join(LOCAL_ROOT, "logs", "_upload.tgz")
    os.makedirs(os.path.dirname(archive), exist_ok=True)
    log("打包源码…")
    build_tarball(archive)
    log(f"包大小 {os.path.getsize(archive) / 1024 / 1024:.1f} MB")

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    log(f"连接 {USER}@{HOST} …")
    client.connect(HOST, port=22, username=USER, password=PASSWORD, timeout=20)

    run(client, f"mkdir -p {REMOTE_DIR}")

    sftp = client.open_sftp()
    remote_archive = posixpath.join(REMOTE_DIR, "_upload.tgz")
    log("上传…")
    sftp.put(archive, remote_archive)
    sftp.close()

    run(client, f"cd {REMOTE_DIR} && tar xzf _upload.tgz && rm -f _upload.tgz && mkdir -p data/workspace")

    # 首次部署才写 .env，避免覆盖服务器上已改过的配置
    code, _ = run(client, f"test -f {REMOTE_DIR}/.env && echo exists || echo missing", quiet=True)
    if "missing" in _:
        log("生成服务器 .env（来自本地 .env）")
        content = read_local_env()
        sftp = client.open_sftp()
        remote_env = posixpath.join(REMOTE_DIR, ".env")
        with sftp.file(remote_env, "w") as handle:
            handle.write(content)
        sftp.chmod(remote_env, stat.S_IRUSR | stat.S_IWUSR)
        sftp.close()
    else:
        log("服务器已有 .env，保持不变")

    build_args = "--build" if not args.skip_build else ""
    target = args.only or ""
    if not args.skip_build and not target:
        # 全量重建（首次部署或依赖变更）
        build_args = "--build"
    command = f"cd {REMOTE_DIR} && docker compose up -d {build_args} {target}".strip()
    code, _ = run(client, command, timeout=3600)
    if code != 0:
        log(f"compose 失败，退出码 {code}")
        run(client, f"cd {REMOTE_DIR} && docker compose ps", timeout=120)
        client.close()
        raise SystemExit(code)

    log("等待后端健康…")
    for _ in range(60):
        code, out = run(client, "curl -fsS -m 3 http://127.0.0.1:18081/api/health", timeout=30, quiet=True)
        if code == 0 and out.strip():
            log(f"后端 OK: {out.strip()[:120]}")
            break
        time.sleep(5)
    else:
        log("后端 10 分钟内没起来，看日志：")
        run(client, f"cd {REMOTE_DIR} && docker compose logs --tail=80 backend", timeout=120)

    run(client, f"cd {REMOTE_DIR} && docker compose ps", timeout=120)
    client.close()
    log(f"完成：http://{HOST}:18080")


if __name__ == "__main__":
    main()
