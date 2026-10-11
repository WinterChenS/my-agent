# chat-file-upload

## Purpose

在对话输入区上传文件：文件落到 MinIO（文本类落库），登记为 `artifact(role=SOURCE)`，并让 Agent 能够获取并处理该文件。

## 交互

- 输入框左侧提供「上传文件」入口（按钮 + 拖拽入输入区均可）。
- 上传中显示进度/占位；失败时给出明确原因且**不清空已输入文本**。
- 上传成功后：文件出现在右侧栏「来源文件」区；同时作为一条待发送的附件显示在输入区（可移除）。
- 发送消息时，附件与文本一起提交（附件已上传，消息只携带 artifact id）。

## 服务端行为（D20）

1. 接收上传（`POST /api/artifacts/upload`，`multipart/form-data`，字段：`sessionId`、`file`）。
2. 计算 `sha256`；按 D18 路由：
   - 文本类 → 正文写 `artifact.content`（`storage=DB`）；
   - 其它 → 写 MinIO（`storage=MINIO`）。
3. 写 `artifact(role=SOURCE, status=READY)`；同会话内相同 `sha256` 直接返回既有记录（不重复上传）。
4. **物化到工作区**：把文件写到 Agent 实际工作目录下的 `uploads/<fileName>`（实测路径为 `.myagent-workspace/<userId>/uploads/`；写成 `<threadId>` 目录 Agent 用相对路径读不到），使 Agent 用常规文件/命令工具即可读取处理。
5. 返回 `{artifact, downloadUrl}`。

约束：
- 单文件大小上限可配置（默认 50MB），超限返回业务错误。
- 文件名做安全处理（去除路径分隔符与 `..`），避免路径穿越。
- 上传接口需 JWT；`sessionId` 必须属于当前用户，否则 403。

## Agent 侧可见性

- 物化路径写在该会话工作区内，Agent 可直接读取（不需要额外自定义工具，满足门禁 G8 精神）。
- 该会话的「来源文件」不自动进入提示词；由 Agent 按需读取（避免无关文件污染上下文）。
- 产物登记规则（自动全收 + 排除框架内部状态）见 `artifact-storage` 的 D22。

## 验收标准

- 上传 `.md`/`.txt` → `artifact` 行 `role=SOURCE`、`storage=DB`、`content` 有值；右侧栏可见；文件存在于工作区 `uploads/` 下。
- 上传 `.png`/`.zip` → `storage=MINIO`、bucket 中存在该对象；右侧栏可下载。
- Agent 在该会话内被要求「读取我上传的文件」时，能读到物化后的文件内容并据此回答（用桩/真实模型均可复现）。
- 超限文件、非属主 sessionId、MinIO 不可用三种情况都返回明确业务错误，页面不白屏、输入不丢。
