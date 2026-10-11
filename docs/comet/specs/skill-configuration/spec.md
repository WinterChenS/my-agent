# skill-configuration

## Purpose

管理可供 Agent 复用的 Skill（Markdown 指令内容），并通过 AgentScope 官方 Skill 仓库在运行时注入 Agent。

## 数据模型

- `skill(id, name UNIQUE, description, content TEXT, version INT DEFAULT 1, status, timestamps)`
  - `content` 为 Markdown 文本
  - `status ∈ {ENABLED, DISABLED}`

## API 行为

| 方法 | 路径 | 行为 |
|---|---|---|
| GET | `/api/skills` | 分页列表 |
| POST | `/api/skills` | 新建，`version` 初始为 1 |
| GET | `/api/skills/{id}` | 详情 |
| PUT | `/api/skills/{id}` | 更新；`content` 变化时 `version` 自增 |
| DELETE | `/api/skills/{id}` | 删除；被 Agent 绑定时返回业务错误 |

- 名称唯一冲突返回业务错误。
- 写入接口仅 `ADMIN`；读取需已鉴权。

## AgentScope 集成

- Skill 存储与加载使用 AgentScope 官方 skill 扩展的 PostgreSQL repository；不得自研加载逻辑（门禁 G8）。
- Agent 构建时只加载 `status=ENABLED` 且被该 Agent 绑定的 Skill。

## 边界

- `skillconfig` 包不得 `import io.agentscope`（门禁 G2）。

## 验收标准

- CRUD curl 通过。
- 将 Skill 绑定到 Agent 并对话时，模型可感知 Skill 内容（例如 Skill 要求"回复末尾加【OK】"时回复带该标记）。
- `skill` 表存在数据行，且通过官方 repository 加载（G8）。
