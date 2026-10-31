#!/usr/bin/env python3
"""把 browser-extension/ 打成内测 zip（deploy/artifacts/my-agent-extension.zip）。

为什么用 Python 打包而不是 npm/vite：扩展是纯 ES module（MV3 直接支持），
不需要构建步骤 —— 服务器和用户都能直接"加载已解压的扩展程序"，打包只为分发方便。
"""
import hashlib
import os
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'browser-extension')
OUT_DIR = os.path.join(ROOT, 'deploy', 'artifacts')
OUT = os.path.join(OUT_DIR, 'my-agent-extension.zip')

INCLUDE = ['manifest.json', 'sidepanel.html', 'onboarding.html', 'README.md']
INCLUDE_DIRS = ['src']


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    if os.path.exists(OUT):
        os.remove(OUT)
    with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as zf:
        for name in INCLUDE:
            zf.write(os.path.join(SRC, name), name)
        for folder in INCLUDE_DIRS:
            base = os.path.join(SRC, folder)
            for dirpath, _, files in os.walk(base):
                for filename in files:
                    full = os.path.join(dirpath, filename)
                    zf.write(full, os.path.relpath(full, SRC))
    data = open(OUT, 'rb').read()
    print('打包完成：%s（%d KB）' % (OUT, len(data) // 1024))
    print('SHA-256：%s' % hashlib.sha256(data).hexdigest())


if __name__ == '__main__':
    main()
