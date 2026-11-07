"""Initialize only a missing password-reset URL; preserve all existing mail secrets."""
from pathlib import Path
import sys
from urllib.parse import urlsplit, urljoin

def initialize_reset_url(path, public_url):
    path = Path(path)
    text = path.read_text()
    key = 'LIT_PASSWORD_RESET_URL'
    values = [line.split('=', 1)[1].strip().strip('\"\'') for line in text.splitlines() if line.startswith(key + '=')]
    if any(values):
        return False
    parsed = urlsplit(public_url)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('A public HTTPS origin is required')
    lines = [line for line in text.splitlines() if not line.startswith(key + '=')]
    path.write_text('\n'.join(lines) + '\n' + key + '=' + urljoin(public_url, '/reset-password') + '\n')
    return True

if __name__ == '__main__':
    changed = initialize_reset_url(sys.argv[1], sys.argv[2])
    print('Password reset URL initialized' if changed else 'Explicit password reset URL preserved')
