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

### 增量范围（用户新增需求，2026-10-11）

Phase 10：产物与文件（新增，基于同一平台）

1. **产物存储**：新增统一的产物/来源记录表；文件类内容存 MinIO，文本类内容（markdown / txt / json / csv 等）直接存数据库。
2. **对话输入框 `/` 选择 Skill**：输入 `/` 弹出 Skill 列表；选中后在输入框中成为一个**不可编辑的块**，可删除。
3. **对话上传文件**：上传到 MinIO，写入来源记录，并让 Agent 能够获取并处理该文件。
4. **右侧栏**：会话页右侧新增侧边栏，分区展示「来源文件」与「产物」。

环境（已实测）：MinIO `http://localhost:9000`（健康检查 200、凭据有效）；现有 bucket：`agent-images`、`nova-assets`。

Phase 11（用户新增需求，2026-10-11 第二轮）：记忆与会话状态入库 + 输入框上方附件

1. **记忆与会话状态入库**：对话消息已存 PostgreSQL，但 Agent 的长期记忆（工作区 `memory/*.md`）与框架会话状态（`agents/*/sessions/*.jsonl`）仍在磁盘。用户要求二者都存入数据库，并选定做法 **A**：
   - 会话状态：实现 PostgreSQL 版官方 `AgentStateStore`，替换框架默认的 JsonFile 实现，经 `HarnessAgent.Builder.stateStore(...)` 注入。
   - 长期记忆：使用官方 `RemoteFilesystem` + 自研 **PostgreSQL 版 `BaseStore`**（官方 SPI，方法仅 `get/put/search/delete`）承载记忆文件。
   - 门禁 G8 不变：不重写框架的记忆生成/合并与状态管理逻辑，只替换官方 SPI 的存储后端；不引入向量检索。
2. **输入框上方附件**：选中的文件以可移除条目显示在**输入框上方**，支持多选与追加；**发送时才上传**（沿用 Phase 10 的存储路由与物化逻辑），右侧栏「来源文件」照旧记录。暂存期间只在浏览器，不产生会话记录。

# 非目标

以下内容不实现，发现价值时只记录到 `docs/backlog.md`（门禁 G7）：

- RAG / 向量检索（含记忆的语义检索：Phase 11 只替换记忆的**存储后端**，不引入 embedding）、语音、A2A 多 Agent 协作协议、多租户、国际化
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

8. 产物与文件（Phase 10 增量）
   - 输入：向会话上传文本类文件（`.md`）
   - 期望：`artifact` 行 `role=SOURCE`、`storage=DB` 且 `content` 有值
   - 输入：向会话上传二进制文件（`.png`）
   - 期望：`storage=MINIO`；`GET /api/artifacts/{id}/download` 返回直链且下载内容 sha256 与原件一致
   - 输入：同一会话重复上传同一内容
   - 期望：不新增 `artifact` 行（按 session+sha256 去重）
   - 输入：上传后让 Agent 读取 `uploads/<文件名>`
   - 期望：Agent 用常规文件工具读到上传原文（文件物化到 `.myagent-workspace/<userId>/uploads/`）
   - 输入：让 Agent 写一个文件后结束 run
   - 期望：出现 `artifact(role=ARTIFACT)` 指向该文件，且不含 `agents/**`、`default/**`、`memory/**`、`.skills-cache/**`、`uploads/**`
   - 输入：会话页右侧栏
   - 期望：来源文件与产物分两区展示，文本类可预览、均可下载，并标注存储位置
   - 输入：输入框输入 `/` 选择技能
   - 期望：出现候选列表；选中后成为不可编辑块（可整块删除、不重复），发送时以 `skillIds` 并入该会话技能集
   - 输入：MinIO 不可用时上传二进制
   - 期望：返回明确业务错误（不是 200 成功），文本上传与对话主链路仍可用

9. 记忆与会话状态入库（Phase 11）
   - 输入：完成一轮会触发长期记忆（Memory Flush）的对话
   - 期望：长期记忆内容可在数据库的记忆表中用 SQL 查到，而不是只存在于工作区文件
   - 输入：同一 Agent 的下一轮对话
   - 期望：Agent 仍能读到此前写入的长期记忆（经官方文件系统接口从库中读取），行为与磁盘版一致
   - 输入：查询数据库表清单与数据
   - 期望：存在会话状态表与记忆表，且对话结束后其中有该会话/该用户的状态行

10. 输入框上方附件（Phase 11）
   - 输入：在输入框处一次选择两个文件（可再追加）
   - 期望：文件以**可移除的条目**显示在输入框上方，输入框仍可正常使用，多选不互相覆盖
   - 输入：点某个条目的移除按钮
   - 期望：仅该条目消失；因为尚未发送，数据库与工作区都不新增记录
   - 输入：带着附件点发送
   - 期望：附件随消息上传成功（右侧栏「来源文件」出现对应条目），且 Agent 能读取到附件内容

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
- D10 模型凭据的交付方式：用户已确认走 OpenAI 兼容协议，但尚未提供 `base_url` / `model id` / `api_key`。实现直接按 OpenAI 兼容协议落地；端点与密钥通过本地未提交配置 / 环境变量注入。Phase 2 连通性测试与 Phase 4–8 的链路 E2E 先用同协议本地 OpenAI 兼容桩服务（**已提交在 `tools/llm-stub/stub.py`**，实现 `/v1/chat/completions`，含 SSE 流式与 `usage` 字段）完成机械验证并标注桩来源；用户提供真实凭据后复跑同一测试，以真实端点证据替换桩证据。此项不阻塞实现。
- D8 provider 类型映射：`OPENAI` / `OPENAI_COMPATIBLE` / `OLLAMA` / `GEMINI` 走 OpenAI 兼容端点（若存在官方扩展则优先官方扩展），`DASHSCOPE` 走 dashscope 扩展，`ANTHROPIC` 走官方 anthropic 扩展（若该版本提供）。具体以构建期实际可解析的官方扩展为准。

- D11 MinIO 客户端：Java `io.minio:minio`（本机已缓存 8.5.17），endpoint / accessKey / secretKey / bucket 全部来自环境变量，不硬编码进代码。
- D17 增量需求的存储模型：**一张统一表** `artifact` 同时承载「来源文件」与「产物」，用 `role` 区分（`SOURCE` / `ARTIFACT`），用 `storage` 区分落地方式（`MINIO` / `DB`）。理由：用户要求「统一的表存产物信息」，且右侧栏要按来源/产物分区展示；一表比两表更容易保证会话级聚合与权限一致。
- D18 存储路由规则：按类型判定——**文本类**（`text/*`、markdown、json、csv、xml、yaml 等）正文写库（`storage=DB`）；**其它**（二进制/图片/压缩包/未知）写 MinIO（`storage=MINIO`）。两种情况都记录 `size` / `sha256` / `mime` / 原始文件名，便于去重与下载校验。
- D19 bucket：使用 `agent-platform`（不存在则启动时创建），下载链接基于 `MINIO_PUBLIC_ENDPOINT` 生成。
- D20 上传文件如何被 Agent 处理：上传后除写 `artifact(role=SOURCE)` 外，还会把文件**物化到 Agent 实际工作区**（实测为 `.myagent-workspace/<userId>/uploads/`，不是 threadId），使 Agent 用常规文件/命令工具即可读取处理；右侧栏另提供下载。这样不依赖 Agent 额外调用自定义工具。
- D21 `/` 选择 Skill 的语义（本次暂定，待确认）：选中的 Skill 作为**该会话**的启用技能集合（在该 Agent 已绑定技能基础上并入），经 AgentScope 官方 `SkillFilter` 生效（内容仍按官方渐进式披露按需加载）；输入框中以不可编辑块呈现，删除即从本次请求移除。

- D22 产物登记范围（用户确认选 **A** 自动全收）：每次 run 结束后对比 Agent 工作区 `.myagent-workspace/<userId>/`（实测：HarnessAgent 按属主分目录，不是 threadId），把本次 **新增或 mtime/size 变化** 的文件登记为 `artifact(role=ARTIFACT)`。**排除框架内部状态**：`agents/**/sessions/**`、`memory/**`、`.skills-cache/**`、`uploads/**`（上传的来源文件另有 SOURCE 记录），避免侧边栏被框架文件淹没。登记失败不影响 run。

- D23 记忆与会话状态的入库方式（用户确认选 **A**）：用官方 SPI 的 PostgreSQL 实现承载——会话状态走官方 `AgentStateStore`（实现 PG 版替换默认 JsonFile，经 builder 注入）；长期记忆走官方 `RemoteFilesystem` + 自研 PG 版 `BaseStore`（官方 SPI，仅 `get/put/search/delete`）。不改框架的记忆生成/合并流程，不引入向量检索；`chat_message` 继续作为用户可见对话的权威记录。
- D24 附件交互（用户确认）：附件在浏览器端**暂存**并显示在输入框上方（可移除、可多选/追加、按文件名+大小去重），**点发送时才上传**（沿用 D18 存储路由与 D20 物化）；暂存期间不写库、不产生 `artifact` 行。右侧栏「来源文件」保持既有行为。

# 开放问题

无未决阻塞项。

> Phase 10 增量已获用户明确确认（2026-10-11）：目标/范围（4 项）/关键决定 D11、D17–D22/验收标准/非目标均确认；其中 D21（`/` 选 Skill 为**会话级**技能集合，经官方 SkillFilter 生效）一并确认。

> 已确认（用户答 **A**）：产物登记范围 = **自动全收** —— 每次 run 结束后扫描该会话工作区，把本次 run 新增或修改的文件登记为产物；框架内部状态目录除外（见 D22）。

## Phase 10 实施进展（2026-10-11）

| 任务 | 状态 | 证据 |
|---|---|---|
| 10.1 统一产物表与存储层 | 完成 | V5 已应用到 `agent_platform`/`agent_platform_test`，索引 `idx_artifact_session_role`、`idx_artifact_sha256`；`mvn verify` 59 测试通过（含 `ArtifactServiceTest` 5 项：文本入 DB / 二进制进 MinIO 并回读 / 去重 / 非属主 403 / 超限拒绝） |
| 10.2 上传与物化 | 完成 | `scripts/verify-p10-artifacts.sh`：文本→DB、二进制→MINIO、去重、下载 sha256 一致、`uploads/` 物化可见、Agent 真读到上传原文 |
| 10.3 产物自动登记 | 完成 | 同脚本 8b：run 后 `role=ARTIFACT` 出现 Agent 写出的文件，且无 `memory/agents/default/sessions/.skills-cache/uploads` 混入；`WorkspaceArtifactScannerTest` 覆盖变更检测与排除 |
| 10.4 右侧栏 | 完成 | `docs/acceptance/task-10-panel-upload.png`、`task-10-artifact-area.png`；Playwright 断言两区、上传落区、文本预览、产物自动出现 |
| 10.5 `/` 选 Skill | 完成 | `task-10-skill-block.png`；Playwright 断言菜单、不可编辑块、去重、可删除；`selection.test.ts` 7 项单测；`AgentFactoryTest` 覆盖会话级技能的组合缓存键 |
| 降级 | 完成 | `scripts/verify-p10-minio-down.sh`：MinIO 不可用时后端起得来、二进制上传返回 `code=400` 明确错误、文本上传与对话主链路不受影响 |

**实施中修正的两处规格偏差（已回写 specs 与《技术方案》）**：
1. Agent 工作区是 `.myagent-workspace/<userId>/` 而非 `<threadId>/` —— 物化与产物扫描都必须按属主目录，否则 Agent 相对路径读不到上传文件、扫描也找不到产物。
2. MinIO 写入失败时若仍返回 `200 + code:0`（只把行标成 FAILED），前端会当成上传成功 —— 改为返回明确业务错误，失败行保留便于排查。

**验收全绿**：后端 `mvn verify` 59 通过；前端 lint 0 error / build 成功 / vitest 27 通过；Playwright 15 passed（Phase 10 三个用例全绿）。**清理**：验收产生的 21 条 artifact 行与 MinIO 对象、p10/e2e fixture 的 agent/model/provider/session 均已删除，库里只剩用户自己的数据。


# 共享理解确认

目标、范围、非目标、关键决定（D1–D10 含四项环境偏离）、验收标准与验证期望已在本文件与 7 份 capability 规格中固化；用户已明确回复"确认"。

# 验证期望

- 后端：`mvn verify`（编译 + 单测 + 集成测试）0 失败；集成测试连接专用测试库/空 db。
- 前端：`npm run lint && npm run build && npx vitest run` 0 失败。
- 逐任务执行 `docs/任务列表.md` 的验收门禁，产出可复核证据（curl 输出、SQL 结果、截图或录屏）。
- Phase 9 全链路剧本（登录 → 配 provider/model 并 test 通过 → 建 Skill/MCP → 绑定建 Agent（REQUIRE_APPROVAL）→ 聊天（普通回复 + 工具卡 + 审批卡）→ 审计看板有数）证据归档到 `docs/acceptance/`。
- G1–G8 逐条复查并记录结果；被 G7 拦下的需求记录到 `docs/backlog.md`。
