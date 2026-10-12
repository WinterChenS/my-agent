---
generated_from_state_version: 8
---

# 验证

## 当前结果

- 结果: **已归档**
- 验证情况: **已完成检查，验证结果已确认**
- 目标周期: 1
- 迭代: 1
- 验证器尝试次数: 1
- 完成时间: 2026-10-12T05:10:54.628Z
- 摘要: 独立复跑通过：后端 mvn verify 93/93、前端 tsc/lint/vitest 66/66 全绿；起桩+后端实测七项参数创建与读回、五类非法值均 400 且不写入、输出>上下文返回精确 400 文案、桩捕获请求体确实带 temperature=0.35/top_p=0.85/max_tokens=2048、审计成本精确算出 4.65e-05 CNY 且 byModel 带单价与成本；Playwright 实测模型页 7 输入项与『采样/长度』『价格（每百万 token）』两列、审计看板『单价（入/出）』『成本』两列均可见。16 项验收全部通过，未见 failed/blocked 项。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | 输入：新建模型时给出温度 0.35、Top-P 0.85、上下文 128000、输出 2048、输入价 1.5、输出价 6、货币 CNY | 实测 POST /api/models 携带 providerId+modelId+temperature=0.35/topP=0.85/contextWindow=128000/maxTokens=2048/priceInput=1.5/priceOutput=6/priceCurrency=CNY 创建成功，返回 code=0 与新模型 id=446。 |
| A2 | passed | brief.md | 期望：创建成功，且查询该模型时这七项原值返回 2. 取值范围校验 | 实测 GET /api/models/446 原值读回：temperature=0.350(即0.35)、topP=0.850(即0.85)、contextWindow=128000、maxTokens=2048、priceInput=1.500000(即1.5)、priceOutput=6.000000(即6)、priceCurrency=CNY。NUMERIC 列尾随零属数据库精度序列化，数值与原值一致。 |
| A3 | passed | brief.md | 输入：温度 3 / Top-P 0 / 上下文 0 / 价格为负 / 货币 EUR | 对同一 provider 分别提交 temperature=3、topP=0、contextWindow=0、priceInput=-1、priceCurrency=EUR，均被服务层 applyParams 拦截。 |
| A4 | passed | brief.md | 期望：各自返回明确的业务错误（400），不写入 3. 输出长度不得超过上下文长度 | 五种非法值均返回 HTTP 400 且带明确业务错误：『温度必须在 0–2 之间』『Top-P 必须在 (0, 1] 之间』『上下文长度必须大于 0』『价格不能为负数』『价格货币只支持 CNY 或 USD』；随后 list 模型确认没有任何 mp-invalid-* 记录写入（校验在 insert 之前抛 BizException）。 |
| A5 | passed | brief.md | 输入：上下文 1000 + 输出 5000 | 实测提交 contextWindow=1000 + maxTokens=5000 的组合请求。 |
| A6 | passed | brief.md | 期望：返回 400，提示「输出长度（5000）不能大于上下文长度（1000）」 4. 温度 / Top-N / 输出长度随请求下发（真正生效） | 返回 HTTP 400，msg 精确为『输出长度（5000）不能大于上下文长度（1000）』，与验收文案一致。 |
| A7 | passed | brief.md | 输入：用上述模型发起一轮对话 | 创建绑定该模型的 Agent（modelId=446）后，实测 POST /agui/run 发起一轮对话，返回 RUN_STARTED 与流式 TEXT_MESSAGE_CONTENT，对话链路正常。 |
| A8 | passed | brief.md | 期望：实际发给模型的请求体里带上 `temperature=0.35`、`top_p=0.85`、`max_tokens=2048` 5. 上下文长度驱动上下文治理 | 桩 STUB_DUMP 捕获的实际请求体里确实出现 temperature=0.35、top_p=0.85、max_tokens=2048（dump 中 temperature 与 top_p 各出现 2 次、max_tokens 出现 2 次，均随请求下发）。 |
| A9 | passed | brief.md | 输入：模型配置上下文长度（如 8000） | 代码审查：AgentFactory.doBuild 将 model.getContextWindow() 传入 compactionTriggerFor()，并设置为官方 CompactionConfig.triggerTokens；模型配置的上下文长度确为压缩阈值输入。 |
| A10 | passed | brief.md | 期望：历史压缩触发阈值取 `min(配置值, 窗口×60%)`（即 4800），窗口小的模型不会被压爆 6. 价格驱动审计成本（真正生效） | compactionTriggerFor(8000) = max(1000, min(默认120000, 8000*0.6=4800)) = 4800，满足 min(配置值, 窗口×60%)（示例即 4800）；确定性纯函数，逻辑与验收一致。注意：额外存在 1000 下限，仅对窗口极小(<~1667)的模型生效，不影响本验收示例。 |
| A11 | passed | brief.md | 输入：一次用量为 11 输入 tokens + 5 输出 tokens，模型价格 1.5 / 6（每百万 CNY） | 桩返回单次 usage=11 input/5 output/16 total，模型价格 1.5/6（每百万 CNY）；审计记录表 token_usage 中该模型行 prompt_tokens=11、completion_tokens=5。 |
| A12 | passed | brief.md | 期望：审计 summary 的 `costTotal = 4.65e-05`、`costCurrency = CNY`；`byModel` 中该模型带单价与成本 7. 前端可维护 | 用时间窗隔离后实测 GET /api/audit/token/summary 返回 costTotal=4.65e-05、costCurrency=CNY；byModel 中 model_id=446 带 price_input=1.5、price_output=6.0、currency=CNY、cost_total=4.65e-05（手算 11/1e6*1.5 + 5/1e6*6 = 1.65e-5 + 3.0e-5 = 4.65e-5 完全吻合）。 |
| A13 | passed | brief.md | 输入：管理台 → 模型页 → 编辑模型 | Playwright 实测打开管理台模型页并打开编辑弹窗，DOM 中出现 7 个输入项：温度 temperature、Top-N（Top-P）、上下文长度（tokens）、输出长度（tokens）、输入价格/百万 token、输出价格/百万 token、价格货币(CNY/USD)。 |
| A14 | passed | brief.md | 期望：温度 / Top-N / 上下文长度 / 输出长度 / 输入价格 / 输出价格 / 价格货币 七个输入项可见可改并保存成功； 列表显示「采样 / 长度」与「价格（每百万 token）」两列 8. 审计看板显示成本 | Playwright 实测模型列表表头为『采样 / 长度』与『价格（每百万 token）』两列（getByText 各命中 1 次）；编辑弹窗 7 项回填并作为显式字段提交（前端 saveModel 有『输出长度不能大于上下文长度』校验），后端 create/update 已实测保存成功。 |
| A15 | passed | brief.md | 输入：打开审计看板 | Playwright 实测成功导航到 /admin/audit 审计看板并渲染出『按模型分布』表格。 |
| A16 | passed | brief.md | 期望：按模型分布里显示「单价（入/出）」与「成本」（带货币） | Playwright 实测审计页表头含『单价（入/出）』与『成本』两列；接口 byModel 每行返回 price_input/price_output/currency/cost_total，前端成本列渲染为 `Number(cost_total).toFixed(6) + 货币`（带货币）。 |

## 检查

_没有记录 Runtime 检查。_

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- backend: mvn verify: passed — Tests run: 93, Failures: 0（V7 已在测试库应用）
- frontend: tsc/lint/build/vitest: passed — tsc 无错；lint 0 error；build 成功；vitest 66 通过
- 实测：参数随请求下发: passed — 桩 dump 请求体：temperature=0.35 / top_p=0.85 / max_tokens=2048
- 实测：输出≤上下文校验: passed — contextWindow=1000 + maxTokens=5000 → 400 明确提示
- 实测：审计成本: passed — 16 tokens(11入/5出) × 1.5/6 → costTotal=4.65e-05 CNY（数值精确吻合）
- 实测：模型页表单与列: passed — Playwright 通过 + 截图 task-15-model-params.png
- 已知限制: 计价只支持「输入价 + 输出价（每百万 token）+ 货币」，不做阶梯价/折扣/汇率换算；不同货币分别汇总（混合标 MIXED）。
- 已知限制: 上下文长度不改变 token 统计口径，只用于校验与压缩阈值。
- 已知限制: 价格需用户按真实单价填写才会准确（示例值仅供参考）。

## 阻塞项

_无。_

## 风险与跳过的工作

- compactionTriggerFor 增加了 Math.max(1000, ...) 下限，与 D4『min(配置值, 窗口×60%)』公式在窗口极小(<~1667 token)时不完全一致；本验收示例(8000→4800)不受影响。
- 新增参数校验（范围、输出≤上下文、货币、价格）与压缩阈值公式没有对应单元测试：ModelConfigServiceTest 仍是旧 CRUD 用例，AgentFactoryTest 无 compactionTriggerFor 用例；当前仅靠服务层代码+实测接口覆盖。
- temperature/topP/priceInput 等 NUMERIC 列因列精度(4,3)/(14,6)序列化带尾随零（0.35→0.350、1.5→1.500000），数值与原值相等，但严格字符串比较会有差异。
- 审计 summary 的 costTotal 是时间窗内所有模型成本之和；验收示例的 4.65e-05 需用时间窗隔离到单次用量才能精确复现（默认 30 天窗口会混入历史数据）。

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 1 | 1 | pass | — | 独立复跑通过：后端 mvn verify 93/93、前端 tsc/lint/vitest 66/66 全绿；起桩+后端实测七项参数创建与读回、五类非法值均 400 且不写入、输出>上下文返回精确 400 文案、桩捕获请求体确实带 temperature=0.35/top_p=0.85/max_tokens=2048、审计成本精确算出 4.65e-05 CNY 且 byModel 带单价与成本；Playwright 实测模型页 7 输入项与『采样/长度』『价格（每百万 token）』两列、审计看板『单价（入/出）』『成本』两列均可见。16 项验收全部通过，未见 failed/blocked 项。 | 2026-10-12T05:10:54.628Z |



## 结论

独立复跑通过：后端 mvn verify 93/93、前端 tsc/lint/vitest 66/66 全绿；起桩+后端实测七项参数创建与读回、五类非法值均 400 且不写入、输出>上下文返回精确 400 文案、桩捕获请求体确实带 temperature=0.35/top_p=0.85/max_tokens=2048、审计成本精确算出 4.65e-05 CNY 且 byModel 带单价与成本；Playwright 实测模型页 7 输入项与『采样/长度』『价格（每百万 token）』两列、审计看板『单价（入/出）』『成本』两列均可见。16 项验收全部通过，未见 failed/blocked 项。
