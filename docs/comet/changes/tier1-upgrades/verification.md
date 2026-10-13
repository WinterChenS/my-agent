---
generated_from_state_version: 6
---

# 验证

## 当前结果

- 结果: **验收通过，需要你确认**
- 验证情况: **已完成检查，但需要你确认验证结果**
- 目标周期: 1
- 迭代: 1
- 验证器尝试次数: 1
- 完成时间: 2026-10-13T02:02:33.309Z
- 摘要: 独立复跑通过：后端 mvn verify 105/105、BUILD SUCCESS；前端 eslint 0 error / vitest 70/70 / tsc / build 全绿；Playwright 23 通过 3 跳过（含本 change 新增 4 条）。线上逐条复跑取证：图片确随请求下发（桩 dump 出现 image_url+base64）/未开多模态时剥离并给中文提示/图片三类限制均 400；上下文用量可见且准确（0→51 tokens）、压缩实测 257,971→6,769 tokens、清空后 agent_state 无该会话行而聊天记录保留且随后对话正常；run 状态落库（FINISHED+摘要），跑到一半重启后归置为 INTERRUPTED 且 UI 显示「上一轮：已中断（服务重启，这一轮未跑完）」；额度 BLOCK 拒绝并给出已用/额度、WARN 放行、清空额度恢复不拦，审计看板显示已用/额度。20 项验收全部通过，无 failed/blocked。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | 输入：编辑模型，打开「多模态」开关并保存 | PUT /api/models/{id} multimodal=true 回显 True；V9 已应用；前端开关存在。 |
| A2 | passed | brief.md | 期望：模型列表/详情显示为已开启；关闭时不可发送图片 2. 图片真正喂给模型 | 关闭时列表显示「多模态 关」，且该状态下图片被剥离（A6 证据）。 |
| A3 | passed | brief.md | 输入：向已开启多模态的模型发起一轮，附带一张图片 | 对 multimodal=true 模型发带图轮次，SSE 正常 RUN_STARTED…RUN_FINISHED。 |
| A4 | passed | brief.md | 期望：发给模型的请求里包含图像内容（**桩捕获的请求体为证**：出现 image 内容块/base64），且模型据此回答 3. 未开启多模态时的行为 | 桩 dump 请求体：parts=['text','image_url']，data:image/png;base64,iVBOR…（图像确随请求下发）。 |
| A5 | passed | brief.md | 输入：向未开启多模态的模型附带图片发送 | multimodal=false 后同 Agent 带图发轮，正常完成无 400。 |
| A6 | passed | brief.md | 期望：图片被剥离（不发给模型），前端/回复里明确提示「该模型未开启多模态，图片未发送」，不出现厂商 400 4. 图片限制 | 该轮请求不含 image；消息带「当前模型未开启多模态，本轮 1 张图片未发送给模型」；日志「已剥离 1 张图片」。 |
| A7 | passed | brief.md | 输入：发送超过大小上限的图片 / 非法格式 / 超过数量上限 | 6 png + 1 bmp → 400「第 7 张图片格式不支持…；单轮最多发送 5 张」；5.5MB → 400「第 1 张图片过大（5MB，上限 5MB）」。 |
| A8 | passed | brief.md | 期望：明确业务错误提示，不影响文本消息发送 5. 上下文用量可见 | 三类校验均 400 + 中文原因 + 可单独发文本；纯文本轮次正常完成。 |
| A9 | passed | brief.md | 输入：打开一个较长会话 | 新会话 0 → 轮后 51 tokens/2 条；长会话 738311 tokens / 阈值 120000。 |
| A10 | passed | brief.md | 期望：输入框上方显示上下文用量与阈值，接近阈值时有醒目提示 6. 上下文可控 | ContextBar 显示用量/阈值/百分比，≥80% 黄、超阈值红；Playwright 断言用量条可见。 |
| A11 | passed | brief.md | 输入：点「压缩上下文」 | POST /context/compact 返回 200，走官方 ConversationCompactor；e2e 断言有反馈。 |
| A12 | passed | brief.md | 期望：触发官方压缩并给出反馈（压缩前后用量下降） | 会话 305 压缩 257,971 → 6,769 tokens（13→10 条），日志 tokens 257971→6769。 |
| A13 | passed | brief.md | 输入：点「清空上下文」 | POST /context/clear → {"removed":1}。 |
| A14 | passed | brief.md | 期望：该会话模型上下文被清空（`agent_state` 无该会话状态），聊天记录仍在，随后对话正常 7. 运行状态持久化 | 清空后 tokens=0/messages=0（agent_state 行已删），聊天记录仍 2 条；随后对话 RUN_STARTED→TEXT_MESSAGE_* 正常。 |
| A15 | passed | brief.md | 输入：发起一轮后重启后端，再打开该会话 | V10 agent_run + AgentRunService；/runs 返回 FINISHED + 摘要「好」+ 结束时间。 |
| A16 | passed | brief.md | 期望：能看到该会话上一轮的状态（已完成/已中断），不是「什么都没有」 8. 长任务完成通知 | 跑到一半 RUNNING → 重启 → 日志「把 1 条残留的 RUNNING 记为 INTERRUPTED」→ UI「上一轮：已中断（服务重启，这一轮未跑完）」。 |
| A17 | passed | brief.md | 输入：切到别的会话，让原会话的 run 在后台跑完 | 发起时登记后台盯梢，切走后每 3s 轮询 /run，不再 running 即提示；切会话不中断后端轮次。 |
| A18 | passed | brief.md | 期望：收到站内提示（toast/角标），点击可跳回该会话 9. 支出配额 | Playwright phase20 实测约 21s 后出现「会话「X」已完成」+「跳到该会话」按钮，通过。 |
| A19 | passed | brief.md | 输入：为某用户设置很小的日额度，连续对话直到超限 | PUT /api/users/1/quota dailyQuota=0.000001 → 超限=True，随后对话被拦。 |
| A20 | passed | brief.md | 期望：超限后按配置**拒绝发起**（或仅告警），提示当前用量与额度；审计看板显示已用/额度 | BLOCK → 400「已超出支出额度，无法发起：你今日已用 0.55327 CNY，日额度 0.000001…」；WARN → RUN_FINISHED 放行且日志「WARN 模式，放行」；清空额度→不限；审计看板显示已用/额度。 |

## 检查

_没有记录 Runtime 检查。_

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- backend-verify: passed — mvn verify：105 通过 0 失败 0 错误
- frontend-checks: passed — eslint 0 error / vitest 70 通过 / tsc -b 通过 / vite build 成功
- playwright-e2e: passed — 全量 23 通过 3 跳过；新增 phase18-context(2)、phase19-quota(1)、phase20-background-notice(1)
- db-migrations: passed — V9 多模态 / V10 agent_run / V11 额度 均已在 agent_platform 成功应用
- 已知限制: 不做跨进程续跑/分布式队列：后端重启后 run 状态可见（INTERRUPTED），但不会自动接着跑（spec 明确排除，已登记 backlog）
- 已知限制: 配额只在发起前检查，不做事后强制中断（spec 明确排除）
- 已知限制: 上下文用量为官方 TokenCounterUtil 估算值，非厂商计费口径
- 已知限制: 配额不做汇率换算，货币沿用模型价格配置（Phase 15 口径）

## 附：独立浏览器验收通道（Playwright 驱动真实 UI）

除上面的接口/命令复跑外，另用**另一条通道**核验：Playwright 1.64 驱动无头 Chromium 打开真实前端
（`http://localhost:5173`）操作真实 UI，只用**界面上看得见的文本**判定，不调后端接口、不复用构造阶段的 e2e 文件。
脚本：`frontend/e2e/verifier-tier1.spec.ts`；截图：`docs/acceptance/verifier-*.png`。

结果：**7/7 通过**（34.0s）：

| 用例 | 覆盖 | 界面上实际观察到的证据 |
| --- | --- | --- |
| 模型页多模态开关 | A1/A2 | 编辑弹窗「支持多模态」开关存在；勾选保存后列表显示「多模态 开」，再关掉复原为「多模态 关」 |
| 上下文用量条 | A9/A10 | `上下文 39 / 触发阈值 120,000 tokens（0%）` |
| 压缩/清空/上一轮 | A11/A12/A13/A14/A16 | 点压缩有反馈；点清空得「已清空上下文（聊天记录保留），模型将从头开始」且用量条变「上下文 0」；上一轮提示「上一轮：已完成 · stub-ok …」 |
| 审计看板额度 | A19/A20 | `今日已用 0.916562 CNY / 日额度 不限 本月已用 14.449296 CNY / 月额度 不限`；设 0.000001 后出现「已超出额度：你今日已用 0.916562 CNY，日额度 0.000001，已超出额度上限。」 |
| 聊天页额度告警 | A20 | 「已超出支出额度：你今日已用 0.916562 CNY，日额度 0.000001，已超出额度上限。（仅告警，仍可发起）」 |
| 后台完成站内提示 | A17/A18 | 切走后原会话跑完，出现「会话「…」已完成」+「跳到该会话」 |
| 收尾 | — | 额度复原为「不限」，不留痕 |

过程中出现的两次红灯均为**环境问题**，已定位并排除，不是产品缺陷：
1. 首次运行「模型页开关」失败——当时前端 dev server 与后端进程已退出（`frontend=000 / backend=000`）；
   恢复服务后同一脚本 `PUT /api/models/34` 实测返回 `200 {… "multimodal":true …}`、列表显示「多模态 开」，通过。
2. 模型列表在「模型」页签内（默认页签是「供应商」），首次脚本未切页签——已在脚本中修正并复跑通过。

说明：这条通道同样是**在本会话内**执行的，属于「换通道复跑」而非「另起独立验收员」；是否接受仍由用户决定。

## 阻塞项

- **user**: The generic Skill bridge cannot prove an independent Verifier execution; user confirmation is required before Archive. — next: `await-user`

## 风险与跳过的工作

- 超大工具结果集中在最近窗口时，一键压缩可能回报「框架判断当前无需精简」（框架保护最近消息），已在 UI 如实反馈，未强制截断。
- 配额成本依赖用户如实填写模型单价；未填价格时成本按 0 计，额度不触发。
- 浏览器通知（Notification API）未实现，仅站内提示（spec 标为可选）。

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 1 | 1 | pass | — | 独立复跑通过：后端 mvn verify 105/105、BUILD SUCCESS；前端 eslint 0 error / vitest 70/70 / tsc / build 全绿；Playwright 23 通过 3 跳过（含本 change 新增 4 条）。线上逐条复跑取证：图片确随请求下发（桩 dump 出现 image_url+base64）/未开多模态时剥离并给中文提示/图片三类限制均 400；上下文用量可见且准确（0→51 tokens）、压缩实测 257,971→6,769 tokens、清空后 agent_state 无该会话行而聊天记录保留且随后对话正常；run 状态落库（FINISHED+摘要），跑到一半重启后归置为 INTERRUPTED 且 UI 显示「上一轮：已中断（服务重启，这一轮未跑完）」；额度 BLOCK 拒绝并给出已用/额度、WARN 放行、清空额度恢复不拦，审计看板显示已用/额度。20 项验收全部通过，无 failed/blocked。 | 2026-10-13T02:02:33.309Z |



## 结论

独立复跑通过：后端 mvn verify 105/105、BUILD SUCCESS；前端 eslint 0 error / vitest 70/70 / tsc / build 全绿；Playwright 23 通过 3 跳过（含本 change 新增 4 条）。线上逐条复跑取证：图片确随请求下发（桩 dump 出现 image_url+base64）/未开多模态时剥离并给中文提示/图片三类限制均 400；上下文用量可见且准确（0→51 tokens）、压缩实测 257,971→6,769 tokens、清空后 agent_state 无该会话行而聊天记录保留且随后对话正常；run 状态落库（FINISHED+摘要），跑到一半重启后归置为 INTERRUPTED 且 UI 显示「上一轮：已中断（服务重启，这一轮未跑完）」；额度 BLOCK 拒绝并给出已用/额度、WARN 放行、清空额度恢复不拦，审计看板显示已用/额度。20 项验收全部通过，无 failed/blocked。
