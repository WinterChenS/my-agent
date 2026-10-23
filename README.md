# my-agent

前后端分离的 Agent 平台。

- **前端**：React 18 + Vite + TypeScript + Tailwind CSS v4 + shadcn/ui
- **后端**：Java 21 + Spring Boot + AgentScope Java v2
- **中间件**：PostgreSQL + Redis（内网）

能力：大模型配置、Agent 配置、Skill 配置（含**从仓库链接导入标准 Skill**：SKILL.md + scripts/references/assets）、MCP 支持、AG-UI 聊天卡片（审批卡 / 确认卡 / 映射卡 / 工具调用卡）、按用户维度的 Token 审计。

## 文档

| 文档 | 说明 |
|---|---|
| `docs/技术方案.md` | 架构、依赖版本、表设计、API 契约、卡片契约、防跑偏门禁 G1–G8 |
| `docs/任务列表.md` | Phase 0–11 共 23 个任务及各自验收门禁 |
| `docs/backlog.md` | 超范围需求登记（门禁 G7） |
| `docs/comet/` | Comet Native change 产物（brief / specs / verification） |
| `docs/acceptance/` | 全链路验收证据 |

## 环境要求

| 组件 | 版本 | 本机实测位置 |
|---|---|---|
| JDK | 21（Temurin） | `C:\Program Files\Java\jdk-21.0.3+9` |
| Maven | 3.9.x | `C:\tools\apache-maven-3.9.16` |
| Node | 20+（本机为 24） | `D:\Program Files\nodejs` |
| PostgreSQL | 18.x（本机实测 18.3） | 连接由环境变量 `DB_*` 提供 |
| Redis | 8.x（本机实测 8.6.3） | 连接由环境变量 `REDIS_*` 提供 |

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

## 连接配置（不含任何真实凭据）

仓库里**不保存任何真实口令**：数据库/Redis/对象存储/MinIO 的连接与密钥全部由**环境变量**提供，
`application*.yml` 里只有本地占位默认值。复制 `.env.example` 为 `.env`（已被 `.gitignore` 忽略）或直接导出：

```bash
# PostgreSQL（应用库与测试库）
export DB_URL='jdbc:postgresql://<主机>:5432/agent_platform?stringtype=unspecified'
export TEST_DB_URL='jdbc:postgresql://<主机>:5432/agent_platform_test?stringtype=unspecified'
export DB_USERNAME='<用户名>'
export DB_PASSWORD='<口令>'

# Redis
export REDIS_HOST='<主机>'
export REDIS_PASSWORD='<口令>'
export REDIS_DATABASE=10          # 会话；测试用 REDIS_TEST_DATABASE=11

# 对象存储（MinIO / S3 兼容）
export MINIO_ENDPOINT='http://<主机>:9000'
export MINIO_PUBLIC_ENDPOINT='http://<对外可达地址>:9000'
export MINIO_ACCESS_KEY='<accessKey>'
export MINIO_SECRET_KEY='<secretKey>'
export MINIO_BUCKET='agent-platform'

# 诊断（可选）：把每轮最终系统提示的形态打到日志（技能清单是否注入、注入几个）
export MYAGENT_AGUI_DEBUG_SYSTEM_PROMPT=true

# 应用自身的密钥（生产必须改）
export JWT_SECRET='<>=32 字节随机串>'
export AGENT_CRYPTO_KEY='<AES-GCM 主密钥>'
```

> 验收脚本（`scripts/verify-*.sh`）同样从 `DB_HOST` / `DB_USERNAME` / `DB_PASSWORD` 读连接信息，不内联口令。

### 安全提示

- `admin` 的初始口令是**公开的默认值**（见 `V2__seed.sql`，BCrypt 存储），首次登录后请立即修改。
- 部署到非内网环境前，请改掉 `JWT_SECRET` 与 `AGENT_CRYPTO_KEY`，并开启 HTTPS。
- 若曾把真实凭据写进过仓库，仅删文件不够：历史提交里仍可读到，需要重写历史（本仓库就是这么做的）。

## 质量门禁

```bash
# 后端
cd backend/agent-server && mvn verify

# 前端
cd frontend && npm run lint && npm run build && npx vitest run
```

## 工作区根目录（注意）

Agent 的工作区（记忆、上传物化、产物扫描、事件日志）默认是**相对路径** `./.myagent-workspace`，
会被**按启动 jar 时的工作目录**解析——从不同目录启动会得到两个不同的工作区（实测踩过：从仓库根启动一份、
从 `backend/agent-server` 启动又一份，导致"上传的文件在界面上看不到"）。

- 启动时日志会打印已规范化的**绝对路径**：`Agent 工作区根目录（已规范为绝对路径）: ...`，用它核对。
- 想固定位置就用绝对路径：`MYAGENT_WORKSPACE=D:/agent-workspace`（推荐部署时这么设）。
- 本项目脚本一律在仓库根启动后端，因此工作区落在 `./.myagent-workspace`。

## 验收脚本（可一键复跑）

所有脚本都在 `scripts/`，每份都自带断言与证据输出：

| 脚本 | 覆盖 | 关键断言 |
|---|---|---|
| `verify-p5-skills.sh` | Skill 同步与绑定生效 | 绑定 Agent 的提示词含 Skill 名称/描述、回复带桩标记；未绑定对照组无标记；官方仓库存有完整内容 |
| `verify-p6-mcp.sh` | MCP 注册与真实调用 | sync-tools 得工具清单；`TOOL_CALL_START/RESULT`；结果来自 MCP server 真实回显 |
| `verify-p7-hitl.sh` | HITL 审批链路 | 拦截无 TOOL_CALL_RESULT；`approval_record` PENDING；批准后 resume 且工具真执行；拒绝不执行；越权 403；重复 409 |
| `verify-p72-cards.sh` | 确认卡 / 映射卡 | CUSTOM 事件 data 逐字段符合冻结契约 |
| `verify-p8-audit.sh` | Token 审计 | summary/byModel/byDay；records 分页；ADMIN 看全员；USER 查他人 403 |
| `verify-p9-acceptance.sh` | **全链路剧本 + 浏览器截图** | Playwright：工具卡 / 审批卡批准 / 拒绝 / 审计看板 |
| `e2e-chat.sh` | 聊天主链路浏览器验收 | 流式气泡逐字出现、刷新后历史仍在、断连错误态 |
| `verify-p10-artifacts.sh` | **产物/来源统一存储与自动登记** | 文本入 DB / 二进制进 MinIO；重复上传去重；下载 sha256 一致；上传物化后 Agent 真能读到；run 后识别 Agent 写出的文件且不带框架内部文件 |
| `verify-p10-minio-down.sh` | 对象存储不可用时的降级 | 后端起得来（只告警）；二进制上传返回 `code=400` 明确错误；文本上传仍成功；对话主链路不受影响 |
| `verify-p11-memory-read.sh` | **记忆与会话状态是否真在库里** | 往 `memory_store` 种标记→删掉工作区 `memory/*.md`→再跑一轮：库里记忆仍含标记且 payload 变大（读取来自库）；磁盘 memory 文件始终 0 |
| `add-real-model.sh` | 一条命令接入真实大模型 | 建 provider/model → 连通性测试 → 建 Agent（无需 jq） |
| `cleanup-e2e-data.sh` | 清理实施期测试数据 | 默认预览，`--yes` 才删 |

管理台页面的浏览器验收在 `frontend/e2e/admin.spec.ts`（MCP 新建+同步工具+工具清单、模型连通测试、Skill 版本、Agent 列表），用 `cd frontend && npx playwright test e2e/admin.spec.ts` 运行。

## 验收证据

截图归档在 `docs/acceptance/`：

| 文件 | 对应门禁 |
|---|---|
| `task-4.3-chat-streaming.png` | 流式气泡逐字出现 |
| `task-4.3-chat-history.png` | 刷新后历史回放 |
| `task-4.3-chat-error-state.png` | 后端不可用：错误态 + 重试 + 输入不丢 |
| `task-8.2-audit-dashboard.png` | 审计看板展示真实数据 |
| `task-8.2-audit-filtered.png` | 日期筛选生效（筛选后为空） |
| `task-9.1-tool-call-card.png` | 工具调用卡（参数 + 结果） |
| `task-9.1-approval-card.png` | **审批卡**（工具名 + 参数 + 批准/拒绝） |
| `task-9.1-approval-approved.png` | 批准后卡片置灰、执行继续 |
| `task-9.1-approval-denied.png` | 拒绝后卡片置灰、不执行 |
| `task-repair-mcp-page.png` | **MCP 管理页**（新建 STDIO → 同步 13 个工具 → 工具清单） |
| `task-repair-models-page.png` | 模型页（供应商/模型列表 + api_key 掩码 + 连通测试） |
| `task-repair-skills-page.png` | Skill 页（新建 + 版本 v1） |
| `task-repair-agents-page.png` | Agent 页（绑定与权限模式） |
| `task-10-panel-upload.png` | **右侧栏**：来源文件 / 产物两区 + 上传后的来源文件 |
| `task-10-artifact-area.png` | **产物区**：Agent 写出的文件在 run 结束后自动出现 |
| `task-10-skill-block.png` | **`/` 选技能**：技能变成可删除的块，不进入输入框文本 |
| `task-11-attachment-staging.png` | **附件暂存区**：文件显示在输入框上方、可移除，发送后才上传 |

## 已知环境偏离（相对《技术方案》）

| 项 | 方案 | 实际 | 原因 |
|---|---|---|---|
| Flyway | 10.x | **11.7.2** | PG 18 需 ≥11；Flyway 12 移除了 Boot 3.3.5 依赖的 `cleanOnValidationError` |
| Redis db | db0/db1 | **db10/db11** | db0/db1 已被同实例其他应用占用 |
| 集成测试 | Testcontainers | 连内网**专用测试库** | 机器未安装 Docker |
| 前端 Markdown | 未指定库 | 新增 `react-markdown` | 方案要求 Markdown 渲染气泡但未指定实现 |
| 权限拦截 | 依赖权限中间件 | 需显式 **ASK 规则** | 实测仅设 `PermissionMode.DEFAULT` 拦不住工具调用 |
| AG-UI 入口 | 未指定 | 必须走官方 **`AguiRequestProcessor`** | 直接调 `adapter.run` 会让 `resume` 失效 |
| SSE 出站 | — | 去掉值为 null 的字段 | 官方客户端 Zod `optional()` 不接受 null，否则整个 `RUN_FINISHED` 被丢弃 |

## 未完成 / 超出当前范围

- 见 `docs/backlog.md`（RAG/语音/A2A/多租户/国际化等，均属方案 G7 明确排除项）

> 管理台四个页面（模型 / Agent / Skill / MCP）已按《技术方案》第 7 节实现：
> `http://localhost:5173/admin/{models,agents,skills,mcp}`。

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
