#!/usr/bin/env python3
"""LIT 정적 파일의 .mjs MIME만 보정. nginx 검사/재로드 실패 시 원본 복원."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile

MARKER = '# LIT_MJS_MIME_V1'

def render(text):
    if MARKER in text:
        return text
    if '# LIT_WEB_SPA_V1' not in text or '/var/www/lit-web/current' not in text:
        raise ValueError('Expected the existing LIT Web server configuration')
    pattern = re.compile(r'(?m)^[ \t]*location /assets/\s*\{')
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        raise ValueError('Expected exactly one non-^~ assets location')
    at = matches[0].start()
    block = '''    # LIT_MJS_MIME_V1
    location ~ \\.mjs$ {
        root /var/www/lit-web/current;
        types { text/javascript mjs; }
        default_type text/javascript;
        try_files $uri =404;
        add_header Cache-Control "no-cache";
    }
'''
    return text[:at] + block + text[at:]

def replace(path, data, stat):
    fd, name = tempfile.mkstemp(prefix='.lit-mjs-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
        os.chmod(name, stat.st_mode & 0o7777)
        os.chown(name, stat.st_uid, stat.st_gid)
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)

def apply(directory):
    candidates = {p.resolve() for p in Path(directory).iterdir() if p.is_file() and '# LIT_WEB_SPA_V1' in p.read_text()}
    if len(candidates) != 1:
        raise ValueError('Expected exactly one active LIT Web config; no file changed')
    path = candidates.pop()
    original = path.read_bytes()
    updated = render(original.decode()).encode()
    if updated == original:
        return
    stat = path.stat()
    replace(path, updated, stat)
    try:
        subprocess.run(['nginx', '-t'], check=True)
        subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
    except Exception:
        replace(path, original, stat)
        subprocess.run(['nginx', '-t'], check=False)
        subprocess.run(['systemctl', 'reload', 'nginx'], check=False)
        raise

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true', required=True)
    parser.add_argument('--directory', default='/etc/nginx/sites-enabled')
    args = parser.parse_args()
    apply(args.directory)
