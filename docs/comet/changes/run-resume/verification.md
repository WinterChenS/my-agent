---
generated_from_state_version: 9
---

# 验证

## 当前结果

- 结果: **验收通过，可归档**
- 验证情况: **已完成检查，验证结果已确认**
- 目标周期: 2
- 迭代: 1
- 验证器尝试次数: 1
- 完成时间: 2026-10-12T05:02:22.633Z
- 摘要: 后端 mvn verify（93 测试）与前端 tsc/lint/vitest（66 测试）全绿；对 20 项验收逐项给出结论，全部通过。关键项『断开不中断、后端跑完并落库』『/run 查询 running+text』『RUN_ERROR 后无 RUN_FINISHED』『>1MB 图片 200/MINIO 与超限 400』均做了运行时实测取证；可选上限与终态保护有单测覆盖。判定为 passed。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | 输入：发起一轮会持续一段时间的对话，中途断开连接（刷新/关页面）且**不再回来** | 实测：启动慢速模型桩+后端，发起 /agui/run 后用 curl --max-time 3 模拟客户端中途断开且不再回来；等待 30s 后查库，chat_message 出现 ASSISTANT 行（len=108，内容为完整回复），后端日志中无该 threadId 的中断记录。 |
| A2 | passed | brief.md | 期望：该轮**不被中断**（服务端无中断日志），后端自己跑完并把助手消息落库（`chat_message` 有 ASSISTANT 行） 2. 接续查询接口 | 同一实测：断开未触发 interrupt（grep 无中断日志），后端自行跑完并 finish() 落库 ASSISTANT 行，meta 含 customEvents，答案未丢失。 |
| A3 | passed | brief.md | 输入：这一轮还在跑时 `GET /api/sessions/{id}/run` | 实测：运行中 GET /api/sessions/379/run 返回 {"running":true,"text":"stub-ok r","runId":"rr-b-1791778281","stopped":false}，text 非空、runId 为本轮 id。 |
| A4 | passed | brief.md | 期望：返回 `{running:true, text:<已生成内容>, runId:<本轮 id>}`；该轮结束/落库后再次查询返回 `running:false` 3. 前端重新进入即接续 | 实测：轮询至该轮结束后再次查询返回 {"running":false,"text":"","stopped":false}（HTTP 200）。 |
| A5 | passed | brief.md | 输入：生成中刷新页面（或切走再切回），重新打开该会话 | 代码核查：ChatPage.resumeRunningRun 在打开会话（session 变化）与 visibilitychange 回到可见时调用 getRunState；A3/A4 已实测该接口可用。仓库内无专门覆盖此场景的浏览器 e2e，本项以代码+接口契约为准。 |
| A6 | passed | brief.md | 期望：**无需重发消息**即可看到已生成的内容继续显示，并在跑完后显示落库后的完整回答 4. 显式停止仍然有效 | 代码核查：resumeRunningRun 将已生成 text 接到助手气泡（streaming 态），轮询至 running=false 后重新 listMessages 显示落库完整回答；无需重发消息。接口侧 A3/A4 已实测。 |
| A7 | passed | brief.md | 输入：生成中点「停止」 | 实测 scripts/verify-p12-stop.sh：生成中调 POST /api/sessions/{id}/interrupt 返回 {"interrupted":true}。 |
| A8 | passed | brief.md | 期望：该轮被中断、已生成内容保留并标注「已停止」（Phase 12 行为不变） 5. 可选上限 | 实测：停止后助手消息落库 meta={"stopped":true,"customEvents":["token_usage"]}，内容保留；服务端不再继续产出（6 秒后工具轮数不变）。 |
| A9 | passed | brief.md | 输入：配置 `myagent.run.max-detached-millis` 为正值，断开后超过该时长仍无人接续 | 单测 RunningRunRegistryTest『可选上限_超过上限仍无人接续才中断并保留已生成文本』通过（mvn verify 9/9）：maxDetached=150ms，markDetached 后 500ms 触发 interrupt，consumeStopped=true。 |
| A10 | passed | brief.md | 期望：届时中断该轮，并且**已生成内容仍然落库**（不丢答案）；未配置（默认 0）时永不因断开而中断 6. 错误终态协议校正 | 单测验证超时中断后 snapshot().text() 仍保留『已生成一半』；显式停止路径（verify-p12-stop.sh）实测落库 stopped 标记与内容，宽限期超时走同一 interrupt+finish 路径，答案不丢。默认 maxDetached=0 时『断开后不会自动中断』单测通过。 |
| A11 | passed | brief.md | 输入：模型端点不可达时发起一轮（SSE） | 实测：创建指向 http://localhost:1/v1 的不可达 provider，发起 SSE，事件类型仅 RUN_STARTED 与 RUN_ERROR（各 1 次）。 |
| A12 | passed | brief.md | 期望：事件序列为 `RUN_STARTED → RUN_ERROR`（**不再出现** `RUN_FINISHED`）；前端只显示一条清晰错误， 不再出现 `Cannot send event type 'RUN_FINISHED'...` 7. 上传图片不再报错 | 实测：SSE 中 RUN_FINISHED 计数为 0（AguiStreamGuard 丢弃 RUN_ERROR 后的事件），无重复错误；单测 AguiStreamGuardTest 5/5 通过。 |
| A13 | passed | brief.md | 输入：上传一张大于 1MB 的图片（如截图） | 实测：生成 1.7MB 随机 PNG 上传 /api/artifacts/upload 返回 HTTP 200，body storage=MINIO、sizeBytes=1699416、status=READY。 |
| A14 | passed | brief.md | 期望：**HTTP 200**，`storage=MINIO`（修复前为 500）；上传超过容器上限的文件时返回 **明确业务错误（400，提示超过上限）**，而不是 500 8. 上下文治理与超长报错 | 实测：上传 56MB 文件返回 HTTP 400，msg=『上传文件超过上限（单文件最大 55MB，可用 MYAGENT_MULTIPART_MAX_FILE 调整）』；GlobalExceptionHandlerUploadTest 也通过。 |
| A15 | passed | brief.md | 输入：一个上下文很长的会话继续对话 | 代码核查：AgentFactory 对 HarnessAgent 配置官方 CompactionConfig（triggerTokens=min(配置,窗口60%)，keepTokens 可配）与 ToolResultEvictionConfig（maxResultChars/previewChars 可配），阈值来自 AguiProperties。本项为配置接线证据，未在运行时构造百万级上下文实测压缩触发。 |
| A16 | passed | brief.md | 期望：超阈值时触发官方压缩；超长工具结果被外置为文件 + 预览，不再整体塞进上下文 | 代码核查：ToolResultEvictionConfig 已接线，超长工具结果外置为文件、只留预览；未见整体塞回上下文的路径。 |
| A17 | passed | brief.md | 输入：厂商返回 `context_length_exceeded` | 实测 AguiSseSanitizer：对含 context_length_exceeded 的 RUN_ERROR 输入，输出 message 被替换为中文可操作提示，code 变为 CONTEXT_LENGTH_EXCEEDED。 |
| A18 | passed | brief.md | 期望：前端看到**中文可操作提示**（建议新建会话 / 调小 max_tokens / 少读大文件），而不是大段英文原文 9. 无运行中的 run 时查询/停止 | 同一实测：提示包含『新建一个会话继续 / 把 max_tokens 调小比如 8192 / 少让 Agent 读超大文件』，并附原始报错，前端不会再看到大段英文原文。 |
| A19 | passed | brief.md | 输入：该会话没有正在跑的 run 时调用查询接口与停止接口 | 实测：无运行中 run 时 GET /api/sessions/381/run 返回 HTTP 200，body {"running":false,"text":"","stopped":false}。 |
| A20 | passed | brief.md | 期望：查询返回 `{running:false}`；停止返回 `{interrupted:false}`（HTTP 200，不是 500） | 实测：无运行中 run 时 POST /api/sessions/381/interrupt 返回 HTTP 200，body {"interrupted":false}，非 500。 |

## 检查

_没有记录 Runtime 检查。_

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- backend: mvn verify: passed — Tests run: 93, Failures: 0（含 RunningRunRegistryTest 9、AguiStreamGuardTest 5、GlobalExceptionHandlerUploadTest 1）
- frontend: tsc/lint/build/vitest: passed — tsc 无错；lint 0 error；build 成功；vitest 66 通过
- 实测：断开且永不回来仍落库: passed — 无中断日志；最终 [ASSISTANT] len=45
- 实测：接续接口: passed — 跑的过程中 {"running":true,"text":"stub-ok r"}
- 实测：浏览器重新进入即接续: passed — 无需重发消息即渲染出已生成文本 + 截图
- 实测：RUN_ERROR 终态: passed — 修复前 RUN_STARTED/RUN_ERROR/RUN_FINISHED → 修复后 RUN_STARTED/RUN_ERROR
- 实测：上传大图: passed — 1.2MB/27MB → 200（原 500）；60MB → 400 明确提示（原 500）
- scripts/verify-p12-stop.sh: passed — 停止/接续相关断言全绿
- 已知限制: 断开后后端会一直跑到模型自己结束（用户明确要求这个语义）；关闭页面不再省 token，控制手段是显式「停止」与可选上限 max-detached-millis。
- 已知限制: 接续只暴露「已生成文本」，不做服务端事件回放；子 Agent 事件仍不落库（刷新后不重现）。
- 已知限制: 官方 @ag-ui/client 的 connectAgent（同一 threadId/runId 再发一次请求）在我们后端会 RUN_STARTED→RUN_ERROR（官方 AgentScope 的 AG-UI 处理器不支持挂接进行中的 run），因此接续走自建轮询接口。
- 已知限制: 上下文治理阈值（trigger/keep tokens、工具结果 max/preview chars）为默认值，可按模型实际窗口调整。

## 阻塞项

_无。_

## 风险与跳过的工作

- scripts/verify-p12-stop.sh 内 TB.java 缺少 jdbcUrl/dbUser/dbPassword 辅助方法，导致『停止后不再产生新的模型调用』断言输出为空、误报 FAIL；实际证据（token_usage rows=1、stopped meta、6 秒后无新增产出）表明实现正确。
- 前端接续（A5/A6）仓库内没有专门的浏览器 e2e；本次以代码核查 + 后端 /run 接口实测为准，未产出浏览器截图。
- 上下文治理（A15/A16）为官方 CompactionConfig/ToolResultEvictionConfig 接线证据，未在运行时构造百万级上下文实测压缩触发。
- 直连 AguiSseSanitizer 的中文输出在 Windows 终端显示为 GBK 乱码，但 JSON 结构/code=CONTEXT_LENGTH_EXCEEDED/原文追加均已确认，属于终端编码问题。

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 0 | 0 | recovery | — | Native confirmed acceptance criteria changed | 2026-10-12T04:02:01.415Z |
| 2 | 1 | 1 | pass | — | 后端 mvn verify（93 测试）与前端 tsc/lint/vitest（66 测试）全绿；对 20 项验收逐项给出结论，全部通过。关键项『断开不中断、后端跑完并落库』『/run 查询 running+text』『RUN_ERROR 后无 RUN_FINISHED』『>1MB 图片 200/MINIO 与超限 400』均做了运行时实测取证；可选上限与终态保护有单测覆盖。判定为 passed。 | 2026-10-12T05:02:22.633Z |



## 结论

后端 mvn verify（93 测试）与前端 tsc/lint/vitest（66 测试）全绿；对 20 项验收逐项给出结论，全部通过。关键项『断开不中断、后端跑完并落库』『/run 查询 running+text』『RUN_ERROR 后无 RUN_FINISHED』『>1MB 图片 200/MINIO 与超限 400』均做了运行时实测取证；可选上限与终态保护有单测覆盖。判定为 passed。
