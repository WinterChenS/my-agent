# llm-configuration

## Purpose

管理大模型供应商与模型定义，并提供连通性测试，为 Agent 提供可用模型。

## 数据模型

- `llm_provider(id, name UNIQUE, provider_type, base_url, api_key_cipher, status, timestamps)`
  - `provider_type ∈ {OPENAI, OPENAI_COMPATIBLE, DASHSCOPE, ANTHROPIC, GEMINI, OLLAMA}`
  - `status ∈ {ENABLED, DISABLED}`
- `llm_model(id, provider_id FK→llm_provider, model_id, display_name, params_json jsonb DEFAULT '{}', status, timestamps)`

## API 行为

| 方法 | 路径 | 行为 |
|---|---|---|
| GET | `/api/model-providers` | 分页列表 |
| POST | `/api/model-providers` | 新建，`api_key` 加密入库 |
| PUT | `/api/model-providers/{id}` | 更新；未传 `api_key` 时保留旧密钥 |
| DELETE | `/api/model-providers/{id}` | 删除；存在关联模型时返回业务错误 |
| POST | `/api/model-providers/{id}/test` | 发起一次最小补全，返回 `{ok, latencyMs, error?}` |
| GET/POST | `/api/models` | 列表 / 新建 |
| GET/PUT/DELETE | `/api/models/{id}` | 详情 / 更新 / 删除 |

- 所有响应遵循 `Result` / `PageResult` 契约。
- 读取响应中 `api_key` 一律为 `****`，任何位置不得出现明文密钥，日志同样不得输出密钥。
- `name` 唯一冲突返回业务错误。
- 删除被 `agent.model_id` 引用的模型返回业务错误。
- 连通测试使用对应 AgentScope 官方模型扩展；失败（无效密钥 / 不可达）返回 `ok:false` 与错误原因，HTTP 状态不得为 500。
- 连通测试调用不计入 Token 审计。
- 写入接口仅 `ADMIN`；读取需已鉴权。

## AgentScope 集成

- provider 解析为可用模型实例：`OPENAI` / `OPENAI_COMPATIBLE` / `GEMINI` / `OLLAMA` 走 OpenAI 兼容扩展，`DASHSCOPE` 走 dashscope 扩展，`ANTHROPIC` 走官方 anthropic 扩展（以实际可解析的官方扩展为准）。
- 禁用的 provider 或 model 不得用于构建 Agent。

## 边界

- `modelconfig` 包不得 `import io.agentscope`（门禁 G2）；AgentScope 模型 API 仅允许出现在 `agui/`、`audit/`、`hitl/` 包。

## 验收标准

- 全 CRUD 与分页 curl 通过。
- 更新不传 `api_key` 时旧密钥保留（更新后 test 仍通过）。
- 列表响应无明文 `api_key`。
- 无效 key 的 test 返回 `{ok:false,error:...}` 而非 500。
- 单测覆盖 mapper CRUD（连接专用测试库）；`mvn verify` 全绿。
