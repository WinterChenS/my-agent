"""生成独立 Verifier 的提示词（一次性脚本，用完即删）。"""
import re

state = open("docs/comet/changes/agent-platform/comet-state.yaml", encoding="utf-8").read()
items = re.findall(r"- id: (A\d+)\n    source: [^\n]*\n    text: (.*)", state)
assert len(items) == 40, len(items)
scope = "\n".join(f"- {aid}: {text.strip()}" for aid, text in items)

prompt = f"""你是 **独立的只读验收员（Verifier）**。你要独立核查一份已经实现完的交付，不能相信任何人的自述，必须自己去看代码、跑检查、看结果。

## 待验收对象
- 工作区（实现所在）：D:\\project\\my-agent
- change 目录：D:\\project\\my-agent\\docs\\comet\\changes\\agent-platform
  - 需求与验收口径：brief.md
  - 能力规格：specs/ 下 10 份 spec.md（platform-foundation / llm-configuration / agent-configuration / agui-chat / skill-configuration / mcp-integration / token-audit / artifact-storage / chat-file-upload / chat-skill-picker）
- 技术方案与任务/门禁：docs/技术方案.md、docs/任务列表.md
- 历史证据：docs/acceptance/*.png

## 验收清单（共 40 项，逐项给结论）
{scope}

## 你可以运行的检查（都要在 D:\\project\\my-agent 下跑）
环境：JAVA_HOME 必须是 "C:\\Program Files\\Java\\jdk-21.0.3+9"（PATH 里默认是 Java 8）；
Maven 在 /c/tools/apache-maven-3.9.16/bin；Node 在 "/d/Program Files/nodejs"。没有 Docker。
PostgreSQL localhost:5432、Redis localhost:6379、MinIO http://localhost:9000 都是内网可用服务。

1) 后端单测/集成测试：
   cd backend/agent-server && JAVA_HOME="C:\\Program Files\\Java\\jdk-21.0.3+9" /c/tools/apache-maven-3.9.16/bin/mvn -B verify
2) 前端静态检查与单测：
   cd frontend && PATH="/d/Program Files/nodejs:$PATH" npx tsc -b --noEmit && npm run lint && npx vitest run
3) 端到端验收脚本（**必须串行**跑，它们各自会占用 8080/8099/5173/8098 端口并自己起停服务）：
   bash scripts/verify-p5-skills.sh
   bash scripts/verify-p6-mcp.sh
   bash scripts/verify-p7-hitl.sh
   bash scripts/verify-p72-cards.sh
   bash scripts/verify-p8-audit.sh
   bash scripts/verify-p9-acceptance.sh
   bash scripts/verify-p10-artifacts.sh
   bash scripts/verify-p10-minio-down.sh
   这些脚本都会打印 PASS/FAIL 与实测数据；跑之前先确保 8080/8099/5173/8098 没有别的实例在占用。
   单个脚本请用 timeout（例如 timeout 900）兜底，避免卡死；p6/p7 首次跑可能要等 npx 拉包。

## 规则
- **只读**：不要修改任何实现文件、配置、脚本或文档；唯一允许写出的文件是本任务的结果文件。
- 以「自己跑出来的结果」为准；文档、注释、截图和别人的结论都只当线索。
- 有一项证据不足或跑不起来，就如实标 blocked 并写清原因，不要猜着给 passed。
- 断言失败就是 failed（不是 blocked）。
- 不要把「测试通过」当成唯一标准：涉及契约的项（API 形状、SSE 事件、卡片字段、存储路由、错误码、工具命名）要对着 spec 看实现与实测输出。
- 时间预算约 40 分钟；优先跑上面 8 个脚本 + 后端 mvn verify + 前端 vitest，其余项靠读代码/规格判断。

## 产出（必须做）
把结果写成 JSON 文件：`.comet-verifier-verdict.json`，形状：
{{
  "verdict": "passed | failed | blocked",
  "acceptance": [{{"id": "A1", "result": "passed | failed | blocked", "reason": "<一句到三句，写你实际看到的证据>"}}, ... 共 40 项，每项恰好一次 ...],
  "risks": ["<你发现但不在验收范围内的风险>"],
  "summary": "<3-6 句：你跑了什么、结果如何、哪些项是你没能独立确认的>"
}}
最后再用普通文字简述你的结论。
"""

with open(".comet-verifier-prompt.md", "w", encoding="utf-8") as fh:
    fh.write(prompt)
print("提示词已生成，长度", len(prompt))
