# token-audit

## Purpose

按用户维度采集与统计 Token 消耗，并提供受权限约束的查询接口与前端看板。

## 采集

- `TokenUsageListener` 从模型响应事件中提取 `prompt_tokens` / `completion_tokens`。
- 以一次 `/agui/run` 的 `request_id` 为单位归属；同一 run 内多轮工具循环可产生多行，`request_id` 相同。
- usage 缺失时不写行、不报错。
- 连通性测试等非对话调用不计入。

## 数据模型

- `token_usage(id, user_id, session_id, agent_id, model_id, provider_type, prompt_tokens, completion_tokens, total_tokens, request_id VARCHAR(64), created_at)`

## API 行为

| 方法 | 路径 | 行为 |
|---|---|---|
| GET | `/api/audit/token/summary?userId&from&to` | `{userId, promptTokens, completionTokens, totalTokens, byModel[], byDay[]}` |
| GET | `/api/audit/token/records?...` | 明细分页 |
| GET | `/api/audit/token/users` | 有消耗的用户列表 |

- 聚合通过 SQL `GROUP BY` 完成，不在应用层实时计算。
- 权限：`ADMIN` 可查询全员；`USER` 仅可查询本人，查询他人返回拒绝（403 / 业务错误）。

## 前端

- 审计页：用户汇总表、按日趋势、模型分布、日期范围与用户筛选。
- 图表实现不新增超过 1 个图表库。

## 验收标准

- 完成 3 次对话后 summary 返回非零 tokens，且 `byModel` / `byDay` 结构正确。
- USER 查他人被拒、ADMIN 查全员成功（附 curl 证据）。
- 数字一致性抽查：`sum(total_tokens)` 与对话量级相符。
- 单测：usage 缺失时不写行、不报错。
- 前端页面展示数据正确、筛选生效，`npm run lint && npm run build && npx vitest run` 全绿。
