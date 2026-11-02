# Docker 部署（只跑平台自身：后端 + 前端）

数据库、Redis、MinIO 全部**复用现有实例**，compose 里不启动它们。

```
┌──────────────┐   18080     ┌──────────────────┐  /api,/agui  ┌─────────────────┐
│   浏览器      │ ──────────► │ frontend (nginx) │ ───────────► │ backend (JRE21) │
└──────────────┘             └──────────────────┘              │ + Python3       │
                                                               └────────┬────────┘
                                                        PG / Redis / MinIO（现有实例）
```

## 目录结构（服务器上）

```
/home/winterchen/docker/my-agent/
├── docker-compose.yml
├── .env                 ← 从 deploy/.env.example 复制并填真实值（不进版本库）
├── deploy/
│   ├── Dockerfile.backend
│   ├── Dockerfile.frontend
│   ├── nginx.conf
│   └── .env.example
├── backend/agent-server/  ← 源码（构建后端镜像用）
├── frontend/              ← 源码（构建前端镜像用）
└── data/workspace/        ← 运行期数据：用户 venv、技能缓存、产物、记忆（**要备份**）
```

## 构建方式：本地出产物，服务器只做运行时镜像

**为什么不在服务器上编译**：实测部署服务器到 Maven Central 的链路极慢且频繁中断
（`Remote host terminated the handshake`、jar 下载被截断），`mvn package` 必然失败。
所以拆成两步：

```bash
# ① 本机（或 CI）出产物：deploy/artifacts/app.jar + deploy/artifacts/dist
./scripts/build-artifacts.sh

# ② 一键部署：打包上传 → 服务器 docker compose up -d --build → 探活
MA_SSH_PASSWORD='***' python scripts/deploy-docker.py
#   只重建前端：--only frontend      只重启不重建：--skip-build
```

网络条件好的环境（能顺畅访问 Maven Central）也可以用 `deploy/Dockerfile.backend.from-source`
在镜像里整包编译，此时把 `docker-compose.yml` 的 `dockerfile:` 换过去即可。

## 首次部署（手动等价操作）

```bash
cd /home/winterchen/docker/my-agent
cp deploy/.env.example .env && vi .env       # 填 DB / Redis / MinIO / 密钥
docker compose up -d --build
docker compose ps
curl -fsS http://127.0.0.1:18081/api/health
```

访问：`http://localhost:18080`

## 日常运维

```bash
docker compose logs -f backend        # 看日志
docker compose restart backend        # 重启
docker compose up -d --build backend  # 只重建后端（改了 Java 代码后）
docker compose down                   # 停止（不会删 data/）
```

## 端口

| 端口 | 用途 |
|---|---|
| 18080 | 前端（nginx）：页面 + `/api` + `/agui` 反代 |
| 127.0.0.1:18081 | 后端直连（只绑本机，调试/健康检查用） |

改端口就改 `docker-compose.yml` 里的 `ports`。

## 几个关键约束（改配置时别踩）

1. **`MYAGENT_WORKSPACE` 必须指向挂载卷**（compose 里已设 `/app/workspace`）。
   里面是用户 venv、`.skills-cache`、产物、记忆 —— 不挂卷则容器一重建全丢。
2. **`/agui/*` 是 SSE**：nginx 已关 `proxy_buffering` 并把 `proxy_read_timeout` 放到 1 小时。
   自己换反代（Traefik/Caddy/云 LB）时同样要关缓冲，否则"思考过程"和流式输出会整段憋到最后。
3. **上传上限三处对齐**：nginx `client_max_body_size 60m` → Tomcat `MYAGENT_MULTIPART_MAX_REQUEST=60MB`
   → 业务 `MINIO_MAX_FILE_MB=50`。只改一处会出现"前端说成功、后端 413"或反之。
4. **`AGENT_CRYPTO_KEY` 一旦更换，库里已加密的供应商 API Key 就解不开了**（要重新填 Key）。
5. **JVM 内存**：`JAVA_OPTS` 默认 `-Xmx2048m`。机器内存小就调小，但 7GB 机器别超过 3G，
   剩下的要留给 Python 脚本（pandas/matplotlib 很吃内存）。
6. 镜像里已预装 Python 常用库（pandas / numpy / openpyxl / python-docx / python-pptx / matplotlib / pypdf 等）
   和中文字体；模型再 `pip install` 会走每个用户自己的 venv，落在 workspace 卷里。
7. **第一次启动会跑 Flyway 迁移**（`V1…V19`）：连不上 DB 会直接启动失败，先确认 `.env` 里的连接串。

## 备份

```bash
# 只备份运行期数据（数据库在 PG 那边单独备份）
tar czf my-agent-workspace-$(date +%F).tgz data/workspace
```
