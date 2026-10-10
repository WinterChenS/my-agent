# my-agent

前后端分离的 Agent 平台。

- **前端**：React 18 + Vite + TypeScript + Tailwind CSS v4 + shadcn/ui
- **后端**：Java 21 + Spring Boot + AgentScope Java v2
- **中间件**：PostgreSQL + Redis（内网）

能力：大模型配置、Agent 配置、Skill 配置、MCP 支持、AG-UI 聊天卡片（审批卡 / 确认卡 / 映射卡 / 工具调用卡）、按用户维度的 Token 审计。

## 文档

| 文档 | 说明 |
|---|---|
| `docs/技术方案.md` | 架构、依赖版本、表设计、API 契约、卡片契约、防跑偏门禁 G1–G8 |
| `docs/任务列表.md` | Phase 0–9 共 16 个任务及各自验收门禁 |
| `docs/backlog.md` | 超范围需求登记（门禁 G7） |
| `docs/comet/` | Comet Native change 产物（brief / specs / verification） |
| `docs/acceptance/` | 全链路验收证据 |

## 环境要求

| 组件 | 版本 | 本机实测位置 |
|---|---|---|
| JDK | 21（Temurin） | `C:\Program Files\Java\jdk-21.0.3+9` |
| Maven | 3.9.x | `C:\tools\apache-maven-3.9.16` |
| Node | 20+（本机为 24） | `D:\Program Files\nodejs` |
| PostgreSQL | 服务端实际为 18.3 | 内网主机，见 `application-dev.yml` |
| Redis | 服务端实际为 8.6.3 | 内网主机，见 `application-dev.yml` |

> 本机 **未安装 Docker**，因此不使用 Testcontainers / `docker compose`；集成测试直接连接专用测试库 `agent_platform_test`。

监督工具链（本机 PATH 默认是 Java 8 与 Node 16，需显式指定）：

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21.0.3+9"
export PATH="/c/tools/apache-maven-3.9.16/bin:$PATH"
export PATH="/d/Program Files/nodejs:$PATH"
```

## 启动

```bash
# 后端（默认 dev profile）
cd backend/agent-server && mvn spring-boot:run

# 前端
cd frontend && npm install && npm run dev   # http://localhost:5173
```

Vite 已代理 `/api` 与 `/agui` 到 `http://localhost:8080`。

## 连接配置

数据库与 Redis 连接位于 `backend/agent-server/src/main/resources/application-dev.yml`（仅 dev profile，内网明文）。`JWT_SECRET` 与 `AGENT_CRYPTO_KEY` 由环境变量提供，dev profile 有可用默认值。

## 质量门禁

```bash
# 后端
cd backend/agent-server && mvn verify

# 前端
cd frontend && npm run lint && npm run build && npx vitest run
```

## 数据库

- 应用库：`agent_platform`（Flyway 管理，DDL 只允许出现在 `src/main/resources/db/migration/`）
- 测试库：`agent_platform_test`
- Redis 逻辑库：`db10` 会话、`db11` 缓存，键前缀 `myagent:`（`db0`/`db1` 已被同实例其他应用占用）

## 本地模型桩（仅开发验证用）

尚未配置真实模型端点时，可用仓库内的 OpenAI 兼容桩验证连通性测试与聊天链路：

```bash
python tools/llm-stub/stub.py        # 监听 127.0.0.1:8099，提供 /v1/chat/completions
```

该脚本只是开发期验证辅助，不是平台组件。
