"""本地 UI 验证：登录 → 让 agent 打开浏览器 → 截图看「浏览器面板是否铺满、模样像不像 Codex」。"""
import base64, json, os, secrets, sys, time, urllib.request
import psycopg2
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from playwright.sync_api import sync_playwright

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
API = os.environ.get('API_BASE','http://localhost:8080')
UI = os.environ.get('UI_BASE','http://localhost:5173')

conn = psycopg2.connect(host='192.168.3.10', port=5432, dbname='agent_platform',
                        user='app_user', password=os.environ['DB_PASSWORD'])

def call(path, method='GET', body=None, token=None, timeout=120):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())

S = 'ui-' + secrets.token_hex(4)
cap = call(f'/api/auth/captcha?sessionKey={S}&mode=slider')['data']
cur = conn.cursor(); conn.commit()
cur.execute('select answer_x from captcha_challenge where id=%s', (cap['id'],)); ax = cur.fetchone()[0]
cur.close(); conn.commit()
track = [[round(min(0.85, i * 0.04 + (i % 3) * 0.004), 4), 16 * i + (i * 7) % 19] for i in range(22)]
v = call('/api/auth/captcha/verify', 'POST', {'id': cap['id'], 'sessionKey': S, 'x': ax, 'track': track})['data']
key = call('/api/auth/login-key')['data']
pub = serialization.load_pem_public_key(key['publicKey'].encode())
pl = json.dumps({'p': 'Admin@123', 't': int(time.time() * 1000), 'n': secrets.token_hex(8), 's': S}).encode()
ct = base64.b64encode(pub.encrypt(pl, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),
                                                   algorithm=hashes.SHA256(), label=None))).decode()
login = call('/api/auth/login', 'POST', {'username': 'admin', 'encryptedPassword': ct,
                                        'keyId': key['keyId'], 'captchaTicket': v['ticket'], 'sessionKey': S})['data']
conn.close()
print('登录 OK')

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page(viewport={'width': 1600, 'height': 900})
    page.goto(UI + '/login', wait_until='load')
    # 注入登录态（zustand persist 的 key 是 myagent-auth）
    page.evaluate("""([token, user]) => {
        localStorage.setItem('myagent-auth', JSON.stringify({state: {token, user}, version: 0}));
    }""", [login['token'], login['user']])
    page.goto(UI + '/chat', wait_until='load')
    page.wait_for_timeout(2500)
    page.screenshot(path='logs/ui-1-chat.png')
    print('已截图：聊天页')

    # 发消息让 agent 打开浏览器
    box = page.locator('[contenteditable="true"]').first
    box.click()
    page.keyboard.type('用浏览器打开 https://example.com ，然后把页面标题告诉我')
    page.wait_for_timeout(300)
    page.keyboard.press('Enter')
    print('已发送，等待 agent 调用 browser_open…')
    for i in range(60):
        page.wait_for_timeout(1000)
        st = call('/api/browser/status', token=login['token'])['data']
        if st.get('active'):
            print('浏览器已激活:', st.get('url'), '|', st.get('width'), 'x', st.get('height'))
            break
    page.wait_for_timeout(4000)
    page.screenshot(path='logs/ui-2-browser.png')
    print('已截图：浏览器面板')

    # 拖窄面板 → 页面应跟着 reflow（视口宽度变化）
    before = call('/api/browser/status', token=login['token'])['data']
    handle = page.locator('[data-testid="preview-resize-handle"]')
    if handle.count() > 0:
        bb = handle.bounding_box()
        if bb:
            page.mouse.move(bb['x'] + 2, bb['y'] + 300)
            page.mouse.down()
            page.mouse.move(bb['x'] + 260, bb['y'] + 300, steps=12)
            page.mouse.up()
            page.wait_for_timeout(2500)
            after = call('/api/browser/status', token=login['token'])['data']
            print(f'拖窄后视口: {before.get("width")}x{before.get("height")} -> {after.get("width")}x{after.get("height")}')
            page.screenshot(path='logs/ui-3-resized.png')
            print('已截图：拖窄之后')
    browser.close()
print('完成')
