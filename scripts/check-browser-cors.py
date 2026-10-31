"""复现 / 验证「网页点批准配对 403」：CORS 不能把同源请求当跨域拒绝。"""
import base64, json, os, secrets, sys, time, urllib.request
import psycopg2
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
API = os.environ.get('API_BASE', 'http://localhost:8080')
conn = psycopg2.connect(host='192.168.3.10', port=5432, dbname='agent_platform',
                        user='app_user', password=os.environ['DB_PASSWORD'])
def call(p, m='GET', b=None, t=None, headers=None, raw=False):
    d = json.dumps(b).encode() if b is not None else None
    r = urllib.request.Request(API + p, data=d, method=m)
    r.add_header('Content-Type', 'application/json')
    if t: r.add_header('Authorization', 'Bearer ' + t)
    for k, v in (headers or {}).items(): r.add_header(k, v)
    try:
        with urllib.request.urlopen(r, timeout=60) as x:
            return x.status, dict(x.headers), x.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode()
S = 'cors-' + secrets.token_hex(4)
cap = call(f'/api/auth/captcha?sessionKey={S}&mode=slider')[2]
cap = json.loads(cap)['data']
cur = conn.cursor(); conn.commit()
cur.execute('select answer_x from captcha_challenge where id=%s', (cap['id'],)); ax = cur.fetchone()[0]
cur.close(); conn.commit(); conn.close()
track = [[round(min(0.85, i*0.04 + (i%3)*0.004), 4), 16*i + (i*7) % 19] for i in range(22)]
v = json.loads(call('/api/auth/captcha/verify', 'POST',
                    {'id': cap['id'], 'sessionKey': S, 'x': ax, 'track': track})[2])['data']
key = json.loads(call('/api/auth/login-key')[2])['data']
pub = serialization.load_pem_public_key(key['publicKey'].encode())
pl = json.dumps({'p': 'Admin@123', 't': int(time.time()*1000),
                 'n': secrets.token_hex(8), 's': S}).encode()
ct = base64.b64encode(pub.encrypt(pl, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),
                                                   algorithm=hashes.SHA256(), label=None))).decode()
token = json.loads(call('/api/auth/login', 'POST', {
    'username': 'admin', 'encryptedPassword': ct, 'keyId': key['keyId'],
    'captchaTicket': v['ticket'], 'sessionKey': S})[2])['data']['token']

body = {'userCode': 'AAAAAAAA'}
cases = [
    ('无 Origin（curl 直连）', {}),
    ('同源：Origin 与反代 Host 一致', {'Origin': 'http://192.168.3.10:18080',
                                      'X-Forwarded-Host': '192.168.3.10:18080',
                                      'X-Forwarded-Proto': 'http'}),
    ('扩展来源', {'Origin': 'chrome-extension://abcdefghijklmnopabcdefghijklmnop'}),
    ('外站来源（应拒绝）', {'Origin': 'http://evil.example.com'}),
]
for name, headers in cases:
    status, resp_headers, text = call('/api/browser/extension/pairings/approve', 'POST',
                                      body, token, headers)
    acao = resp_headers.get('Access-Control-Allow-Origin', '-')
    print(f'{name:28s} → {status}  ACAO={acao}  body={text[:60]}')
