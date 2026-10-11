# mcp-integration

## Purpose

注册 MCP Server（三种传输），同步并缓存其工具清单，使绑定该 Server 的 Agent 能在对话中调用其工具。

## 数据模型

- `mcp_server(id, name UNIQUE, transport, endpoint_url, command, args_json jsonb, env_json jsonb, headers_json jsonb, tool_cache jsonb, last_synced_at, status, timestamps)`
  - `transport ∈ {STDIO, SSE, STREAMABLE_HTTP}`
  - `status ∈ {ENABLED, DISABLED}`

## API 行为

| 方法 | 路径 | 行为 |
|---|---|---|
| GET | `/api/mcp-servers` | 分页列表（含 `tool_cache` 摘要与 `last_synced_at`） |
| POST | `/api/mcp-servers` | 新建 |
| GET/PUT/DELETE | `/api/mcp-servers/{id}` | 详情 / 更新 / 删除 |
| POST | `/api/mcp-servers/{id}/sync-tools` | 连接并拉取工具清单，写入 `tool_cache` 与 `last_synced_at`，返回工具数组 |

- 校验：`STDIO` 必须提供 `command`；`SSE` / `STREAMABLE_HTTP` 必须提供 `endpoint_url`。
- `sync-tools` 返回元素包含工具名、描述与输入 schema。
- 连接失败或地址错误：返回带原因的**业务错误**，HTTP 不得为 500，且不得影响其他功能。
- 名称唯一冲突返回业务错误。
- 写入接口仅 `ADMIN`；读取与同步需已鉴权（同步写操作仅 `ADMIN`）。

## AgentScope 集成

- 连接与工具发现使用官方 `McpClientManager`；对话中的工具执行同样经官方扩展（门禁 G8）。
- 工具在 Agent 侧按 Server 命名空间暴露，避免同名冲突：Agent 看到的工具名为 **`<serverName>__<toolName>`**（例：`everything__echo`）。
  - 实现方式（门禁 G8：协议实现全部用官方件）：官方 `McpClientBuilder` 建客户端 + 官方 `Toolkit.registerMcpClient` 注册，只在外面套一层 `NamespacedMcpClientWrapper` 负责 `listTools()` 加前缀、`callTool()` 去前缀（`McpTool.callAsync` 传的是注册名，所以必须剥）；MCP 协议本身不自研。
  - 管理台「工具清单」`tool_cache` 仍存 Server 提供的**原始**工具名（带 Server 列，便于管理员对照），Agent 侧才用带命名空间的名字。
- 严禁自研 MCP JSON-RPC 解析或自研连接管理。

## 边界

- `mcpconfig` 包不得 `import io.agentscope`（门禁 G2）。

## 验收标准

- 对真实测试 MCP Server 执行 `sync-tools` 成功并返回非空工具列表（附 JSON）。
- 错误地址 sync 返回业务错误与原因。
- 绑定 Agent 后对话触发该工具时出现 `TOOL_CALL_*` 事件，且工具名带命名空间。
- `grep -r "jsonrpc\|JSON-RPC" backend/agent-server/src/main/java` 无自研协议解析（G8）。
- 前端 MCP 管理页支持列表 / 新建 / 编辑 / 同步工具 / 查看工具清单。
