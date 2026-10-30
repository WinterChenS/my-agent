"""验证码安全自测（本地 8080）：服务端判定 / 会话绑定 / 重放 / 并发 / 限流 / 行为 / 无障碍题。"""
import json, os, threading, time, urllib.request, sys
import psycopg2

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
B = os.environ.get("CAPTCHA_BASE", "http://localhost:8080")
SESSION = "it-session-" + str(int(time.time()))
OTHER = "it-session-other-" + str(int(time.time()))

conn = psycopg2.connect(host="192.168.3.10", port=5432, dbname="agent_platform",
                        user="app_user", password=os.environ["DB_PASSWORD"])


def call(path, method="GET", body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(B + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())


def db(sql, args=()):
    cur = conn.cursor()
    conn.commit()
    cur.execute(sql, args)
    row = cur.fetchone() if cur.description else None
    cur.close()
    conn.commit()  # 别把连接留在 idle-in-transaction，否则会挡别人
    return row


def new_challenge(mode="slider", session=SESSION):
    q = f"/api/auth/captcha?sessionKey={session}&mode={mode}"
    data = call(q)["data"]
    row = db("select answer_x, piece_y, text_answer, mode from captcha_challenge where id=%s", (data["id"],))
    return data, row


def human_track(end_ratio=0.85):
    track, x, t = [], 0.0, 0.0
    for i in range(24):
        x += 0.01 if i < 4 else (0.05 + (i % 3) * 0.012 if i < 18 else 0.02)
        t += 18 + (i * 7) % 23
        if i == 12:
            x -= 0.02
        track.append([round(min(end_ratio, x), 4), t])
    return track


def ok(label, cond, extra=""):
    print(("  ✅ " if cond else "  ❌ ") + label + (("  " + extra) if extra else ""))
    return cond


results = []
print("== 0. 题目响应不含答案 ==")
data, row = new_challenge()
results.append(ok("响应无 answerX 字段", "answerX" not in json.dumps(data)))
results.append(ok("图片尺寸/边距下发正确", data["pieceSize"] == 72 and data["piecePad"] == 14,
                  f'pieceSize={data["pieceSize"]} pad={data["piecePad"]}'))
answers = [data]
print(f"  题目 id={data['id'][:8]} 答案 x={row[0]} 容差 ±6")

print("== 1. 正常用户通过 ==")
data, row = new_challenge()
r = call("/api/auth/captcha/verify", "POST",
         {"id": data["id"], "sessionKey": SESSION, "x": row[0], "track": human_track()})
results.append(ok("位置对 + 人类轨迹 → 拿到 ticket", r["code"] == 0, str(r.get("data"))[:40]))
ticket = r["data"]["ticket"] if r["code"] == 0 else None

print("== 2. 位置错误失败 ==")
data, row = new_challenge()
r = call("/api/auth/captcha/verify", "POST",
         {"id": data["id"], "sessionKey": SESSION, "x": min(300, row[0] + 40), "track": human_track()})
results.append(ok("位置错 → 失败且不泄露正确位置", r["code"] == 400 and "位置" in r["msg"], r["msg"]))
results.append(ok("失败信息里没有正确坐标", str(row[0]) not in r["msg"]))

print("== 3. 重放 ticket ==")
r2 = call("/api/auth/login", "POST",
          {"username": "admin", "password": "Admin@123", "captchaTicket": ticket, "sessionKey": SESSION})
results.append(ok("首次登录成功", r2["code"] == 0))
r3 = call("/api/auth/login", "POST",
          {"username": "admin", "password": "Admin@123", "captchaTicket": ticket, "sessionKey": SESSION})
results.append(ok("重复使用同一 ticket 被拒", r3["code"] == 400, r3["msg"]))

print("== 4. 跳过验证码直接登录 ==")
r = call("/api/auth/login", "POST", {"username": "admin", "password": "Admin@123"})
results.append(ok("无 ticket 登录被拒", r["code"] == 400, r["msg"]))

print("== 5. 会话绑定 ==")
data, row = new_challenge()
r = call("/api/auth/captcha/verify", "POST",
         {"id": data["id"], "sessionKey": OTHER, "x": row[0], "track": human_track()})
results.append(ok("换会话提交同一题被拒", r["code"] == 400, r["msg"]))

print("== 6. 行为轨迹伪造 ==")
data, row = new_challenge()
r = call("/api/auth/captcha/verify", "POST",
         {"id": data["id"], "sessionKey": SESSION, "x": row[0],
          "track": [[0.0, 0.0], [0.85, 4.0]]})
results.append(ok("位置对但瞬移脚本轨迹被拒", r["code"] == 400 and "验证未通过" in r["msg"], r["msg"]))

print("== 7. 并发提交同一题 ==")
data, row = new_challenge()
out = []


def hit():
    out.append(call("/api/auth/captcha/verify", "POST",
                    {"id": data["id"], "sessionKey": SESSION, "x": row[0], "track": human_track()}))


ts = [threading.Thread(target=hit) for _ in range(8)]
[t.start() for t in ts]
[t.join() for t in ts]
wins = [x for x in out if x["code"] == 0]
results.append(ok("8 个并发请求只成功 1 个", len(wins) == 1, f"成功 {len(wins)} 个"))

print("== 8. 高频错误 → 限流 ==")
codes = []
for _ in range(15):
    d, rw = new_challenge(session=SESSION + "-flood")
    resp = call("/api/auth/captcha/verify", "POST",
                {"id": d["id"], "sessionKey": SESSION + "-flood", "x": 5, "track": human_track()})
    codes.append(resp["code"])
results.append(ok("连续失败后出现 429", 429 in codes, f"codes={sorted(set(codes))}"))

print("== 9. 无障碍文字题 ==")
data, row = new_challenge(mode="text", session=SESSION + "-text")
results.append(ok("文字题有题面且无图片", bool(data.get("question")) and not data.get("background"), data.get("question")))
r = call("/api/auth/captcha/verify", "POST",
         {"id": data["id"], "sessionKey": SESSION + "-text", "answer": row[2]})
results.append(ok("文字题答对 → ticket", r["code"] == 0, str(r.get("msg", ""))))
d2, row2 = new_challenge(mode="text", session=SESSION + "-text")
r = call("/api/auth/captcha/verify", "POST",
         {"id": d2["id"], "sessionKey": SESSION + "-text", "answer": "999"})
results.append(ok("文字题答错 → 失败", r["code"] == 400, r["msg"]))

print("== 10. 题目过期 ==")
d, row = new_challenge(session=SESSION + "-exp")
db("update captcha_challenge set expires_at = NOW() - INTERVAL '1 second' where id=%s", (d["id"],))
r = call("/api/auth/captcha/verify", "POST",
         {"id": d["id"], "sessionKey": SESSION + "-exp", "x": row[0], "track": human_track()})
results.append(ok("过期题被拒", r["code"] == 400 and "过期" in r["msg"], r["msg"]))

print("== 11. 随机性抽样 ==")
xs = set()
for _ in range(12):
    d, row = new_challenge(session=SESSION + "-rand")
    xs.add(row[0])
results.append(ok("12 次出题缺口位置分散", len(xs) >= 8, f"不同位置 {len(xs)}/12"))

conn.close()
print()
print(f"通过 {sum(1 for r in results if r)}/{len(results)}")
sys.exit(0 if all(results) else 1)
