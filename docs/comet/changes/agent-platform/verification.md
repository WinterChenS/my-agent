---
generated_from_state_version: 45
---

# 验证

## 当前结果

- 结果: **验收通过，可归档**
- 验证情况: **已完成检查，验证结果已确认**
- 目标周期: 7
- 迭代: 1
- 验证器尝试次数: 1
- 完成时间: 2026-10-11T10:29:56.646Z
- 摘要: 独立只读验收完成：后端 mvn verify 87 测试 0 失败、前端 tsc/lint/vitest（66 测试）全绿；10 个 verify 脚本（p5/p6/p7/p72-confirm/p72-mapping/p8/p9/p10/p10-minio-down/p11/p12）全部退出码 0；e2e-chat.sh 22 个浏览器用例 19 passed/3 skipped（skipped 由 verify-p9 覆盖）；并对 A1–A10 契约做了直连后端 curl 与直连 PostgreSQL 的独立核验。86 项验收全部通过，无 failed、无 blocked。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | 输入：`POST /api/auth/login {"username":"admin","password":"Admin@123"}` | POST /api/auth/login {"username":"admin","password":"Admin@123"} 返回 code:0 并含 token（本机直连后端实测）。 |
| A2 | passed | brief.md | 期望：`{"code":0,"data":{"token":"<JWT>","user":{"id":1,"username":"admin","role":"ADMIN"}}}` | 响应 data.user 为 {id:1, username:admin, role:ADMIN}（另多出 displayName 字段，不影响契约字段）。 |
| A3 | passed | brief.md | 输入：无 token 访问 `GET /api/auth/me` | 无 token 访问 GET /api/auth/me 实测 HTTP 401。 |
| A4 | passed | brief.md | 期望：401；带 token 时返回当前用户 2. 大模型配置 | 带 token 返回当前用户 {id:1, username:admin, role:ADMIN}。 |
| A5 | passed | brief.md | 输入：新建 provider（含 api_key）后 `GET /api/model-providers` | 新建 provider（含 api_key）后响应中 apiKey 即为 ****（实测返回 "apiKey":"****"）。 |
| A6 | passed | brief.md | 期望：响应中 api_key 显示为 `****`，无明文；更新时不传 api_key 则保留旧密钥 | GET /api/model-providers 列表无明文密钥（grep 无 sk-super-secret-key），apiKey 显示 ****；更新不传 api_key 保留旧密钥由 admin.spec 连通测试用例覆盖。 |
| A7 | passed | brief.md | 输入：`POST /api/model-providers/{id}/test` | POST /api/model-providers/{id}/test 可用；verify-p9 实测返回 {"ok":true,"latencyMs":404}。 |
| A8 | passed | brief.md | 期望：`{"ok":true,"latencyMs":<n>}`；密钥无效时 `{"ok":false,"error":"..."}` 且 HTTP 非 500 3. Agent 配置与绑定 | 指向不可达端点时返回 {"ok":false,"latencyMs":269,"error":"ClosedChannelException"}，HTTP 200（非 500）。 |
| A9 | passed | brief.md | 输入：`POST /api/agents`（含 `skillIds:[1]`、`mcpServerIds:[1]`） | POST /api/agents 含 skillIds:[2]、mcpServerIds:[42] 创建成功并回显两个数组。 |
| A10 | passed | brief.md | 期望：`GET /api/agents/{id}` 回读含两个绑定数组；重复 `agent_id` 返回业务错误码 4. AG-UI 聊天主链路 | GET /api/agents/{id} 回读含 skillIds 与 mcpServerIds 两个绑定数组；重复 agent_id 实测返回 HTTP 409 {"code":409,"msg":"agent_id 已存在..."}。 |
| A11 | passed | brief.md | 输入：`curl -N -X POST /agui/run`（SSE，带 Bearer 与 `RunAgentInput`） | curl SSE /agui/run 可用；verify-p6/p7/p72 与 e2e-chat.sh 均观察到 SSE 事件流。 |
| A12 | passed | brief.md | 期望：事件序列含 `RUN_STARTED` → `TEXT_MESSAGE_START/CONTENT/END` → `RUN_FINISHED`；结束后 `chat_session`、`chat_message` 有对应行；前端气泡流式出现且刷新后历史仍在 5. MCP | 事件序列含 RUN_STARTED → TEXT_MESSAGE_START/CONTENT/END → RUN_FINISHED（p7/p72 输出与 chat.spec「流式气泡+刷新回放」用例通过）。 |
| A13 | passed | brief.md | 输入：`POST /api/mcp-servers/{id}/sync-tools`（指向真实测试 MCP server） | sync-tools 对真实 MCP server 返回非空工具清单（13 个工具）。 |
| A14 | passed | brief.md | 期望：返回非空工具清单并写入 `tool_cache`；错误地址返回业务错误与原因 | 工具清单写入 tool_cache（官方 agentscope 仓库）；错误地址返回 HTTP 400 业务错误与原因（verify-p6）。 |
| A15 | passed | brief.md | 输入：绑定该 server 后对话要求调用其工具 | 绑定该 server 后对话触发了工具调用并回传真实结果（Echo: hello-mcp）。 |
| A16 | passed | brief.md | 期望：出现 `TOOL_CALL_*` 事件且工具名带命名空间 6. HITL 卡片 | TOOL_CALL_* 事件出现且工具名带命名空间（模型可见 <server>__echo，事件内工具名同样带前缀）。 |
| A17 | passed | brief.md | 输入：`REQUIRE_APPROVAL` 模式下触发工具调用的对话 | REQUIRE_APPROVAL 模式触发工具调用被拦截并落 PENDING（verify-p7）。 |
| A18 | passed | brief.md | 期望：页面出现审批卡 → 点"批准" → 工具真实执行、run 继续；点"拒绝" → 收到拒绝反馈文本；`approval_record` 状态按 PENDING→APPROVED/DENIED 流转；非 session 属主调用 decide 返回 403 | 批准→工具真实执行（POST_ACTING SUCCESS）、拒绝→不执行；approval_record PENDING→APPROVED/DENIED 流转；非属主 decide 403、重复决定 409；Playwright 审批卡批准/拒绝剧本通过。 |
| A19 | passed | brief.md | 输入：`demo-agent` 发送 `confirm_card` / `mapping_card` CUSTOM 事件 | demo-agent 分别发出 confirm_card 与 mapping_card CUSTOM 事件（verify-p72 两种卡片均通过）。 |
| A20 | passed | brief.md | 期望：两种卡片按契约字段渲染，回传后 run 继续；未知 CUSTOM 事件降级为 JSON 折叠卡且不崩溃 7. Token 审计 | 两种卡片按契约字段（ConfirmCardData title/message/options/requestId、MappingCardData title/columns/rows）渲染；未知 CUSTOM 走 reducer 的 unknown 分支降级为 JSON 卡（CARD_BY_NAME[name] ?? "unknown"），前端不崩溃。 |
| A21 | passed | brief.md | 输入：完成 3 次对话后 `GET /api/audit/token/summary?userId=1&from=...&to=...` | 完成 3 次对话后 GET /api/audit/token/summary 返回非零 totalTokens（实测 5547249）。 |
| A22 | passed | brief.md | 期望：返回非零 token 且 `byModel`、`byDay` 结构正确 | summary 含非空 byModel 与 byDay，结构正确（verify-p8 断言通过）。 |
| A23 | passed | brief.md | 输入：USER 角色查询他人数据 / ADMIN 查询全员 | ADMIN 可看全员 users 列表；USER 查询他人数据返回 403（verify-p8 造普通用户实测）。 |
| A24 | passed | brief.md | 期望：前者被拒，后者成功 8. 产物与文件（Phase 10 增量） | 前者（USER 查他人）被拒 403，后者（ADMIN 查全员）成功。 |
| A25 | passed | brief.md | 输入：向会话上传文本类文件（`.md`） | 上传 .md 文本文件成功（verify-p10 HTTP 200）。 |
| A26 | passed | brief.md | 期望：`artifact` 行 `role=SOURCE`、`storage=DB` 且 `content` 有值 | artifact 行 role=SOURCE、storage=DB 且 content 有值（列表显示 id=110 role=SOURCE storage=DB file=note.md）。 |
| A27 | passed | brief.md | 输入：向会话上传二进制文件（`.png`） | 上传 .png 二进制成功（HTTP 200）。 |
| A28 | passed | brief.md | 期望：`storage=MINIO`；`GET /api/artifacts/{id}/download` 返回直链且下载内容 sha256 与原件一致 | storage=MINIO；下载返回内容且 sha256 与原件一致（verify-p10 断言 yes）。 |
| A29 | passed | brief.md | 输入：同一会话重复上传同一内容 | 同一会话重复上传同一内容被识别（md id 一致）。 |
| A30 | passed | brief.md | 期望：不新增 `artifact` 行（按 session+sha256 去重） | 重复上传不新增 artifact 行（按 session+sha256 去重）。 |
| A31 | passed | brief.md | 输入：上传后让 Agent 读取 `uploads/<文件名>` | 上传后 Agent 读取 uploads/<文件> 成功读到上传原文（verify-p10 8a）。 |
| A32 | passed | brief.md | 期望：Agent 用常规文件工具读到上传原文（文件物化到 `.myagent-workspace/<userId>/uploads/`） | 文件物化到 .myagent-workspace/<userId>/uploads/，Agent 用常规文件工具读到原文。 |
| A33 | passed | brief.md | 输入：让 Agent 写一个文件后结束 run | 让 Agent 写文件后结束 run，产物被登记（agent-out-*.md）。 |
| A34 | passed | brief.md | 期望：出现 `artifact(role=ARTIFACT)` 指向该文件，且不含 `agents/**`、`default/**`、`memory/**`、`.skills-cache/**`、`uploads/**` | 出现 artifact(role=ARTIFACT) 指向该文件；SQL 抽查 role=ARTIFACT count=1，且无 memory/agents/uploads/.skills-cache 等框架目录混入（verify-p10 断言 no）。 |
| A35 | passed | brief.md | 输入：会话页右侧栏 | 会话页右侧栏存在（SourceArtifactPanel 组件 + e2e phase10 通过）。 |
| A36 | passed | brief.md | 期望：来源文件与产物分两区展示，文本类可预览、均可下载，并标注存储位置 | 来源文件与产物分两区展示、文本类可预览、均可下载，并标注存储位置（"对象存储"/"数据库"，SourceArtifactPanel 代码 + e2e 通过）。 |
| A37 | passed | brief.md | 输入：输入框输入 `/` 选择技能 | 输入 / 弹出技能候选列表（e2e phase10 用例「/ 选技能」通过）。 |
| A38 | passed | brief.md | 期望：出现候选列表；选中后成为不可编辑块（可整块删除、不重复），发送时以 `skillIds` 并入该会话技能集 | 选中后成为不可编辑块、可整块删除、不重复，发送以 skillIds 并入会话技能集（e2e + selection.test.ts 7 项单测）。 |
| A39 | passed | brief.md | 输入：MinIO 不可用时上传二进制 | MinIO 指向不存在端口时后端起得来且二进制上传返回明确业务错误（HTTP 400 code:400）。 |
| A40 | passed | brief.md | 期望：返回明确业务错误（不是 200 成功），文本上传与对话主链路仍可用 9. 记忆与会话状态入库（Phase 11） | 文本上传仍成功（storage=DB）、对话主链路不受影响（verify-p10-minio-down 通过）。 |
| A41 | passed | brief.md | 输入：完成一轮会触发长期记忆（Memory Flush）的对话 | 完成触发 Memory Flush 的对话后记忆可查（verify-p11）。 |
| A42 | passed | brief.md | 期望：长期记忆内容可在数据库的记忆表中用 SQL 查到，而不是只存在于工作区文件 | 长期记忆可在 memory_store 表中 SQL 查到（namespace user/1，key /2026-10-11.md，payload 672 字节），不是只存磁盘。 |
| A43 | passed | brief.md | 输入：同一 Agent 的下一轮对话 | 同一 Agent 下一轮对话触发新 flush（verify-p11 第 4 步）。 |
| A44 | passed | brief.md | 期望：Agent 仍能读到此前写入的长期记忆（经官方文件系统接口从库中读取），行为与磁盘版一致 | 删除磁盘 memory 文件后，再跑一轮仍读到原标记 MEMO-MARKER（memory_store 仍含原标记，磁盘文件数为 0），证明读取来自数据库。 |
| A45 | passed | brief.md | 输入：查询数据库表清单与数据 | 直连 DB 查询：memory_store、agent_state 表存在且分别有 1 / 53 行。 |
| A46 | passed | brief.md | 期望：存在会话状态表与记忆表，且对话结束后其中有该会话/该用户的状态行 10. 输入框上方附件（Phase 11） | 会话状态表 agent_state 有 53 行、记忆表 memory_store 有 1 行；chat_session=53、chat_message=135、approval_record=8。 |
| A47 | passed | brief.md | 输入：在输入框处一次选择两个文件（可再追加） | 一次选择两个文件可再追加（e2e phase10「多选与追加」用例通过）。 |
| A48 | passed | brief.md | 期望：文件以**可移除的条目**显示在输入框上方，输入框仍可正常使用，多选不互相覆盖 | 文件以可移除条目显示在输入框上方，输入框可正常使用，多选不互相覆盖（AttachmentStaging + stageFiles 去重 + e2e）。 |
| A49 | passed | brief.md | 输入：点某个条目的移除按钮 | 点移除按钮仅该条目消失（removeAttachment + e2e）。 |
| A50 | passed | brief.md | 期望：仅该条目消失；因为尚未发送，数据库与工作区都不新增记录 | 尚未发送时移除不新增记录：e2e phase10 用例断言「移除后库里不留记录」，工作区也不新增。 |
| A51 | passed | brief.md | 输入：带着附件点发送 | 带附件点发送上传成功（e2e phase10「附件暂存+发送时上传」用例通过）。 |
| A52 | passed | brief.md | 期望：附件随消息上传成功（右侧栏「来源文件」出现对应条目），且 Agent 能读取到附件内容 11. 停止执行（Phase 12） | 右侧栏「来源文件」出现对应条目，且 Agent 能读到附件内容（e2e + verify-p10 8a）。 |
| A53 | passed | brief.md | 输入：生成过程中点「停止」 | 生成中调停止接口返回 {"interrupted":true}（verify-p12）。 |
| A54 | passed | brief.md | 期望：前端立即回到可输入状态；服务端该 run 被中断，此后不再产生新的 token 消耗；已生成内容保留并在气泡上标注「已停止」 | 前端 stopRun 先 abort SSE 再 interrupt；服务端被中断、停止后 token_usage 不再新增、助手消息落库带 stopped 标记（verify-p12 断言全过）。 |
| A55 | passed | brief.md | 输入：生成过程中直接关闭页面 / 断开连接 | 关闭页面/断开连接触发 SseEmitter onCompletion/onError/onTimeout → runningRuns.interrupt(threadId)（AguiRunController 代码接线）。 |
| A56 | passed | brief.md | 期望：服务端在数秒内自行中断该 run（不再继续跑完空烧 token） | 断开后服务端数秒内自行中断：onCompletion→interrupt 兜底 + RunningRunRegistryTest 单测验证 interrupt 打到官方 Agent.interrupt + p12 实测中断后 token 不再增长。 |
| A57 | passed | brief.md | 输入：没有运行中的 run 时调用停止接口 | 没有运行中的 run 时调用停止接口返回 {"interrupted":false}（verify-p12 断言通过）。 |
| A58 | passed | brief.md | 期望：返回业务上的 `{interrupted:false}`，不是 500 | 返回业务 {interrupted:false} 而非 500（verify-p12 PASS）。 |
| A59 | passed | brief.md | 输入：停止后继续提问 | 停止后继续提问，新 run 正常完成（verify-p12 控制组完整 run + chat.spec 多轮对话回归通过）。 |
| A60 | passed | brief.md | 期望：新的 run 正常完成，历史消息不丢 12. 子 Agent 事件卡（Phase 12） | 历史消息不丢（chat.spec「多轮对话：发第二条不清空之前的对话」与刷新回放用例通过）。 |
| A61 | passed | brief.md | 输入：触发一次子 Agent 执行（如 demo agent 的 `agent_spawn` / `agent_send`） | 触发子 Agent 执行时 subagent.* 事件族按 source 聚合成一张卡片（reducer applySubagent + SubagentCard）。 |
| A62 | passed | brief.md | 期望：界面不再出现「未识别事件」卡片；子 Agent 的输出聚合为**一张**可折叠卡片 | subagent.* 在 applyCustom 中先于 unknown 分支处理，不再出现「未识别事件」卡片；输出聚合为一张可折叠卡。 |
| A63 | passed | brief.md | 输入：查看收起态 | 收起态由 SubagentCard 折叠按钮控制。 |
| A64 | passed | brief.md | 期望：只占一行（子 Agent 名称 + 状态 + 工具调用次数），不刷屏 | 收起态只一行：子 Agent 名称 + 状态 + 工具调用次数（SubagentCard summary 实现）。 |
| A65 | passed | brief.md | 输入：展开该卡片 | 展开卡片可见正文与工具调用行（SubagentCard expanded 分支）。 |
| A66 | passed | brief.md | 期望：可见子 Agent 的正文文本与工具调用行，信息不丢失 | 展开后正文（subagent.text）与工具调用行不丢失（reducer 追加 delta + reducer.test 覆盖）。 |
| A67 | passed | brief.md | 输入：一轮里先后出现两个子 Agent | 一轮出现两个子 Agent 按 source 各成一张卡片（reducer.test 覆盖 t1/alpha、t1/beta 分开）。 |
| A68 | passed | brief.md | 期望：按 `source` 各成一张卡片，互不混淆；父 Agent 的主回答照常流式渲染 13. 审计时间参数容错（Phase 12，顺带修复） | 父 Agent 主回答照常流式渲染（文本事件独立于 subagent 聚合，chat.spec 流式通过）。 |
| A69 | passed | brief.md | 输入：`GET /api/audit/token/summary?from=2026-10-10&to=2026-10-11`（只传日期） | 只传日期 from=2026-10-10&to=2026-10-11 正常返回（verify-p12 断言 200）。 |
| A70 | passed | brief.md | 期望：正常返回聚合结果（日期按服务端时区补齐），不是 500 | 日期按服务端时区补齐，非 500（verify-p12 PASS）。 |
| A71 | passed | brief.md | 输入：传入非法时间字符串 | 非法时间字符串返回明确业务错误 400（verify-p12 PASS）。 |
| A72 | passed | brief.md | 期望：返回明确业务错误，不是 500 14. 会话列表时间分组（Phase 13） | 非法输入返回 400 而非 500（verify-p12 PASS）。 |
| A73 | passed | brief.md | 输入：打开聊天页（存在多个不同时间的会话） | 聊天页左侧按分组显示（e2e phase13「会话列表按时间分组显示」通过）。 |
| A74 | passed | brief.md | 期望：左侧按「今天 / 昨天 / 最近 7 天 / 最近 30 天 / 更早」分组显示，每组有标题；空分组不显示 | 分组为今天/昨天/最近7天/最近30天/更早、每组有标题、空分组不显示（sessionGroups.ts + 单测 + e2e）。 |
| A75 | passed | brief.md | 输入：刚聊过的会话 | 刚聊过的会话出现在「今天」组（按 lastActiveAt 分组）。 |
| A76 | passed | brief.md | 期望：出现在「今天」组（按最后活跃时间，而不是按创建时间落到更早的组） | 取最后活跃时间而非创建时间：sessionTime 优先 lastActiveAt 缺失回退 createdAt（单测覆盖）。 |
| A77 | passed | brief.md | 输入：某个会话的时间无法解析 | 时间无法解析时归入「更早」（sessionTime 返回 null → groupOf 更早，单测覆盖）。 |
| A78 | passed | brief.md | 期望：归入「更早」，不会从列表里消失 15. 输入框拖拽与粘贴附件（Phase 13） | 解析失败不会从列表消失（单测「取不到时间时归入更早，不丢条目」）。 |
| A79 | passed | brief.md | 输入：把文件拖到聊天区上方 | 拖文件到聊天区触发 dragover/drop 处理（ChatPage section onDragOver/onDrop）。 |
| A80 | passed | brief.md | 期望：出现落区提示；松手后文件成为**输入框上方的待发送附件**（此时尚未上传，右侧栏没有来源文件） | 出现落区提示（data-testid=drop-hint），松手成为输入框上方待发送附件，此时右侧栏无来源文件（e2e phase13 断言 panel-sources-item=0）。 |
| A81 | passed | brief.md | 输入：在输入框里粘贴一张截图或一个文件 | 输入框粘贴截图/文件进入待发送附件区（ChatPage onPaste + filesFromClipboard）。 |
| A82 | passed | brief.md | 期望：同样进入待发送附件区；粘贴**纯文本**时保持原有输入行为（不拦截） | 纯文本粘贴不拦截：files.length===0 时不 preventDefault，保持原输入行为（代码 + sources.test.ts 覆盖）。 |
| A83 | passed | brief.md | 输入：一次拖入/粘贴多张截图 | 一次拖入/粘贴多张截图互不覆盖（normalizeIncomingFiles 补名）。 |
| A84 | passed | brief.md | 期望：多张互不覆盖（自动补不重名的名字），可分别移除 | 通用名/空名补成不重名可读名字（粘贴文件-<ts>-<n>.<ext>），可分别移除（sources.test.ts 覆盖多截图唯一名）。 |
| A85 | passed | brief.md | 输入：带着这些附件发送 | 带附件发送与点选上传走同一暂存链路（stageFiles/normalizeIncomingFiles 统一入口）。 |
| A86 | passed | brief.md | 期望：与点选上传完全一致：右侧栏「来源文件」出现对应条目，Agent 能读到附件内容 | 发送后右侧栏「来源文件」出现对应条目、Agent 能读到内容（与点选完全一致，e2e phase10 发送上传 + verify-p10 Agent 读取）。 |

## 检查

_没有记录 Runtime 检查。_

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- backend: mvn verify: passed — Tests run: 87, Failures: 0, Errors: 0
- frontend: tsc/lint/build: passed — 类型检查无错误；lint 0 error（3 条既有 warning）；build 成功
- frontend: vitest run: passed — 66 passed（新增 sessionGroups 7 项、attachments/sources 9 项）
- playwright: 全量 e2e（scripts/e2e-chat.sh）: passed — 19 passed / 3 skipped（新增 phase13 三用例：分组显示、拖入即成为待发送附件、落区提示）
- scripts/verify-p12-stop.sh: passed — 停止执行：interrupted=true、停止后 token 不再新增、落库 stopped、迟到停止 false(200)、审计日期容错
- scripts/verify-p11-memory-read.sh: passed — 记忆入库 + 读取来自库的判决性实验
- scripts/verify-p10-artifacts.sh: passed — 产物/来源：路由、去重、下载 sha256、物化后 Agent 读到、自动登记
- scripts/verify-p10-minio-down.sh: passed — MinIO 不可用降级
- scripts/verify-p9-acceptance.sh: passed — 浏览器 4 剧本（工具卡/审批批准/拒绝/审计看板）
- scripts/verify-p8-audit.sh: passed — Token 审计与越权 403
- scripts/verify-p7-hitl.sh: passed — HITL 拦截→批准→resume 真执行
- scripts/verify-p72-cards.sh: passed — 卡片契约
- scripts/verify-p6-mcp.sh: passed — MCP 命名空间 + 真实执行回显
- scripts/verify-p5-skills.sh: passed — Skill 注入与对照组
- 已知限制: 粘贴来源只有前端单测（sources.test.ts）+ ChatPage onPaste 接线证据，没有浏览器端「粘贴文件」的 e2e（拖拽有两条 e2e）。
- 已知限制: 框架的中断是循环边界生效：已在飞的模型调用不会被掐断，因此单轮被停止时正文仍可能完整，被阻止的是后续轮次/收尾；验收以「停止后不再产生新的模型调用」为准。
- 已知限制: 子 Agent 事件卡只在实时流中渲染（该族事件不落库），刷新后不会重现；历史回放需另设计落库方案（非目标）。
- 已知限制: 停止执行的 UI 证据以 reducer 单测 + 后端脚本为主；Playwright 未做「生成中点停止」用例（需要可控的慢模型）。
- 已知限制: agent_state 的 agent 维度是框架语义里的 userId（RuntimeContext.getUserId()），不是平台 agent 主键。
- 已知限制: 真实大模型（用户自维护的 deepseek 供应商）未纳入自动验收；e2e 中 MCP 管理页依赖 npx 拉包（首次需联网）。

## 阻塞项

_无。_

## 风险与跳过的工作

- A55/A56（关页面自动中断）无专门的浏览器断开 e2e 用例，结论基于 AguiRunController onCompletion/onError/onTimeout→runningRuns.interrupt 的代码接线 + RunningRunRegistryTest 单测（interrupt 打到官方 Agent.interrupt）+ verify-p12 对中断后 token 不再增长的实测。
- e2e-chat.sh 的 22 个 Playwright 用例中有 3 个（acceptance.spec 审批卡三个剧本）显示 skipped；它们已由 verify-p9-acceptance.sh 单独跑通（4 passed），不构成失败。
- Flyway 对 PostgreSQL 18.3 输出官方「未测试版本」警告（支持上限声明 17），但 v1–v6 迁移已全部成功应用，标记为已知风险。
- A8 无效密钥路径以「不可达端点」触发（返回 ok:false + error，HTTP 200），满足「HTTP 非 500」契约；未用真实服务端拒绝的 401/403 场景单独验证。

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 1 | 0 | recovery | — | Native Shape artifacts changed | 2026-10-10T14:43:44.162Z |
| 2 | 1 | 1 | pass | — | 逐条复核 A1-A24：全部通过。核验方式为在本机重新执行全部命令：后端 mvn verify 51 测试通过、前端 lint/build/vitest(20) 通过、6 个验收脚本（P5 Skill / P6 MCP / P7 HITL / P7.2 卡片×2 / P8 审计）断言全绿、Playwright 浏览器剧本 4 passed 并产出 9 张证据截图。门禁 G1-G8 逐条 grep 复查通过。重要说明：本轮核验由实现者本人执行（平台无可派发 subagent），独立性不足已列入 risks。 | 2026-10-10T15:16:38.207Z |
| 2 | 1 | 1 | recovery | — | 用户选择 revise-implementation：不接受当前验证结论，将自行用真实大模型端点配置并自测后再反馈；本回合先启动前后端供其使用，待其反馈具体问题后回到 Build 修复。 | 2026-10-10T15:28:57.774Z |
| 2 | 2 | 0 | recovery | — | Native target specification declarations changed | 2026-10-11T03:54:59.154Z |
| 3 | 1 | 1 | fail | A16 | 我独立跑了后端 mvn verify（59 测试全绿）、前端 tsc -b --noEmit/lint/vitest（27 测试）与 npm run build（全绿），并串行执行了 8 个 e2e 脚本（p5/p6/p7/p72/p8/p9/p10-artifacts/p10-minio-down）及 phase10.spec.ts 三个 Playwright 用例。除 A16 外其余验收项均通过实测或代码契约核验。A16 失败原因明确：MCP 工具真实执行且 TOOL_CALL_* 事件齐全，但 toolCallName 为裸名 'echo'、无 server 命名空间，违反冻结 spec。p9 首次运行超时、复跑通过，属于环境/脚本抖动已记入风险。未能独立跑通的只有无效 provider key 的 live 测试（改为代码核验 ok:false+error 分支）与重复 agent_id 的 live 测试（改为代码核验 conflict 分支），其余均有实测证据。 | 2026-10-11T05:00:48.265Z |
| 3 | 2 | 0 | recovery | — | Native Shape artifacts changed | 2026-10-11T05:14:06.834Z |
| 4 | 1 | 1 | pass | — | 我独立跑了后端 mvn verify（63 tests 全过）、前端 tsc/lint/vitest（27 项全过），并串行跑了 8 个 E2E 脚本：p5/p6(复跑)/p7/p72(confirm+mapping)/p8/p9(Playwright 4 passed)/p10-artifacts/p10-minio-down 全部 PASS；另手动起后端实测 A1-A10 的登录/401/密钥脱敏/连通测试/agent 绑定与重复 id 契约、A11-A12 的 SSE 事件序列与 chat_session/chat_message 落库，并用 phase10.spec.ts 实测 A35-A38 右侧栏与 / 选技能。40 项验收全部有实测证据，无一 blocked。未能独立确认的部分只有页面视觉细节（依赖 Playwright 断言与既有截图），但相关用例均通过。 | 2026-10-11T06:12:33.440Z |
| 4 | 1 | 1 | recovery | — | 用户在独立验收通过后提出两条新增需求，需要修订需求后重新实现与验收： （一）记忆与会话状态入库：目前对话消息已在 PostgreSQL，但 Agent 的长期记忆（工作区 memory/*.md）与框架会话状态（agents/*/sessions/*.jsonl）仍在磁盘。用户要求都存到数据库，并选定做法 A —— 用官方 SPI 实现 PostgreSQL 版存储（AgentStateStore 承载会话状态；官方 RemoteFilesystem + PostgreSQL 版 BaseStore 承载长期记忆），改动集中在存储层，Agent 工具与对话链路行为不变。 （二）附件交互调整：上传的文件要在输入框上方以可移除的形式显示、支持多选；用户选定「暂存 + 发送时上传」（暂存期间只在浏览器，不产生会话记录）。 | 2026-10-11T06:27:35.049Z |
| 5 | 1 | 1 | pass | — | 我独立跑了后端 mvn verify（71 测试通过、BUILD SUCCESS）、前端 tsc -b（0 错）/eslint（0 error 3 warning）/vitest（36 通过），串行执行了 verify-p5/p6/p7/p72/p8/p10/p10-minio-down/p11 与浏览器 e2e-chat.sh（Playwright 16 passed/3 skipped）。另外自启后端+桩，用 curl 与 psycopg2 直接核实了 A1-A10（登录/鉴权/密钥脱敏与保留/连通测试/agent 绑定与重复 agent_id），并用 SQL 亲自查证了 memory_store、agent_state（主键维度为 userId=1）、tool_cache、artifact 去重与 chat_message/chat_session 落库。52 项全部通过；其中 A8 的『无效密钥→ok:false』因桩不校验密钥改以不可达端点取证，A13 首次冷启动出现过一次 sync-tools 失败但热缓存重跑全绿，已列入风险。未能独立确认的是真实模型端点下的行为（无真实凭据）。 | 2026-10-11T07:40:10.170Z |
| 5 | 1 | 1 | recovery | — | 用户实测发现 Phase 11 附件功能的两个真实缺陷，需要继续改实现： 1）附件上传后，用户消息气泡里不显示附件（看不到自己发了什么文件）； 2）Agent 感知不到这一轮带了附件——上传只把文件物化到工作区 uploads/，但没有任何信息随该条消息传给模型， 导致模型只能去列工作区猜测（实测跑了 18 个工具调用后反过来问用户"您指的是哪个文件"）。 修复方向：发送时把"本轮附件清单（工作区相对路径 uploads/<fileName>）"随用户消息一起传给模型， 并在消息气泡里把附件渲染成可见的条目（可回放）。范围仅限这两点，不改既有 52 项验收口径。 | 2026-10-11T07:44:44.081Z |
| 5 | 2 | 1 | pass | — | 52 项验收全部通过。后端 mvn verify 74 测试通过（0 失败）；前端 tsc/lint/build/vitest 全绿（vitest 43 通过，lint 0 error）；串行验证脚本 p5/p6/p7/p72/p8/p9/p10/p10-minio-down/p11 全部 exit 0；浏览器全量 e2e 16 passed/3 skipped。用户实测的三个修复已亲自核实：① UTF-16/非法 UTF-8 文本上传返回 200 且 storage=MINIO（合格 UTF-8 仍 storage=DB，不再 500）；② 附件气泡 data-testid=message-attachment 可见，库中 USER 消息含 [已上传附件] 与 uploads/<文件名>；③ MEMORY.md/AGENTS.md/KNOWLEDGE.md/tools.json 被 WorkspaceArtifactScanner 排除，不出现在产物区。契约项均对照 spec 与数据库核对，非仅看测试通过。 | 2026-10-11T08:45:18.260Z |
| 5 | 2 | 1 | recovery | — | 第 4 轮独立验收已 52/52 通过。用户在此基础上提出新增需求，需要需求修订： （一）停止执行：生成过程中用户要能主动停止。已核对框架有官方中断 API（HarnessAgent.interrupt()/interrupt(RuntimeContext)/interrupt(agentId, sessionId)）， 因此可让服务端真正中断（而非仅断开前端 SSE），不需要自研中断逻辑（G8）。用户已认可拟定范围： 前端生成中把「发送」换成「停止」并中断在途 SSE、保留已生成内容并标注已停止；后端新增停止入口调用官方 interrupt； 客户端断开时也自动中断避免空烧 token；被停止的消息落库可回放。 （二）子 Agent 事件卡：官方 AG-UI 扩展的 SubagentEventConverter 会发出 subagent.lifecycle/text/thinking/tool_call/tool_result/require_confirm 这族 CUSTOM 事件（value 含 source/type/delta/name/toolName 等）。当前前端只认卡片类 CUSTOM，其余一律降级成「未识别事件」JSON 卡， 而 subagent.text 是逐 delta 发送 → 界面被刷屏（用户实测截图）。需求是按 source 聚合成一个「子 Agent 输出」可折叠卡片（默认收起、展开可读正文与工具行），保持简约美观。 （三）顺带修复第 4 轮验收报告的风险项：GET /api/audit/token/summary 只传日期（如 2026-10-10）会 500，应接受日期或给出明确业务错误。 | 2026-10-11T08:45:32.000Z |
| 6 | 1 | 1 | pass | — | 独立只读验收通过。后端 mvn verify 成功（87 tests，0 失败）；前端 tsc -b --noEmit + eslint（0 error，3 warning）+ vitest（66 passed）全绿；10 个串行验收脚本（p5 skills / p6 mcp / p7 hitl / p72 cards / p8 audit / p9 acceptance / p10 artifacts / p10-minio-down / p11-memory-read / p12-stop）全部 exit=0；浏览器 e2e-chat.sh 19 passed + 3 skipped（skipped 的 acceptance.spec 三例在 verify-p9-acceptance.sh 中已通过，含 phase13 会话分组/拖拽/落区提示三用例）。逐项对照 brief 验收示例 1–13（72 项）与 13 份 spec 契约、并用 DB SQL 抽查（agent_state/memory_store/artifact/approval_record 状态流转、api_key 掩码、绑定数组回读、sha256 去重），全部通过。Phase 13 两项顺带优化（会话时间分组、拖拽/粘贴附件走同一暂存链路）亦核实通过。 | 2026-10-11T10:03:01.539Z |
| 6 | 1 | 1 | recovery | — | 第 5 轮独立验收已 72/72 通过。用户要求把本轮已实现并验证过的两个优化正式登记为验收项（Phase 13）： （一）会话历史列表按时间分组：用户反馈左侧会话一长条太乱。需求为按「今天/昨天/最近 7 天/最近 30 天/更早」分组显示， 时间取最后活跃时间（缺失回退创建时间），空分组不显示，组内顺序不变；跨日边界与时钟漂移要有确定行为。 （二）输入框支持拖入与粘贴文件/图片：除点选按钮外，支持把文件拖进聊天区、以及在输入框粘贴截图/文件； 两者都必须与点选走同一条暂存链路（D24：发送时才上传、暂存期间不写库），多张截图不能因重名互相覆盖， 纯文本粘贴保持原行为。 说明：这两项代码已实现并通过我这边与独立验收的核实，本次修订主要是把它们登记为正式验收标准（口径与实现一致）。 | 2026-10-11T10:04:06.747Z |
| 7 | 1 | 1 | pass | — | 独立只读验收完成：后端 mvn verify 87 测试 0 失败、前端 tsc/lint/vitest（66 测试）全绿；10 个 verify 脚本（p5/p6/p7/p72-confirm/p72-mapping/p8/p9/p10/p10-minio-down/p11/p12）全部退出码 0；e2e-chat.sh 22 个浏览器用例 19 passed/3 skipped（skipped 由 verify-p9 覆盖）；并对 A1–A10 契约做了直连后端 curl 与直连 PostgreSQL 的独立核验。86 项验收全部通过，无 failed、无 blocked。 | 2026-10-11T10:29:56.646Z |



## 结论

独立只读验收完成：后端 mvn verify 87 测试 0 失败、前端 tsc/lint/vitest（66 测试）全绿；10 个 verify 脚本（p5/p6/p7/p72-confirm/p72-mapping/p8/p9/p10/p10-minio-down/p11/p12）全部退出码 0；e2e-chat.sh 22 个浏览器用例 19 passed/3 skipped（skipped 由 verify-p9 覆盖）；并对 A1–A10 契约做了直连后端 curl 与直连 PostgreSQL 的独立核验。86 项验收全部通过，无 failed、无 blocked。
