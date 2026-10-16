#!/usr/bin/env python3
"""本地 OpenAI 兼容桩服务（仅用于开发期验证，不是平台组件）。

用途：在没有真实模型端点时，验证 `/api/model-providers/{id}/test` 与后续 AG-UI 聊天链路。
实现 `POST /v1/chat/completions`，支持非流式与 SSE 流式响应，并返回 OpenAI 形状的 `usage` 字段。

用法：
    python tools/llm-stub/stub.py            # 默认 127.0.0.1:8099
    PORT=9000 python tools/llm-stub/stub.py
"""

import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "8099"))
HOST = os.environ.get("HOST", "127.0.0.1")
REPLY = os.environ.get("STUB_REPLY", "stub-ok")
# 每个流式分片之间的延迟（毫秒）。默认 0；浏览器验收时调大以便观察到“逐字出现”。
# 默认给每片之间一点延迟（毫秒）：否则所有分片会在 1ms 内到达，客户端会合并成一次渲染，
# 导致「逐字流式」在浏览器里不可观测（测试与体验都受影响）。
CHUNK_DELAY_MS = int(os.environ.get("STUB_CHUNK_DELAY_MS", "250"))
# 可选标记：若收到的请求体里出现 STUB_MARKER，则回复 STUB_MARKER_REPLY。
# 用于验证“模型真的收到了某段内容”（例如 Skill 注入）。
STUB_MARKER = os.environ.get("STUB_MARKER", "")
STUB_MARKER_REPLY = os.environ.get("STUB_MARKER_REPLY", "MARKER-SEEN")
# 可选：模拟厂商对 max_tokens 的范围限制（如 DeepSeek 的 [1, 393216]）。
# 设为整数上限；超过则返回 400 + 类似厂商的错误体（用于回归“非法配置要在测试阶段暴露”）。
STUB_MAX_TOKENS_LIMIT = os.environ.get("STUB_MAX_TOKENS_LIMIT", "393216")  # 默认模拟厂商上限
# 可选：用「模型名」当回复内容（前缀 stub-ok）。用于需要“较长回复”来稳定观测流式的测试，
# 这样测试可以按模型名精确知道期望文本，且长度可控（模型名取长一点即可）。
STUB_REPLY_FROM_MODEL = os.environ.get("STUB_REPLY_FROM_MODEL", "1")
# 可选：把最近一次收到的请求体写到该文件，便于对照“模型实际收到了什么”。
STUB_DUMP = os.environ.get("STUB_DUMP", "")
# 可选：让桩模拟“模型要调用工具”（用于验证 MCP 工具与 HITL 审批链路）。
# STUB_TOOL_NAME 为工具名片段（大小写不敏感）；仅在请求里带了工具、且尚未出现 tool 结果时触发一次。
# 触发时会在 stdout 打印命中的完整工具名（含命名空间），便于验收取证。
STUB_TOOL_NAME = os.environ.get("STUB_TOOL_NAME", "")
STUB_TOOL_ARGS = os.environ.get("STUB_TOOL_ARGS", "{}")
# 可选：一次 run 里连续发起几轮工具调用（用于验证“连续工具调用折叠成组”）。
# 默认 1；桩会数请求里已有的 tool 结果条数，未达上限就再发一次工具调用。
STUB_TOOL_ROUNDS = int(os.environ.get("STUB_TOOL_ROUNDS", "1"))
# 可选：模拟网关“没有这个模型”的 404（用于验证前端错误提示）。
# 当请求的 model **包含** STUB_MODEL_404 子串时，返回 404 + 类似网关的错误体（便于用唯一名字测试）。
# 可选：模型名含该子串时，返回一份**合法的技能草稿 JSON**。
# 用途：「用 AI 生成技能」的端到端用例要自给自足（项目原则：不依赖用户维护的真实模型），
# 而那条链路要求模型输出严格 JSON —— 靠 STUB_REPLY 全局改会破坏其它断言 stub-ok 的用例，
# 所以这里按**模型名**分流，各用各的。
STUB_SKILL_JSON_MODEL = os.environ.get("STUB_SKILL_JSON_MODEL", "skill-json")
STUB_SKILL_JSON = os.environ.get(
    "STUB_SKILL_JSON",
    '{"name":"stub-generated-skill","description":"由桩生成的技能：用于验证 AI 生成链路",'
    # 注意：这里必须写两个字符的反斜杠+n，让 JSON 文本里是**转义序列**；
    # 如果写成单个反斜杠+n，Python 会先把它变成真换行 → 嵌在 JSON 字符串里就是非法 JSON（实测踩过）。
    '"content":"# 桩生成的技能\\n\\n## 什么时候用\\n- 端到端验证 AI 生成技能时。\\n\\n'
    '## 步骤\\n1. 调用 `read_file` 读取目标文件。\\n2. 用 `demo_confirm_card` 向用户确认。"}',
)
# 可选：模型名含该子串时，返回一段**带围栏代码块的 Markdown**（用于验证代码块样式/对比度）。
# 同理按模型名分流，避免影响其它用例。
STUB_CODE_FENCE_MODEL = os.environ.get("STUB_CODE_FENCE_MODEL", "code-fence")
# 可选：模型名含该子串时，用「慢速分片」模拟长任务（用于验证后台通知/侧栏 running 标记这类用例）。
# 为什么要按模型名而不是全局：早先用「改全局 STUB_CHUNK_DELAY_MS」的办法，跑完经常忘了恢复，
# 导致后面整轮 e2e 都变慢并大量超时（实测踩过两次）。按模型名分流后，全局延迟永远是快的。
STUB_SLOW_MODEL = os.environ.get("STUB_SLOW_MODEL", "slow-")
# 模型名含它（默认 card-）时，桩主动发一次 `demo_confirm_card` 工具调用。
# 用于验证"交互卡片悬浮在输入框上方"，且不必改全局 STUB_TOOL_NAME 去污染别的用例。
STUB_CARD_MODEL = os.environ.get("STUB_CARD_MODEL", "card-")
# 模型名含它（默认 script-）时，桩走「两轮脚本」：先 write_file 写一个文件，再 deliver_artifact
# 把它作为**产物**交付（官方产物交付链路）。用于验证"产物落到哪一轮"与气泡下方的展示。
STUB_SCRIPT_MODEL = os.environ.get("STUB_SCRIPT_MODEL", "script-")
# 模型名含它（默认 think-）时，桩在**首字之前**先静默等待 STUB_THINK_DELAY_MS。
# 用来模拟"推理型模型想很久、界面上半天不出气泡"的场景（验证占位 loading 气泡）。
STUB_THINK_MODEL = os.environ.get("STUB_THINK_MODEL", "think-")
STUB_THINK_DELAY_MS = int(os.environ.get("STUB_THINK_DELAY_MS", "5000"))
STUB_SLOW_DELAY_MS = int(os.environ.get("STUB_SLOW_DELAY_MS", "2500"))
STUB_CODE_FENCE = os.environ.get(
    "STUB_CODE_FENCE",
    "这是示例：\n\n```json\n{\"action\": \"out_of_scope\", \"reason\": \"request unrelated\"}\n```\n\n完毕。",
)
STUB_MODEL_404 = os.environ.get("STUB_MODEL_404", "wrong-model")  # 默认开启：名字含它即回 404
STUB_MODEL_404_HINT = os.environ.get("STUB_MODEL_404_HINT", "deepseek/deepseek-flash, tencent/GLM-5.2")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _json(self, status, payload):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path in ("/health", "/"):
            self._json(200, {"status": "ok", "stub": True})
        else:
            self._json(404, {"error": {"message": "not found"}})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        if STUB_DUMP:
            try:
                # 追写所有请求（一次对话可能包含主对话 + 记忆抽取等多个调用）
                with open(STUB_DUMP, "ab") as fh:
                    fh.write(b"\n=====REQUEST=====\n")
                    fh.write(raw)
            except OSError:
                pass
        try:
            body = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            self._json(400, {"error": {"message": "invalid json"}})
            return

        if not self.path.rstrip("/").endswith("/chat/completions"):
            self._json(404, {"error": {"message": f"unknown path {self.path}"}})
            return

        auth = self.headers.get("Authorization") or ""
        if not auth.startswith("Bearer ") or len(auth) <= len("Bearer "):
            self._json(401, {"error": {"message": "missing api key", "type": "invalid_request_error"}})
            return

        model = body.get("model", "stub-model")
        if STUB_MODEL_404 and STUB_MODEL_404 in model:
            self._json(404, {"error": {
                "code": None,
                "message": f'magpie knows no model "{model}"; it has {STUB_MODEL_404_HINT}',
                "param": None,
                "type": "not_found_error",
            }})
            return
        if STUB_MAX_TOKENS_LIMIT:
            limit = int(STUB_MAX_TOKENS_LIMIT)
            sent = body.get("max_tokens")
            if isinstance(sent, int) and sent > limit:
                self._json(400, {"error": {
                    "code": None,
                    "message": f"DeepSeek: Invalid max_tokens value, the valid range of max_tokens is [1, {limit}]",
                    "param": None,
                    "type": "invalid_request_error",
                }})
                return
        created = int(time.time())
        usage = {"prompt_tokens": 11, "completion_tokens": 5, "total_tokens": 16}
        tool_call = self._tool_call_to_emit(body)

        if body.get("stream"):
            # 分片延迟：默认用全局值；模型名含 STUB_SLOW_MODEL（默认 slow-）时用慢速值。
            # 这样「需要慢响应」的用例只靠模型名就能拿到，不必改全局环境变量
            # （早先靠改全局值，跑完忘恢复会让后面整轮 e2e 变慢并超时，实测踩过两次）。
            delay = CHUNK_DELAY_MS
            if STUB_SLOW_MODEL and STUB_SLOW_MODEL in str(model):
                delay = STUB_SLOW_DELAY_MS
            # 「想很久」：首字之前先静默等待（模拟推理型模型）
            first_delay = 0
            if STUB_THINK_MODEL and STUB_THINK_MODEL in str(model):
                first_delay = STUB_THINK_DELAY_MS
            if tool_call:
                self._stream_tool_call(model, created, usage, tool_call, delay)
            else:
                self._stream(model, created, usage, self._reply_text(body, raw), delay, first_delay)
        else:
            message = {"role": "assistant", "content": self._reply_for(raw, model)}
            finish = "stop"
            if tool_call:
                message = {"role": "assistant", "content": None, "tool_calls": [tool_call]}
                finish = "tool_calls"
            self._json(200, {
                "id": "chatcmpl-stub",
                "object": "chat.completion",
                "created": created,
                "model": model,
                "choices": [{"index": 0, "message": message, "finish_reason": finish}],
                "usage": usage,
            })

    def _tool_call_to_emit(self, body):
        """
        决定本轮是否要模拟一次工具调用。

        规则：配置了 STUB_TOOL_NAME、请求里带了工具、且还没出现任何 tool 结果（避免无限循环）。
        命中时在 stdout 打印完整工具名（含命名空间），便于验收取证。
        """
        model = str(body.get("model", ""))
        card_mode = bool(STUB_CARD_MODEL) and STUB_CARD_MODEL.lower() in model.lower()
        script_mode = bool(STUB_SCRIPT_MODEL) and STUB_SCRIPT_MODEL.lower() in model.lower()
        if script_mode:
            return self._script_tool_call(body, model)
        if not STUB_TOOL_NAME and not card_mode:
            return None
        tools = body.get("tools") or []
        if not tools:
            return None
        tool_done = sum(1 for m in (body.get("messages") or []) if m.get("role") == "tool")
        if tool_done >= STUB_TOOL_ROUNDS:
            return None  # 已达到设定轮数，该收尾了
        wanted = "demo_confirm_card" if card_mode else STUB_TOOL_NAME.lower()
        args = (
            json.dumps({"question": "将在生产环境删除订单历史分区（不可恢复），是否继续？"}, ensure_ascii=False)
            if card_mode
            else STUB_TOOL_ARGS
        )
        for t in tools:
            fn = (t or {}).get("function") or {}
            name = fn.get("name") or ""
            if wanted in name.lower():
                print(f"TOOL-CALL-HIT={name}", flush=True)
                return {
                    "id": f"call_stub_{tool_done + 1}",
                    "type": "function",
                    "function": {"name": name, "arguments": args},
                }
        return None

    def _script_tool_call(self, body, model):
        """
        两轮脚本：① write_file 写一个文件；② deliver_artifact 把它作为产物交付。

        参数名取自官方/框架实际下发的 schema（实测 dump 得到）：
          write_file(path, content)
          deliver_artifact(filePath, fileName, description, force)
        """
        tools = body.get("tools") or []
        if not tools:
            return None
        tool_done = sum(1 for m in (body.get("messages") or []) if m.get("role") == "tool")
        suffix = "".join(ch for ch in model if ch.isdigit()) or "1"
        path = f"outputs/report-{suffix}.md"
        if tool_done == 0:
            wanted = "write_file"
            body_text = chr(10).join(["# 季度报告 " + suffix, "", "这是本轮交付的正文。", ""])
            args = json.dumps({"path": path, "content": body_text}, ensure_ascii=False)
        elif tool_done == 1:
            wanted = "deliver_artifact"
            args = json.dumps(
                {"filePath": path, "fileName": f"报告-{suffix}.md", "description": "季度报告（桩生成）"},
                ensure_ascii=False,
            )
        else:
            return None
        for t in tools:
            fn = (t or {}).get("function") or {}
            name = fn.get("name") or ""
            if wanted in name.lower():
                print(f"TOOL-CALL-HIT={name}", flush=True)
                return {
                    "id": f"call_script_{tool_done + 1}",
                    "type": "function",
                    "function": {"name": name, "arguments": args},
                }
        return None

    def _stream_tool_call(self, model, created, usage, tool_call, chunk_delay_ms=None):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        def chunk(delta, finish=None):
            payload = {
                "id": "chatcmpl-stub",
                "object": "chat.completion.chunk",
                "created": created,
                "model": model,
                "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
            }
            self.wfile.write(f"data: {json.dumps(payload, ensure_ascii=False)}\n\n".encode("utf-8"))
            self.wfile.flush()

        chunk({"role": "assistant", "content": ""})
        name = tool_call["function"]["name"]
        args = tool_call["function"]["arguments"]
        chunk({"tool_calls": [{
            "index": 0,
            "id": tool_call["id"],
            "type": "function",
            "function": {"name": name, "arguments": ""},
        }]})
        for piece in [args[i:i + 8] for i in range(0, len(args), 8)]:
            delay = CHUNK_DELAY_MS if chunk_delay_ms is None else chunk_delay_ms
            if delay > 0:
                time.sleep(delay / 1000.0)
            chunk({"tool_calls": [{"index": 0, "function": {"arguments": piece}}]})
        chunk({}, finish="tool_calls")

        final = {
            "id": "chatcmpl-stub",
            "object": "chat.completion.chunk",
            "created": created,
            "model": model,
            "choices": [],
            "usage": usage,
        }
        self.wfile.write(f"data: {json.dumps(final, ensure_ascii=False)}\n\n".encode("utf-8"))
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def _reply_text(self, body, raw: bytes = b"") -> str:
        # 「技能 JSON」分流优先：AI 生成技能的用例要求模型输出严格 JSON
        if STUB_SKILL_JSON_MODEL and STUB_SKILL_JSON_MODEL in str(body.get("model", "")):
            return STUB_SKILL_JSON
        # 围栏代码块分流：验证代码块样式/对比度的用例用
        if STUB_CODE_FENCE_MODEL and STUB_CODE_FENCE_MODEL in str(body.get("model", "")):
            return STUB_CODE_FENCE
        # 标记优先：标记是「模型确实收到了这段内容」的强证据，
        # 不能被 STUB_REPLY_FROM_MODEL 的按模型名回复盖掉（曾经因此让 Skill 注入的端到端断言假红）。
        if STUB_MARKER:
            try:
                if STUB_MARKER.encode("utf-8") in raw:
                    return f"{REPLY} {STUB_MARKER_REPLY}"
            except Exception:  # 标记匹配失败不应影响服务
                pass
        if STUB_REPLY_FROM_MODEL:
            return f"stub-ok {body.get('model', '')}"
        return REPLY

    @staticmethod
    def _reply_for(raw: bytes, model: str = "") -> str:
        """若配置了 STUB_MARKER 且请求体包含它，就回一个可识别的标记，证明模型确实收到了该内容。"""
        if STUB_SKILL_JSON_MODEL and STUB_SKILL_JSON_MODEL in str(model):
            return STUB_SKILL_JSON
        if STUB_CODE_FENCE_MODEL and STUB_CODE_FENCE_MODEL in str(model):
            return STUB_CODE_FENCE
        if STUB_MARKER:
            try:
                if STUB_MARKER.encode("utf-8") in raw:
                    return f"{REPLY} {STUB_MARKER_REPLY}"
            except Exception:  # 标记匹配失败不应影响服务
                pass
        return REPLY

    def _stream(self, model, created, usage, reply, chunk_delay_ms=None, first_delay_ms=0):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        def chunk(delta, finish=None):
            payload = {
                "id": "chatcmpl-stub",
                "object": "chat.completion.chunk",
                "created": created,
                "model": model,
                "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
            }
            self.wfile.write(f"data: {json.dumps(payload, ensure_ascii=False)}\n\n".encode("utf-8"))
            self.wfile.flush()

        if first_delay_ms and first_delay_ms > 0:
            # 首字之前保持静默：模型「在想」，还没有任何内容产出（用于验证占位 loading 气泡）
            time.sleep(first_delay_ms / 1000.0)
        chunk({"role": "assistant", "content": ""})
        for piece in [reply[i:i + 3] for i in range(0, len(reply), 3)]:
            delay = CHUNK_DELAY_MS if chunk_delay_ms is None else chunk_delay_ms
            if delay > 0:
                time.sleep(delay / 1000.0)
            chunk({"content": piece})
        chunk({}, finish="stop")

        final = {
            "id": "chatcmpl-stub",
            "object": "chat.completion.chunk",
            "created": created,
            "model": model,
            "choices": [],
            "usage": usage,
        }
        self.wfile.write(f"data: {json.dumps(final, ensure_ascii=False)}\n\n".encode("utf-8"))
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def log_message(self, fmt, *args):  # 静音访问日志
        pass


if __name__ == "__main__":
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"llm-stub listening on http://{HOST}:{PORT} (reply={REPLY!r})", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        sys.exit(0)
