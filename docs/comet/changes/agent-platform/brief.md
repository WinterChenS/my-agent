# 目标

构建前后端分离的 Agent 平台 `my-agent`：前端 React 18 + Vite + Tailwind v4 + shadcn/ui，后端 Java 21 + Spring Boot + AgentScope Java v2。交付六项能力：

1. 大模型配置（provider / model，含连通性测试）
2. Agent 配置（系统提示词、模型、采样参数、权限模式，含 Skill/MCP 绑定）
3. Skill 配置（Markdown 内容 + 版本 + 启停）
4. MCP 支持（stdio / SSE / Streamable HTTP 三种传输，工具同步与调用）
5. 基于 AG-UI 的聊天卡片（审批卡、确认卡、映射卡、工具调用卡）
6. 按用户维度的 Token 审计（汇总、明细、权限隔离）

事实来源：`docs/技术方案.md`（架构、依赖版本、DB 设计、API 契约、卡片契约、门禁 G1–G8）与 `docs/任务列表.md`（Phase 0–9 共 16 个任务及其验收门禁）。

# 范围

按 `docs/任务列表.md` 的 Phase 0–9 全部 16 个任务交付，交付顺序遵循其依赖图：

- Phase 0 项目骨架（0.1 仓库、0.2 后端骨架、0.3 前端骨架）
- Phase 1 基础设施（1.1 Flyway 迁移、1.2 通用层 + JWT 鉴权）
- Phase 2 大模型配置（2.1 provider/model CRUD + 连通测试）
- Phase 3 Agent 配置（3.1 agent CRUD + Skill/MCP 绑定）
- Phase 4 AG-UI 聊天主链路（4.1 AgentFactory、4.2 `/agui/run` SSE、4.3 前端聊天页）
- Phase 5 Skill 配置（5.1 Skill CRUD）
- Phase 6 MCP 支持（6.1 MCP Server 注册 + 工具同步）
- Phase 7 HITL 卡片系统（7.1 审批卡、7.2 确认卡/映射卡/工具调用卡）
- Phase 8 Token 审计（8.1 采集 + 聚合接口、8.2 审计看板前端）
- Phase 9 集成收尾（9.1 全链路回归 + README + 门禁复查）

每个任务的完成判定 = 该任务在 `docs/任务列表.md` 中的验收门禁全部通过，并附真实证据（curl 输出 / SQL 结果 / 页面截图 / 录屏）。

# 非目标

以下内容不实现，发现价值时只记录到 `docs/backlog.md`（门禁 G7）：

- RAG / 向量检索、语音、A2A 多 Agent 协作协议、多租户、国际化
- 自研推理循环、自研工具调用编排、自研 MCP JSON-RPC 解析、自研审批等待逻辑（门禁 G8：必须用 AgentScope 官方扩展）
- 前端解析模型响应、拼接 prompt、直连模型 API
- 配置类包（`modelconfig` / `agentconfig` / `skillconfig` / `mcpconfig`）引用 `io.agentscope`（门禁 G2）
- 生产级部署编排、K8s、CI/CD 流水线、监控告警

# 验收示例

1. 登录与鉴权
   - 输入：`POST /api/auth/login {"username":"admin","password":"Admin@123"}`
   - 期望：`{"code":0,"data":{"token":"<JWT>","user":{"id":1,"username":"admin","role":"ADMIN"}}}`
   - 输入：无 token 访问 `GET /api/auth/me`
   - 期望：401；带 token 时返回当前用户

2. 大模型配置
   - 输入：新建 provider（含 api_key）后 `GET /api/model-providers`
   - 期望：响应中 api_key 显示为 `****`，无明文；更新时不传 api_key 则保留旧密钥
   - 输入：`POST /api/model-providers/{id}/test`
   - 期望：`{"ok":true,"latencyMs":<n>}`；密钥无效时 `{"ok":false,"error":"..."}` 且 HTTP 非 500

3. Agent 配置与绑定
   - 输入：`POST /api/agents`（含 `skillIds:[1]`、`mcpServerIds:[1]`）
   - 期望：`GET /api/agents/{id}` 回读含两个绑定数组；重复 `agent_id` 返回业务错误码

4. AG-UI 聊天主链路
   - 输入：`curl -N -X POST /agui/run`（SSE，带 Bearer 与 `RunAgentInput`）
   - 期望：事件序列含 `RUN_STARTED` → `TEXT_MESSAGE_START/CONTENT/END` → `RUN_FINISHED`；结束后 `chat_session`、`chat_message` 有对应行；前端气泡流式出现且刷新后历史仍在

5. MCP
   - 输入：`POST /api/mcp-servers/{id}/sync-tools`（指向真实测试 MCP server）
   - 期望：返回非空工具清单并写入 `tool_cache`；错误地址返回业务错误与原因
   - 输入：绑定该 server 后对话要求调用其工具
   - 期望：出现 `TOOL_CALL_*` 事件且工具名带命名空间

6. HITL 卡片
   - 输入：`REQUIRE_APPROVAL` 模式下触发工具调用的对话
   - 期望：页面出现审批卡 → 点"批准" → 工具真实执行、run 继续；点"拒绝" → 收到拒绝反馈文本；`approval_record` 状态按 PENDING→APPROVED/DENIED 流转；非 session 属主调用 decide 返回 403
   - 输入：`demo-agent` 发送 `confirm_card` / `mapping_card` CUSTOM 事件
   - 期望：两种卡片按契约字段渲染，回传后 run 继续；未知 CUSTOM 事件降级为 JSON 折叠卡且不崩溃

7. Token 审计
   - 输入：完成 3 次对话后 `GET /api/audit/token/summary?userId=1&from=...&to=...`
   - 期望：返回非零 token 且 `byModel`、`byDay` 结构正确
   - 输入：USER 角色查询他人数据 / ADMIN 查询全员
   - 期望：前者被拒，后者成功

# 约束与不变量

- G1 依赖门禁：AgentScope 相关坐标与版本按方案 2.1（`io.agentscope:*:2.0.3`）；新增未列依赖需说明理由。
- G2 分层门禁：配置类包禁止 `import io.agentscope`；AgentScope API 仅允许在 `agui/`、`audit/`、`hitl/` 包。
- G3 契约门禁：方案第 5 节 API 路径/字段、第 6 节卡片 TS 契约冻结；变更需先改文档并在任务记录说明。
- G4 数据门禁：DDL 只允许出现在 `src/main/resources/db/migration/`；业务代码不得出现 `CREATE TABLE`/`ALTER TABLE`。
- G5 质量门禁：后端 `mvn verify`、前端 `npm run lint && npm run build && npx vitest run` 全绿。
- G6 证据门禁：每个任务附真实命令输出或页面证据，禁止"应该可以了"式声明。
- G7 范围门禁：超范围需求只进 `docs/backlog.md`。
- G8 框架用法门禁：MCP/Skill/AG-UI/HITL 必须走 AgentScope 官方扩展。
- 职责边界：Spring Boot 层只做 CRUD 配置、鉴权、会话、审计落库、配置→AgentScope 对象翻译、托管 `/agui/run`；不实现推理循环与协议。
- 安全：密码 BCrypt；api_key 入库 AES-GCM 加密、回读脱敏；`JWT_SECRET`、`AGENT_CRYPTO_KEY` 由环境变量提供。
- 凭据不入库位：Comet 产物（brief / spec / verification / summary）不得写入任何密码、连接串或密钥；连接信息只引用 `docs/技术方案.md` 与本地 `application-dev.yml`。
- 不得破坏共享中间件上其他应用的既有数据。

# 决定

环境调查结论与据此做出的实现决定（偏离方案处均已注明理由）：

- D1 工具链（实测）：使用机器上已存在且符合方案的版本，不新装工具。JDK 21 = `C:\Program Files\Java\jdk-21.0.3+9`；Maven = `C:\tools\apache-maven-3.9.16`；Node = `D:\Program Files\nodejs`（v24 + npm 11，方案要求 Node 20 LTS，用更新的可用版本）；PATH 默认的 Java 8 / Node 16 不使用。
- D2 无 Docker（实测：未安装 docker，亦无可用 WSL 发行版）→ Testcontainers 不可用。集成测试改为连接内网真实 PostgreSQL 与 Redis，使用**专用测试库/空 db**，测试数据用完清理；G5 的 `mvn verify` 仍作为门禁执行。偏离理由：机器无容器运行时，而方案要求真实集成验证。
- D3 数据库版本（实测）：内网 PostgreSQL 为 **18.3**，方案假设 16；Flyway 10.x（Boot 3.3.5 管理版本）不支持 PG 18，需 >= 11。构建期实测发现 **Flyway 12.x 已移除 `cleanOnValidationError(boolean)`**，而 Boot 3.3.5 的 `FlywayAutoConfiguration` 会调用它，启动直接失败（BeanCreationException）。因此最终采用 **Flyway 11.7.2**：既保留该 API，又能连 PG 18，应用启动与 v1–v3 迁移均已实测成功。Flyway 对 PG 18 仍输出“未测试”警告（其官方支持上限声明为 17），已记为已知风险。
- D4 Redis db 冲突（实测）：`db0`（569 keys）与 `db1`（1833 keys）已被其他应用占用，方案指定的 `db0/db1` 会与既有数据冲突。改用 **db10（会话）/ db11（缓存）**（实测为空），并统一 key 前缀 `myagent:`。偏离理由：避免污染共享实例中其他应用的数据。
- D5 目标库不存在（实测）：`agent_platform` 库尚未创建；`app_user` 具备 CREATEDB/SUPERUSER 权限。实现阶段创建 `agent_platform`（应用）与 `agent_platform_test`（集成测试）。Redis 与 PostgreSQL 均监听于 localhost 且可达。
- D6 工件可达性（实测）：Maven Central 与 npm registry 均可访问；方案 2.1 中全部 `io.agentscope:*:2.0.3` 坐标在 Central 存在（`agentscope-harness`、`extensions-agui`、`extensions-model-openai`、`extensions-model-dashscope`、`extensions-redis`、`extensions-postgresql`、`extensions-skills`）。因此 G1 的坐标可用，无需换版本。
- D7 范围：`docs/任务列表.md` 全部 16 个任务即本次 change 的完整目标规格；不额外征询是否缩小范围。实现可分批推进并保存 checkpoint，但完成判定不因分批而降级。
- D9 模型接入方式（用户已确认）：验收路径使用 **OpenAI 兼容端点**（`provider_type=OPENAI_COMPATIBLE`），对应 `agentscope-extensions-model-openai`；dashscope 扩展仍按方案保留在依赖中。具体 base_url / model id / api_key 只写入本地未提交的 `.env` 与 `application-dev.yml`，不进入任何 Comet 产物。
- D10 模型凭据的交付方式：用户已确认走 OpenAI 兼容协议，但尚未提供 `base_url` / `model id` / `api_key`。实现直接按 OpenAI 兼容协议落地；端点与密钥通过本地未提交配置 / 环境变量注入。Phase 2 连通性测试与 Phase 4–8 的链路 E2E 先用同协议本地 OpenAI 兼容桩服务（实现 `/v1/chat/completions`，含 SSE 流式与 usage 字段）完成机械验证并标注桩来源；用户提供真实凭据后复跑同一测试，以真实端点证据替换桩证据。此项不阻塞实现。
- D8 provider 类型映射：`OPENAI` / `OPENAI_COMPATIBLE` / `OLLAMA` / `GEMINI` 走 OpenAI 兼容端点（若存在官方扩展则优先官方扩展），`DASHSCOPE` 走 dashscope 扩展，`ANTHROPIC` 走官方 anthropic 扩展（若该版本提供）。具体以构建期实际可解析的官方扩展为准。

# 开放问题

无未决阻塞项。模型端点具体凭据属可后补的运维输入（见 D10），不作为阶段推进的阻塞项。

# 共享理解确认

目标、范围、非目标、关键决定（D1–D10 含四项环境偏离）、验收标准与验证期望已在本文件与 7 份 capability 规格中固化；用户已明确回复"确认"。

# 验证期望

- 后端：`mvn verify`（编译 + 单测 + 集成测试）0 失败；集成测试连接专用测试库/空 db。
- 前端：`npm run lint && npm run build && npx vitest run` 0 失败。
- 逐任务执行 `docs/任务列表.md` 的验收门禁，产出可复核证据（curl 输出、SQL 结果、截图或录屏）。
- Phase 9 全链路剧本（登录 → 配 provider/model 并 test 通过 → 建 Skill/MCP → 绑定建 Agent（REQUIRE_APPROVAL）→ 聊天（普通回复 + 工具卡 + 审批卡）→ 审计看板有数）证据归档到 `docs/acceptance/`。
- G1–G8 逐条复查并记录结果；被 G7 拦下的需求记录到 `docs/backlog.md`。
