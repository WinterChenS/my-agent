# agent-configuration

## Purpose

定义 Agent：系统提示词、模型、采样参数、权限模式，以及 Skill 与 MCP Server 绑定；为 `AgentFactory` 提供输入实体树。

## 数据模型

- `agent(id, name UNIQUE, agent_id UNIQUE, sys_prompt, model_id FK→llm_model, temperature NUMERIC(3,2), max_tokens INT, permission_mode, max_iterations INT DEFAULT 20, status, timestamps)`
  - `permission_mode ∈ {ALWAYS_ALLOW, ALWAYS_DENY, REQUIRE_APPROVAL}`
  - `status ∈ {ENABLED, DISABLED}`
- `agent_skill(agent_id, skill_id)` 主键为二元组
- `agent_mcp_server(agent_id, mcp_server_id)` 主键为二元组

## API 行为

| 方法 | 路径 | 行为 |
|---|---|---|
| GET | `/api/agents` | 分页列表 |
| POST | `/api/agents` | 新建，请求体含 `skillIds[]`、`mcpServerIds[]` |
| GET | `/api/agents/{id}` | 详情，返回含 `skillIds`、`mcpServerIds` |
| PUT | `/api/agents/{id}` | 更新，绑定集合整体替换 |
| DELETE | `/api/agents/{id}` | 删除（含绑定清理） |

- 重复 `agent_id` 或 `name` 返回业务错误。
- `permission_mode`、`temperature`、`max_tokens`、`max_iterations` 做取值校验，非法值返回校验错误。
- 绑定写入必须原子：`skillIds` / `mcpServerIds` 中存在不存在的 id 时整体失败，不产生半更新。
- 引用的 `model_id` 必须存在，且推荐为启用状态。
- 写入接口仅 `ADMIN`；读取需已鉴权。

## AgentFactory 契约

- `HarnessAgent build(AgentEntity agentConfig)`：按实体树构建 AgentScope Agent（系统提示词、模型、采样参数、启用状态的 Skill、绑定 MCP 工具、权限模式）。
- `void invalidate(Long agentId)`：配置变更后使缓存失效，新会话生效。

## 边界

- `agentconfig` 包不得 `import io.agentscope`（门禁 G2）；实体到 AgentScope Agent 的翻译只发生在 `agui/` 包。

## 验收标准

- CRUD 与绑定增删 curl 通过；重复 `agent_id` 返回业务错误。
- `grep -r "io.agentscope" backend/agent-server/src/main/java/com/myagent/agentconfig/` 无输出（G2）。
- `GET /api/agents/{id}` 回读的 `skillIds`/`mcpServerIds` 与写入一致。
