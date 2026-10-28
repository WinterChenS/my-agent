#!/usr/bin/env python
"""邮件连接器端到端验证（对着真实邮箱跑一遍）。

用法：
    python scripts/verify-mail-connector.py --base http://192.168.3.10:18080 \
        --email me@qq.com --code <授权码> [--provider qq|163|gmail|custom] \
        [--agent 小梅] [--keep]

做四件事：
    1. 建一个临时邮件连接器（IMAP+SMTP）
    2. 点「测试连接」，把真实的登录结果打印出来
    3. 绑定到指定 Agent，然后让它在对话里真的读一次收件箱（走 mail_list 工具）
    4. 默认删掉这个临时连接器（--keep 保留）

为什么单独写脚本：邮件是**真实副作用**（发信不可撤销），验证过程要可复现、可清理。
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid

PROVIDERS = {
    "qq": ("imap.qq.com", "993", "smtp.qq.com", "465"),
    "163": ("imap.163.com", "993", "smtp.163.com", "465"),
    "126": ("imap.126.com", "993", "smtp.126.com", "465"),
    "gmail": ("imap.gmail.com", "993", "smtp.gmail.com", "587"),
}


def call(base: str, path: str, method: str = "GET", body=None, token: str | None = None,
         stream: bool = False, timeout: int = 300) -> str:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(base + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if stream:
        request.add_header("Accept", "text/event-stream")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.read().decode("utf-8", "replace")


def call_json(base: str, path: str, method: str = "GET", body=None, token: str | None = None):
    return json.loads(call(base, path, method, body, token))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://192.168.3.10:18080")
    parser.add_argument("--email", required=True)
    parser.add_argument("--code", required=True, help="邮箱授权码（不是登录密码）")
    parser.add_argument("--provider", default="qq", choices=[*PROVIDERS, "custom"])
    parser.add_argument("--imap-host")
    parser.add_argument("--imap-port", default="993")
    parser.add_argument("--smtp-host")
    parser.add_argument("--smtp-port", default="465")
    parser.add_argument("--agent", default="小梅")
    parser.add_argument("--username", help="登录账号，默认同邮箱地址")
    parser.add_argument("--keep", action="store_true", help="验证后保留连接器")
    args = parser.parse_args()

    if args.provider == "custom":
        imap_host, smtp_host = args.imap_host, args.smtp_host
        if not imap_host or not smtp_host:
            print("custom 需要 --imap-host/--smtp-host")
            return 2
        imap_port, smtp_port = args.imap_port, args.smtp_port
        imap_ssl = imap_port == "993"
        smtp_ssl = smtp_port == "465"
    else:
        imap_host, imap_port, smtp_host, smtp_port = PROVIDERS[args.provider]
        imap_ssl = True
        smtp_ssl = smtp_port == "465"

    token = call_json(args.base, "/api/auth/login", "POST",
                      {"username": "admin", "password": "Admin@123"})["data"]["token"]

    config = {
        "email": args.email,
        "imapHost": imap_host,
        "imapPort": imap_port,
        "imapSsl": "true" if imap_ssl else "false",
        "smtpHost": smtp_host,
        "smtpPort": smtp_port,
        "smtpSsl": "true" if smtp_ssl else "false",
        "username": args.username or args.email,
        "password": args.code,
        "allowSend": "false",  # 验证阶段先不开放发信，避免误发
        "inboundMode": "OFF",
    }
    payload = {
        "type": "MAIL",
        "name": f"验证-{args.provider}-{uuid.uuid4().hex[:4]}",
        "enabled": True,
        "config": config,
        "inboundMode": "OFF",
    }

    created = call_json(args.base, "/api/connectors", "POST", payload, token)
    if created.get("code") != 0:
        print("创建失败:", created)
        return 1
    connector = created["data"]
    connector_id = connector["id"]
    print(f"[1/4] 已创建连接器 #{connector_id} {connector['name']}（账号 {connector['account']}，"
          f"密码回显 {connector['config'].get('password')}）")

    result = call_json(args.base, f"/api/connectors/{connector_id}/test", "POST", None, token)["data"]
    print(f"[2/4] 测试连接: {'成功' if result['ok'] else '失败'} → {result['message']}")
    for step in result.get("steps", []):
        print("      -", step)
    if not result["ok"]:
        if not args.keep:
            call_json(args.base, f"/api/connectors/{connector_id}", "DELETE", None, token)
            print("      已清理临时连接器")
        return 1

    agents = call_json(args.base, "/api/agents?current=1&size=200", token)["data"]
    records = agents.get("records") or agents.get("list") or []
    agent = next((a for a in records if a["name"] == args.agent), records[0] if records else None)
    if agent is None:
        print("没有任何 Agent，无法继续")
        return 1
    bound = call_json(args.base, f"/api/connectors/{connector_id}/agents", "PUT",
                      {"agentIds": [agent["id"]]}, token)
    print(f"[3/4] 已绑定到 Agent「{agent['name']}」({agent['id']}) → {bound.get('data')}")

    thread = f"mail-verify-{uuid.uuid4().hex[:8]}"
    run_payload = {
        "threadId": thread,
        "runId": "r1",
        "state": {},
        "messages": [{
            "id": "m1",
            "role": "user",
            "content": "用 mail_list 看看我收件箱最近的 3 封邮件，只列主题、发件人和时间。",
        }],
        "tools": [],
        "context": [],
        "forwardedProps": {"agentId": agent["agentId"]},
    }
    raw = call(args.base, "/agui/run", "POST", run_payload, token, stream=True)
    text, tools = [], []
    for line in raw.splitlines():
        if not line.startswith("data:"):
            continue
        try:
            event = json.loads(line[5:])
        except Exception:
            continue
        kind = event.get("type")
        if kind == "TEXT_MESSAGE_CONTENT":
            text.append(event.get("delta", ""))
        elif kind == "TOOL_CALL_START":
            tools.append(event.get("toolCallName"))
        elif kind == "RUN_ERROR":
            print("      RUN_ERROR:", str(event.get("message"))[:300])
    print(f"[4/4] 对话调用的工具: {tools}")
    print("------ 模型回答 ------")
    print("".join(text)[:2000])

    if not args.keep:
        cleanup = call_json(args.base, f"/api/connectors/{connector_id}", "DELETE", None, token)
        print("已清理临时连接器:", cleanup.get("code") == 0)
    return 0


if __name__ == "__main__":
    sys.exit(main())
