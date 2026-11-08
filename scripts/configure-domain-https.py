"""Add the approved LIT hostname to the existing IP certificate and site."""
from pathlib import Path
import fcntl
import os
import re
import shutil
import socket
import subprocess
import time
import urllib.request

DOMAIN = 'llkj.itslit.r-e.kr'
IP = '3.25.143.224'
ORIGIN = 'https://' + DOMAIN


def update_nginx(text):
    if text.count('# LIT_WEB_SPA_V1') != 1:
        raise ValueError('Expected the existing single LIT SPA configuration')
    pattern = r'(?m)^(\s*server_name\s+)' + re.escape(IP) + r'(?: ' + re.escape(DOMAIN) + r')?;'
    result, count = re.subn(pattern, lambda m: m.group(1) + IP + ' ' + DOMAIN + ';', text)
    if count != 2:
        raise ValueError('Expected exactly the existing HTTP and HTTPS LIT servers')
    if '/etc/letsencrypt/live/lit-api-ip/fullchain.pem' not in text:
        raise ValueError('Unexpected certificate lineage')
    return result


def update_environment(text):
    lines = text.splitlines()
    replacements = {
        'LIT_JOIN_BASE_URL': ORIGIN + '/student/join',
        'LIT_PASSWORD_RESET_URL': ORIGIN + '/reset-password',
    }
    key = 'LIT_CORS_ALLOWED_ORIGINS'
    old = [line.split('=', 1)[1].strip().strip('\"\'') for line in lines if line.startswith(key + '=')]
    if len(old) > 1:
        raise ValueError('Duplicate CORS configuration')
    origins = [value.strip() for value in (old[0].split(',') if old else []) if value.strip()]
    if ORIGIN not in origins:
        origins.append(ORIGIN)
    replacements[key] = ','.join(origins)
    result = [line for line in lines if line.partition('=')[0] not in replacements]
    result.extend(key + '=' + value for key, value in replacements.items())
    return '\n'.join(result) + '\n'


def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def write_atomic(path, text):
    temporary = path.with_name('.' + path.name + '.domain-tmp')
    stat = path.stat()
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w') as file:
            file.write(text)
            file.flush()
            os.fsync(file.fileno())
        os.chown(temporary, stat.st_uid, stat.st_gid)
        os.chmod(temporary, stat.st_mode & 0o777)
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def verify_https():
    for host in (DOMAIN, IP):
        with urllib.request.urlopen('https://' + host + '/actuator/health/readiness', timeout=15) as response:
            if response.status != 200 or b'"UP"' not in response.read():
                raise RuntimeError('HTTPS readiness failed for ' + host)


def configure_domain():
    if os.geteuid() != 0:
        raise SystemExit('Run with sudo')
    with open('/opt/lit/.domain-https.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if set(socket.gethostbyname_ex(DOMAIN)[2]) != {IP}:
            raise RuntimeError('Domain must resolve only to the approved production IP')
        site = Path('/etc/nginx/sites-enabled/lit').resolve(strict=True)
        environment = Path('/opt/lit/app.env')
        original_site = site.read_text()
        original_environment = environment.read_text()
        configured_site = update_nginx(original_site)
        configured_environment = update_environment(original_environment)
        run('nginx', '-t')
        backup = Path('/opt/lit/backups') / ('domain-https-' + time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()))
        backup.mkdir(mode=0o700, parents=True)
        os.chmod(backup, 0o700)
        for source, name in ((site, 'nginx.conf'), (environment, 'app.env'), (Path('/etc/letsencrypt/renewal/lit-api-ip.conf'), 'renewal.conf')):
            destination = backup / name
            shutil.copyfile(source, destination)
            os.chmod(destination, 0o600)
        print('Configuration backup:', backup, flush=True)
        run('docker', 'run', '--rm', '--pull=never',
            '-v', '/etc/letsencrypt:/etc/letsencrypt',
            '-v', '/var/lib/letsencrypt:/var/lib/letsencrypt',
            '-v', '/var/www/lit-acme:/var/www/lit-acme',
            'certbot/certbot:latest', 'certonly', '--non-interactive', '--expand',
            '--cert-name', 'lit-api-ip', '--preferred-profile', 'shortlived',
            '--webroot', '-w', '/var/www/lit-acme', '--ip-address', IP, '-d', DOMAIN)
        certificate = '/etc/letsencrypt/live/lit-api-ip/cert.pem'
        run('openssl', 'x509', '-in', certificate, '-noout', '-checkhost', DOMAIN)
        run('openssl', 'x509', '-in', certificate, '-noout', '-checkip', IP)
        names = subprocess.check_output(['openssl', 'x509', '-in', certificate, '-noout', '-ext', 'subjectAltName'], text=True)
        if 'DNS:' + DOMAIN not in names or 'IP Address:' + IP not in names:
            raise RuntimeError('Certificate must retain both approved identifiers')
        compose = ['docker', 'compose', '-f', '/opt/lit/compose.yml', '--env-file', '/opt/lit/.release.env',
                   'up', '-d', '--no-deps', '--pull', 'never', '--wait', '--wait-timeout', '150', 'app']
        try:
            write_atomic(site, configured_site)
            run('nginx', '-t')
            run('systemctl', 'reload', 'nginx')
            verify_https()
            if configured_environment != original_environment:
                write_atomic(environment, configured_environment)
                run(*compose, cwd='/opt/lit')
            verify_https()
            run('systemctl', 'enable', '--now', 'lit-cert-renew.timer')
            run('systemctl', 'is-active', 'lit-cert-renew.timer')
            print('Domain HTTPS and application URLs configured:', ORIGIN, flush=True)
        except Exception:
            write_atomic(environment, original_environment)
            run(*compose, cwd='/opt/lit')
            write_atomic(site, original_site)
            run('nginx', '-t')
            run('systemctl', 'reload', 'nginx')
            raise


if __name__ == '__main__':
    configure_domain()
