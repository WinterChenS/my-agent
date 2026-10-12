# run-lifecycle

## Purpose

定义**一轮对话（run）的生命周期归属**：由后端负责跑完，客户端只是观众；断开不影响运行，重新进入即接续；
只有显式停止或（可选的）断开上限才中断；错误时遵守 AG-UI 的终态约束。

## 背景（实测）

- 旧实现里客户端断开（刷新/切页也算）会 `subscription.dispose()` + 中断，实测**这一轮的助手消息根本不落库**
  （负责落库的 `finish()` 不执行）——用户看到的表现就是"刷新一下，对话没接上（其实是答案丢了）"。
- 官方扩展在错误时会先发 `RUN_ERROR`、紧接着又发 `RUN_FINISHED`；官方客户端规定"出错后不能再有事件"，
  于是抛出 `Cannot send event type 'RUN_FINISHED': The run has already errored with 'RUN_ERROR'. No further events can be sent.`
- 官方 `@ag-ui/client` 的 `detachActiveRun()` / `connectAgent()` 设计意图正是"离开不中断 / 再接上"，
  但其"再接上"本质是用同一 threadId/runId 再发一次 `POST /agui/run`；实测我们后端会返回 `RUN_STARTED → RUN_ERROR`
  （官方 AgentScope 的 AG-UI 处理器不支持挂接进行中的 run）。

## 设计

### 后端

- **运行登记表**（`RunningRunRegistry`）按 threadId 保存：Agent 实例、RuntimeContext、runId、
  **已生成的助手文本**、是否被显式停止、是否已收尾、以及"最后一次断开时间"。
- **断开不中断**：SSE 的 `onCompletion/onError/onTimeout` 只标记"已断开"，不 `dispose()`、不中断；
  这一轮继续跑，结束时 `finish()` 正常落库。
- **可选上限**：`myagent.run.max-detached-millis`（默认 `0` = 不限制）。设为正值时，断开超过该时长且
  期间无人接续才中断；中断后仍会落库（答案不丢）。
- **查询接口**：`GET /api/sessions/{id}/run`（JWT + 属主校验）返回
  `{running, text, runId, stopped}`；调用即视为"客户端回来了"（取消断开计时）。
- **显式停止**：`POST /api/sessions/{id}/interrupt` 语义不变（走官方 `Agent.interrupt(...)`）。
- **终态校正**（`AguiStreamGuard`）：本轮一旦发出 `RUN_ERROR` 即进入终态，之后的事件一律不下发；
  官方已发过错误时不再重复发兜底错误。

### 前端

- 打开会话后、以及 `visibilitychange` 回到可见时，调用查询接口：
  - `running=true` → 把 `text` 接到助手气泡上继续显示（流式态），每 ~1.2s 轮询一次，直到 `running=false`，
    随后重新拉取消息，显示落库后的完整内容（含「已停止」标记）。
  - `running=false` → 不做任何事（按正常历史回放）。
- 正常的发送/停止流程不变（仍走官方 `runAgent` 与显式停止按钮）。

### 上传上限（同批修复）

- 容器层 `spring.servlet.multipart.max-file-size/max-request-size` 必须 **>= 业务上限**
  （`myagent.minio.max-file-size-mb`，默认 50MB），默认取 55MB/60MB；否则大文件在进入业务代码前
  就被容器拒掉（实测 1.2MB 图片即 500）。
- `MaxUploadSizeExceededException` 由全局异常处理翻译为 **400 + 明确文案**，不是 500。

## 边界

- 只暴露"已生成文本"，不做服务端事件回放（历史事件不落库）。
- 不改变 HITL 审批语义：等待审批的 run 本来就停着。
- 查询接口只返回当前用户自己会话的状态（非属主 403）。

## 验收标准

- 断开（刷新/关页面）后该轮**不被中断**，后端自己跑完并把助手消息落库（附日志与 SQL）。
- 运行中查询接口返回 `running=true` 与已生成文本；结束后返回 `running=false`。
- 前端重新进入会话即接续显示已生成内容，跑完后显示完整回答（**无需重发消息**，附截图）。
- 显式「停止」仍然中断该轮并保留已生成内容（Phase 12 语义不变）。
- 配置 `max-detached-millis` 为正时：超时且无人接续才中断，且已生成内容仍落库；默认 `0` 时不因断开而中断。
- 模型端点不可达时 SSE 只发 `RUN_STARTED → RUN_ERROR`，前端只显示一条清晰错误。
- 无运行中的 run 时：查询返回 `{running:false}`、停止返回 `{interrupted:false}`（均为 200）。
