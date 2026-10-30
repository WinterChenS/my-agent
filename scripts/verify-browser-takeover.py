"""验证「用户接管」：点『我要操作』→ 点页面上的 Learn more → URL 应变成 iana.org。"""
import base64, json, os, secrets, sys, time, urllib.request
import psycopg2
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from playwright.sync_api import sync_playwright
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
API=os.environ.get('API_BASE','http://localhost:8080'); UI=os.environ.get('UI_BASE','http://localhost:5173')
conn=psycopg2.connect(host='192.168.3.10',port=5432,dbname='agent_platform',user='app_user',password=os.environ['DB_PASSWORD'])
def call(p,m='GET',b=None,t=None,timeout=120):
    d=json.dumps(b).encode() if b is not None else None
    r=urllib.request.Request(API+p,data=d,method=m); r.add_header('Content-Type','application/json')
    if t: r.add_header('Authorization','Bearer '+t)
    try:
        with urllib.request.urlopen(r,timeout=timeout) as x: return json.loads(x.read().decode())
    except urllib.error.HTTPError as e: return json.loads(e.read().decode())
S='tk-'+secrets.token_hex(4)
cap=call(f'/api/auth/captcha?sessionKey={S}&mode=slider')['data']
cur=conn.cursor(); conn.commit(); cur.execute('select answer_x from captcha_challenge where id=%s',(cap['id'],)); ax=cur.fetchone()[0]; cur.close(); conn.commit()
track=[[round(min(0.85,i*0.04+(i%3)*0.004),4),16*i+(i*7)%19] for i in range(22)]
v=call('/api/auth/captcha/verify','POST',{'id':cap['id'],'sessionKey':S,'x':ax,'track':track})['data']
key=call('/api/auth/login-key')['data']
pub=serialization.load_pem_public_key(key['publicKey'].encode())
pl=json.dumps({'p':'Admin@123','t':int(time.time()*1000),'n':secrets.token_hex(8),'s':S}).encode()
ct=base64.b64encode(pub.encrypt(pl,padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),algorithm=hashes.SHA256(),label=None))).decode()
login=call('/api/auth/login','POST',{'username':'admin','encryptedPassword':ct,'keyId':key['keyId'],'captchaTicket':v['ticket'],'sessionKey':S})['data']
token=login['token']; conn.close()
call('/api/browser/navigate','POST',{'url':'https://example.com'},token); time.sleep(1)
before=call('/api/browser/status',t=token)['data']
print('接管前:', before.get('url'))
with sync_playwright() as p:
    b=p.chromium.launch(headless=True); page=b.new_page(viewport={'width':1600,'height':900})
    page.goto(UI+'/login', wait_until='load')
    page.evaluate("""([t,u])=>localStorage.setItem('myagent-auth', JSON.stringify({state:{token:t,user:u},version:0}))""",[token,login['user']])
    page.goto(UI+'/chat', wait_until='load'); page.wait_for_timeout(3500)
    page.screenshot(path='logs/tk-1-open.png')
    # 点「我要操作」
    page.get_by_role('button', name='我要操作').click()
    page.wait_for_timeout(1200)
    st=call('/api/browser/status',t=token)['data']
    print('接管状态 userControlled =', st.get('userControlled'))
    canvas=page.locator('[data-testid="browser-canvas"]')
    box=canvas.bounding_box(); print('canvas box', box)
    # Learn more 在页面底部中间偏左 → 用比例点它
    canvas.click(position={'x': box['width']*0.5, 'y': box['height']*0.97})
    page.wait_for_timeout(2500)
    after=call('/api/browser/status',t=token)['data']
    print('点击后 URL:', after.get('url'))
    page.screenshot(path='logs/tk-2-after-click.png')
    b.close()
print('用户接管点击生效' if (after.get('url') or '').find('iana.org')>=0 else '（URL 未变，需人工确认点击位置）')
