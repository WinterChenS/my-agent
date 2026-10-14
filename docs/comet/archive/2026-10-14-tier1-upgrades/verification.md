---
generated_from_state_version: 15
---

# 验证

## 当前结果

- 结果: **已归档**
- 验证情况: **已完成检查，验证结果已确认**
- 目标周期: 2
- 迭代: 1
- 验证器尝试次数: 1
- 完成时间: 2026-10-14T07:46:47.990Z
- 摘要: tier1-upgrades 20 项验收全部通过（本轮重跑，证据均为当轮实测）。后端 mvn verify 138 通过；前端 eslint 0 error / vitest 73 通过 / tsc+build 成功；Playwright 全量 38 通过 0 失败 3 跳过（其中 verifier-tier1.spec.ts 的 7 条即本 change 的浏览器验收）。关键实测：①桩 dump 证明多模态模型收到的请求 parts=['text','image_url']（图片真的进了模型），未开多模态时请求不含 image、带明确中文提示且不再携带附件路径（避免诱导模型读图撑爆上下文）；图片三类限制均返回明确 400；②圆环用量可见、压缩实测 70,128→20,496 tokens、清空需二次确认且清空后 agent_state 无该会话行而聊天记录保留；③跑到一半重启后端，run 归置为 INTERRUPTED（「服务重启，这一轮未跑完」）；后台完成有站内提示且可跳回；④未设额度不拦、BLOCK 拒绝并给出已用/额度、WARN 放行；另修掉额度统计的时钟偏差漏计 bug。两处需求已按用户实际要求修订并同步 brief/spec：上下文用量改发送键旁圆环、上一轮提示收紧为只在「已中断/出错」时出现。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | 输入：编辑模型，打开「多模态」开关并保存 | V9 迁移给 llm_model 加 multimodal（默认 FALSE）+ 实体/DTO/服务全链路读写；浏览器实测（verifier-tier1 A1/A2，本轮改为自造模型，不动用户数据）：编辑弹窗有「支持多模态（图片输入）」开关，勾选保存后列表显示「多模态 开」，取消后回到「多模态 关」。 |
| A2 | passed | brief.md | 期望：模型列表/详情显示为已开启；关闭时不可发送图片 2. 图片真正喂给模型 | 模型列表「采样 / 长度」块内显示「多模态 开/关」；实测关闭状态下带图发送时图片不会被发给模型（见 A6）。 |
| A3 | passed | brief.md | 输入：向已开启多模态的模型发起一轮，附带一张图片 | 前端把暂存图片转为 AG-UI 内容块 {type:'image', source:{type:'data', value:<base64>, mimeType}}；实测向多模态模型发起一轮带图消息返回 RUN_FINISHED。 |
| A4 | passed | brief.md | 期望：发给模型的请求里包含图像内容（**桩捕获的请求体为证**：出现 image 内容块/base64），且模型据此回答 3. 未开启多模态时的行为 | 用会 dump 请求体的桩取证（本轮重跑）：请求 #0 model=t1v-mm 的消息内容块为 ['text','image_url']，图片为 data:image/png;base64,... —— 图像内容确实随请求下发，与验收指定的判定方式一致。 |
| A5 | passed | brief.md | 输入：向未开启多模态的模型附带图片发送 | 同一套请求换成 multimodal=false 的模型（请求 #2/#3 model=t1v-nomm），SSE 正常结束，未出现厂商 400。 |
| A6 | passed | brief.md | 期望：图片被剥离（不发给模型），前端/回复里明确提示「该模型未开启多模态，图片未发送」，不出现厂商 400 4. 图片限制 | 容器收到的 #2/#3 请求**不含任何 image**（含图片=False），且文本里带「（注意：用户上传了 N 张图片，但当前模型不支持图片输入，图片内容没有发给模型，也请不要去读取这些图片文件——二进制内容会撑爆上下文。需要看图请在模型配置里开启多模态、或改用支持图片的模型。）」；同时**不再携带附件路径**（含附件路径=False）。日志「模型未开启多模态，已剥离 N 张图片」。另把厂商拒收图片的报错翻成中文（IMAGE_NOT_SUPPORTED 分支真实触发过）。 |
| A7 | passed | brief.md | 输入：发送超过大小上限的图片 / 非法格式 / 超过数量上限 | 本轮实测三类限制：6 张 → {'code':400,'msg':'单轮最多发送 5 张图片（当前 6 张）。…'}；image/bmp → {'code':400,'msg':'第 1 张图片格式不支持（仅支持 png/jpeg/webp/gif）：image/bmp。…'}；单张 5.5MB → {'code':400,'msg':'第 1 张图片过大（5MB，上限 5MB）。…'}；单测 MultimodalGuardLimitsTest 覆盖。 |
| A8 | passed | brief.md | 期望：明确业务错误提示，不影响文本消息发送 5. 上下文用量可见 | 三类不合规均返回明确业务错误（400 + 中文原因 + 「请调整后重发（文本消息可单独发送）」）；同一 Agent 的纯文本轮次在相同配置下正常 RUN_FINISHED。 |
| A9 | passed | brief.md | 输入：打开一个较长会话 | GET /api/sessions/{id}/context 实测返回 estimatedTokens=44 / messages=2 / triggerTokens=120000 / keepTokens=32000 / compacted=false；长会话曾测得 738,311 tokens（阈值 120,000）。用量用官方 TokenCounterUtil 估算。 |
| A10 | passed | brief.md | 期望：**发送键旁的圆环**显示上下文用量与阈值（百分比 + 三档颜色），接近阈值时有醒目提示；点开浮层看细节 6. 上下文可控 | 浏览器实测（verifier-tier1 A9/A10）：发送键旁的圆环（data-testid=context-ring）可见，点开后浮层文本为「上下文 9 / 触发阈值 120,000 tokens（0%）」，并含消息条数、保留阈值、是否压缩过与状态提示；圆环三档颜色（绿/黄 ≥80%/红 ≥100%）。注：需求已按用户要求修订为「圆环 + 点开详情」（brief 与 specs/context-control 已同步）。 |
| A11 | passed | brief.md | 输入：点「压缩上下文」 | 圆环浮层内「压缩上下文」→ POST /api/sessions/{id}/context/compact → 官方 ConversationCompactor；浏览器实测点击后有反馈文案。 |
| A12 | passed | brief.md | 期望：触发官方压缩并给出反馈（压缩前后用量下降） | 本轮决定性实测：把上下文顶到 70,128 tokens 后压缩 → /context 返回 20,496 tokens（18→4 条，降 71%），框架日志「压缩上下文完成 … messages 18→4 tokens 70128→20496」；另在 8,832 tokens（低于保留预算 32,000）时压缩如实回报「框架判断当前无需精简」，行为正确。 |
| A13 | passed | brief.md | 输入：点「清空上下文」 | 「清空上下文」先弹二次确认（AlertDialog 说明「模型会从零开始，聊天记录与产物都保留」），确认后调用 clear；浏览器实测（verifier-tier1 A13）打印「已弹出『清空上下文』二次确认弹窗」。 |
| A14 | passed | brief.md | 期望：该会话模型上下文被清空（`agent_state` 无该会话状态），聊天记录仍在，随后对话正常 7. 运行状态持久化 | 本轮实测：清空前聊天记录 1 条 → 清空返回 {'removed':1} → 清空后 /context 返回 tokens=0/messages=0，而聊天记录仍 1 条；此前另测清空后立刻对话 RUN_STARTED→TEXT_MESSAGE_* 正常。 |
| A15 | passed | brief.md | 输入：发起一轮后重启后端，再打开该会话 | V10 agent_run + AgentRunService；本轮实测 /api/sessions/{id}/runs 返回 状态=FINISHED 摘要=stub-ok t1v-mm 止=2026-10-14T07:39:55Z。 |
| A16 | passed | brief.md | 期望：能看到该会话上一轮的状态，不是「什么都没有」。 **提示只在界面上看不到时才出现**（用户反馈收紧）：已完成的答案已在聊天记录里 → 不提示； 已中断（如服务重启）或出错 → 一律提示并带原因。 8. 长任务完成通知 | 本轮决定性实验：跑到一半时 /runs 状态=RUNNING；杀掉后端再启动 → 日志「启动归置：把 1 条残留的 RUNNING 记为 INTERRUPTED」；重启后该会话 run 历史为 状态=INTERRUPTED 错误=「服务重启，这一轮未跑完」（界面据此显示「上一轮：已中断（服务重启，这一轮未跑完）」）。需求已按用户反馈收紧为「只在界面上看不到时才提示：已完成不提示、已中断/出错提示并带原因」，文案已同步。 |
| A17 | passed | brief.md | 输入：切到别的会话，让原会话的 run 在后台跑完 | 本页发起的 run 登记为「后台盯梢」，切走后每 3s 轮询 /run，不再 running 即提示；浏览器实测（verifier-tier1 A17/A18）打印「站内提示: 会话「…」已完成」+「跳到该会话」。 |
| A18 | passed | brief.md | 期望：收到站内提示（toast/角标），点击可跳回该会话 9. 支出配额 | Playwright 实测：发起一轮 → 切到别的会话 → 原会话跑完后出现站内提示且「跳到该会话」可点击（verifier-tier1 与 phase20-background-notice 两处覆盖，均通过）。 |
| A19 | passed | brief.md | 输入：为某用户设置很小的日额度，连续对话直到超限 | V11 给 sys_user 与 agent 加日/月额度 + 模式；本轮实测：未设额度时 /api/quota/me 返回 日额度=None 超限=False 且对话不受影响；设置极小额度成功（PUT 200）。配额的两级判断由 QuotaServiceTest 9 条单测覆盖（含用户级/Agent 级/月额度三条分支，其中 Agent 级此前从未被测）。 |
| A20 | passed | brief.md | 期望：超限后按配置**拒绝发起**（或仅告警），提示当前用量与额度；审计看板显示已用/额度 | 本轮实测：BLOCK → 发起被拒 {'code':400,'msg':'已超出支出额度，无法发起：你今日已用 0.542922 CNY，日额度 0.000001，已超出额度上限。'}（含已用与额度）；换 WARN → 同条件 RUN_STARTED…RUN_FINISHED 放行且服务端打印「WARN 模式，放行」；审计看板显示「今日已用 …/ 日额度 不限 本月已用 …/ 月额度 不限」，设置后出现「已超出额度：…」且聊天页出现同等告警条。另修掉一个会漏计的真 bug：额度窗口上界原用应用进程 now()，而 token_usage.created_at 是数据库 now()，时钟有偏差（实测 DB 快 3 秒）时刚花掉的用量落在窗口外被静默漏计 → 改为整天/整月开区间。 |

## 检查

_没有记录 Runtime 检查。_

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- backend-verify: passed — mvn verify：138 通过 0 失败 0 错误（本轮新增 QuotaService 9 / SessionContextService 5 / ToolCatalogService 4 / SessionTitleService 2 等）
- frontend-checks: passed — eslint 0 error（3 warning 为 UI 组件风格提示）；vitest 73 通过；tsc -b 通过；vite build 成功（主 chunk 已由 2188KB 拆到 314KB）
- playwright-e2e: passed — 全量 38 通过 0 失败 3 跳过；其中 verifier-tier1.spec.ts 7 条为本 change 的验收用例（多模态开关 / 圆环用量 / 压缩 / 清空二次确认 / 上一轮提示 / 审计额度 / 聊天页额度告警）
- db-migrations: passed — V9 多模态 / V10 agent_run / V11 额度 均已在 agent_platform 成功应用
- 证据真实性: passed — 全部证据来自真实运行：真模型（用户配置的 OpenAI 兼容端点）+ 会 dump 请求体的桩 + 真实浏览器；无伪造数据
- 已知限制: 不做跨进程续跑/分布式队列：后端重启后 run 状态可见（INTERRUPTED）但不会自动接着跑（spec 明确排除，已登记 backlog）
- 已知限制: 配额只在发起前检查，不做事后强制中断（spec 明确排除）
- 已知限制: 上下文用量为官方 TokenCounterUtil 估算值，非厂商计费口径
- 已知限制: 配额不做汇率换算，货币沿用模型价格配置（Phase 15 口径）
- 已知限制: 压缩对「超大内容集中在最近消息」的会话效果有限（框架保护最近窗口），此时会如实回报「框架判断当前无需精简」，不做强制截断
- 已知限制: 「清空上下文」需二次确认后执行（用户要求）：不可逆

## 阻塞项

_无。_

## 风险与跳过的工作

- 不做跨进程续跑/分布式队列：后端重启后 run 状态可见（INTERRUPTED）但不会自动接着跑（spec 明确排除）。
- 配额只在发起前检查，不做事后强制中断；且依赖用户如实填写模型单价 —— 未填价格的模型成本按 0 计，额度不会触发（实测该分支：Agent 级额度设了但因模型无价而未拦，属预期）。
- 上下文用量为官方 TokenCounterUtil 估算值，非厂商计费口径。
- 压缩对「超大内容集中在最近消息」的会话效果有限（框架保护最近窗口），此时如实回报「无需精简」，不做强制截断。
- 本轮为构造者会话内的独立复跑核验（真实模型 + 桩 dump + 真实浏览器），非另起独立验收员执行。

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 1 | 1 | pass | — | 独立复跑通过：后端 mvn verify 105/105、BUILD SUCCESS；前端 eslint 0 error / vitest 70/70 / tsc / build 全绿；Playwright 23 通过 3 跳过（含本 change 新增 4 条）。线上逐条复跑取证：图片确随请求下发（桩 dump 出现 image_url+base64）/未开多模态时剥离并给中文提示/图片三类限制均 400；上下文用量可见且准确（0→51 tokens）、压缩实测 257,971→6,769 tokens、清空后 agent_state 无该会话行而聊天记录保留且随后对话正常；run 状态落库（FINISHED+摘要），跑到一半重启后归置为 INTERRUPTED 且 UI 显示「上一轮：已中断（服务重启，这一轮未跑完）」；额度 BLOCK 拒绝并给出已用/额度、WARN 放行、清空额度恢复不拦，审计看板显示已用/额度。20 项验收全部通过，无 failed/blocked。 | 2026-10-13T02:02:33.309Z |
| 1 | 1 | 1 | recovery | — | 用户反馈：上下文容量条太丑。已按反馈改实现（发送键旁圆环 + 点开详情 + 清空二次确认），需退回 Build 重新提交候选。 | 2026-10-13T03:05:32.300Z |
| 1 | 2 | 0 | recovery | — | Native confirmed acceptance criteria changed | 2026-10-14T07:37:55.146Z |
| 2 | 1 | 1 | pass | — | tier1-upgrades 20 项验收全部通过（本轮重跑，证据均为当轮实测）。后端 mvn verify 138 通过；前端 eslint 0 error / vitest 73 通过 / tsc+build 成功；Playwright 全量 38 通过 0 失败 3 跳过（其中 verifier-tier1.spec.ts 的 7 条即本 change 的浏览器验收）。关键实测：①桩 dump 证明多模态模型收到的请求 parts=['text','image_url']（图片真的进了模型），未开多模态时请求不含 image、带明确中文提示且不再携带附件路径（避免诱导模型读图撑爆上下文）；图片三类限制均返回明确 400；②圆环用量可见、压缩实测 70,128→20,496 tokens、清空需二次确认且清空后 agent_state 无该会话行而聊天记录保留；③跑到一半重启后端，run 归置为 INTERRUPTED（「服务重启，这一轮未跑完」）；后台完成有站内提示且可跳回；④未设额度不拦、BLOCK 拒绝并给出已用/额度、WARN 放行；另修掉额度统计的时钟偏差漏计 bug。两处需求已按用户实际要求修订并同步 brief/spec：上下文用量改发送键旁圆环、上一轮提示收紧为只在「已中断/出错」时出现。 | 2026-10-14T07:46:47.990Z |



## 结论

tier1-upgrades 20 项验收全部通过（本轮重跑，证据均为当轮实测）。后端 mvn verify 138 通过；前端 eslint 0 error / vitest 73 通过 / tsc+build 成功；Playwright 全量 38 通过 0 失败 3 跳过（其中 verifier-tier1.spec.ts 的 7 条即本 change 的浏览器验收）。关键实测：①桩 dump 证明多模态模型收到的请求 parts=['text','image_url']（图片真的进了模型），未开多模态时请求不含 image、带明确中文提示且不再携带附件路径（避免诱导模型读图撑爆上下文）；图片三类限制均返回明确 400；②圆环用量可见、压缩实测 70,128→20,496 tokens、清空需二次确认且清空后 agent_state 无该会话行而聊天记录保留；③跑到一半重启后端，run 归置为 INTERRUPTED（「服务重启，这一轮未跑完」）；后台完成有站内提示且可跳回；④未设额度不拦、BLOCK 拒绝并给出已用/额度、WARN 放行；另修掉额度统计的时钟偏差漏计 bug。两处需求已按用户实际要求修订并同步 brief/spec：上下文用量改发送键旁圆环、上一轮提示收紧为只在「已中断/出错」时出现。
