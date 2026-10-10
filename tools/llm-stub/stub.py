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
CHUNK_DELAY_MS = int(os.environ.get("STUB_CHUNK_DELAY_MS", "0"))


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
        created = int(time.time())
        usage = {"prompt_tokens": 11, "completion_tokens": 5, "total_tokens": 16}

        if body.get("stream"):
            self._stream(model, created, usage)
        else:
            self._json(200, {
                "id": "chatcmpl-stub",
                "object": "chat.completion",
                "created": created,
                "model": model,
                "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": REPLY},
                    "finish_reason": "stop",
                }],
                "usage": usage,
            })

    def _stream(self, model, created, usage):
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
        for piece in [REPLY[i:i + 3] for i in range(0, len(REPLY), 3)]:
            if CHUNK_DELAY_MS > 0:
                time.sleep(CHUNK_DELAY_MS / 1000.0)
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
