#!/usr/bin/env python3
"""把 browser-extension/ 打成内测 zip（deploy/artifacts/my-agent-extension.zip）。

为什么用 Python 而不是 npm/vite：扩展是纯 ES module（MV3 直接支持），没有构建步骤 ——
用户可以直接「加载已解压的扩展程序」，打包只为分发方便。

关键点是**把平台地址烘焙进扩展**：
  MYAGENT_PUBLIC_BASE_URL=https://agent.example.com python scripts/build-extension.py
这样用户装完不用手填地址；换了部署域名只改这个环境变量、重新打一次包即可（代码里没有写死的地址）。
"""
import argparse
import base64
import hashlib
import io
import json
import os
import re
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'browser-extension')
OUT_DIR = os.path.join(ROOT, 'deploy', 'artifacts')
OUT = os.path.join(OUT_DIR, 'my-agent-extension.zip')
# 平台地址**只从配置来**（.env 的 MYAGENT_PUBLIC_BASE_URL，或 --platform-url）。
# 故意不设默认值：避免上线真实环境还烘进内网 IP。
CONFIGURED_PLATFORM = os.environ.get('MYAGENT_PUBLIC_BASE_URL', '').strip()


def extension_id() -> str:
    path = os.path.join(SRC, 'extension-id.txt')
    if os.path.exists(path):
        return io.open(path, encoding='utf-8').read().strip()
    manifest = json.load(io.open(os.path.join(SRC, 'manifest.json'), encoding='utf-8'))
    der = base64.b64decode(manifest['key'])
    digest = hashlib.sha256(der).hexdigest()[:32]
    return ''.join(chr(ord('a') + int(c, 16)) for c in digest)


def extern_matches(platform_url: str) -> list:
    """externally_connectable：只放行这个平台（外加本地开发地址）。"""
    base = platform_url.rstrip('/')
    matches = [base + '/*']
    for dev in ('http://localhost:5173', 'http://localhost:18080', 'http://127.0.0.1:5173'):
        if dev + '/*' not in matches:
            matches.append(dev + '/*')
    return matches


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        '--platform-url',
        default=os.environ.get('MYAGENT_BROWSER_EXT_PLATFORM', CONFIGURED_PLATFORM),
    )
    args = parser.parse_args()
    platform = (args.platform_url or '').strip().rstrip('/')
    if not platform:
        raise SystemExit(
            '缺少平台地址：请在 .env 里设置 MYAGENT_PUBLIC_BASE_URL（例如 https://agent.example.com），'
            '或传 --platform-url。
'
            '（故意不提供默认值：避免把内网地址烘进要发布的扩展包里）'
        )

    # 1) 把地址写进 src/config.js（保留固定扩展 ID）
    config_path = os.path.join(SRC, 'src/config.js')
    config = io.open(config_path, encoding='utf-8').read()
    config = re.sub(r"export const DEFAULT_PLATFORM_URL = '[^']*';",
                    "export const DEFAULT_PLATFORM_URL = '%s';" % platform, config)
    io.open(config_path, 'w', encoding='utf-8', newline='\n').write(config)

    # 2) 更新 manifest 的 externally_connectable
    manifest_path = os.path.join(SRC, 'manifest.json')
    manifest = json.load(io.open(manifest_path, encoding='utf-8'))
    manifest['externally_connectable'] = {'matches': extern_matches(platform)}
    io.open(manifest_path, 'w', encoding='utf-8', newline='\n').write(
        json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')

    # 3) 打包
    os.makedirs(OUT_DIR, exist_ok=True)
    if os.path.exists(OUT):
        os.remove(OUT)
    include = ['manifest.json', 'sidepanel.html', 'onboarding.html', 'README.md', 'extension-id.txt']
    with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as zf:
        for name in include:
            full = os.path.join(SRC, name)
            if os.path.exists(full):
                zf.write(full, name)
        for dirpath, _, files in os.walk(os.path.join(SRC, 'src')):
            for filename in files:
                full = os.path.join(dirpath, filename)
                zf.write(full, os.path.relpath(full, SRC))

    data = io.open(OUT, 'rb').read()
    print('平台地址：%s' % platform)
    print('扩展 ID：%s' % extension_id())
    print('externally_connectable：%s' % ', '.join(manifest['externally_connectable']['matches']))
    print('打包完成：%s（%d KB）SHA-256=%s' % (OUT, len(data) // 1024, hashlib.sha256(data).hexdigest()))


if __name__ == '__main__':
    main()
