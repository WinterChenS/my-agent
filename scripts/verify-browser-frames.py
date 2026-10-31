"""验证「连续操作时画面会更新」：first page → navigate 第二页 → 帧内容必须变化。"""
import base64, hashlib, json, os, secrets, sys, threading, time, urllib.request
import psycopg2
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
API = os.environ.get('API_BASE', 'http://localhost:8080')
conn = psycopg2.connect(host='192.168.3.10', port=5432, dbname='agent_platform',
                        user='app_user', password=os.environ['DB_PASSWORD'])
def call(p, m='GET', b=None, t=None, timeout=180):
    d = json.dumps(b).encode() if b is not None else None
    r = urllib.request.Request(API + p, data=d, method=m)
    r.add_header('Content-Type', 'application/json')
    if t: r.add_header('Authorization', 'Bearer ' + t)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as x: return json.loads(x.read().decode())
    except urllib.error.HTTPError as e: return json.loads(e.read().decode())
S = 'fr-' + secrets.token_hex(4)
cap = call(f'/api/auth/captcha?sessionKey={S}&mode=slider')['data']
cur = conn.cursor(); conn.commit()
cur.execute('select answer_x from captcha_challenge where id=%s', (cap['id'],)); ax = cur.fetchone()[0]
cur.close(); conn.commit()
track = [[round(min(0.85, i*0.04 + (i%3)*0.004), 4), 16*i + (i*7)%19] for i in range(22)]
v = call('/api/auth/captcha/verify', 'POST', {'id': cap['id'], 'sessionKey': S, 'x': ax, 'track': track})['data']
key = call('/api/auth/login-key')['data']
pub = serialization.load_pem_public_key(key['publicKey'].encode())
pl = json.dumps({'p': 'Admin@123', 't': int(time.time()*1000), 'n': secrets.token_hex(8), 's': S}).encode()
ct = base64.b64encode(pub.encrypt(pl, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))).decode()
login = call('/api/auth/login', 'POST', {'username': 'admin', 'encryptedPassword': ct, 'keyId': key['keyId'],
                                        'captchaTicket': v['ticket'], 'sessionKey': S})['data']
token = login['token']; conn.close()

frames = []
stop = False
def reader():
    req = urllib.request.Request(API + '/api/browser/frames')
    req.add_header('Authorization', 'Bearer ' + token); req.add_header('Accept', 'text/event-stream')
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            while not stop:
                line = r.readline()
                if not line: break
                if line.startswith(b'data:'):
                    try:
                        d = json.loads(line[5:].decode())
                        frames.append((time.time(), hashlib.md5(d['image'].encode()).hexdigest(), d['image']))
                    except Exception: pass
    except Exception as e:
        if not stop: print('帧流结束:', type(e).__name__)

def wait_frames(n_before, timeout=8):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if len(frames) > n_before: return True
        time.sleep(0.05)
    return False

call('/api/browser/navigate', 'POST', {'url': 'https://example.com'}, token)
threading.Thread(target=reader, daemon=True).start()
print('第 1 页 example.com …')
wait_frames(0, 10); time.sleep(1.0)
n1 = len(frames); h1 = frames[-1][1] if frames else None
print(f'  帧数={n1} hash={h1[:8] if h1 else None}')

print('导航到第 2 页 bing.com …')
call('/api/browser/navigate', 'POST', {'url': 'https://www.bing.com'}, token)
ok2 = wait_frames(n1, 12); time.sleep(2.0)
n2 = len(frames); h2 = frames[-1][1] if frames else None
print(f'  帧数={n2}（新增 {n2-n1}）hash={h2[:8] if h2 else None}')
open('logs/frames-2nd.jpg','wb').write(base64.b64decode(frames[-1][2]))

print('用户点击接管 …')
call('/api/browser/input', 'POST', {'type': 'click', 'x': 400, 'y': 300}, token)
n3 = len(frames); wait_frames(n3, 6)
print(f'  点击后新增帧 {len(frames)-n3}')
st = call('/api/browser/status', t=token)['data']
print('  status:', {k: st.get(k) for k in ('url','width','height','userControlled')})
stop = True
print()
print('结论：', '画面随操作更新 ✅' if (h1 and h2 and h1 != h2 and n2 > n1) else '画面没更新 ❌')
