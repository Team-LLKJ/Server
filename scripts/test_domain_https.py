import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('domain_https', Path(__file__).with_name('configure-domain-https.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DomainHttpsTest(unittest.TestCase):
    def test_site_change_preserves_routes_and_is_idempotent(self):
        original = 'server {\n server_name 3.25.143.224;\n}\nserver {\n server_name 3.25.143.224;\n ssl_certificate /etc/letsencrypt/live/lit-api-ip/fullchain.pem;\n # LIT_WEB_SPA_V1\n location / { try_files $uri /index.html; }\n}\n'
        result = module.update_nginx(original)
        self.assertEqual(result.count('3.25.143.224 llkj.itslit.r-e.kr;'), 2)
        self.assertIn('try_files $uri /index.html;', result)
        self.assertEqual(module.update_nginx(result), result)
        self.assertEqual(result.count('# LIT_WEB_SPA_V1'), 1)

    def test_unexpected_site_is_rejected(self):
        with self.assertRaises(ValueError):
            module.update_nginx('server_name unrelated.test;')

    def test_environment_retains_secrets_and_existing_origins(self):
        original = 'MAIL_PASSWORD=unchanged-test-value\nLIT_CORS_ALLOWED_ORIGINS=\'http://localhost:5173\'\nLIT_PASSWORD_RESET_URL=https://3.25.143.224/reset-password\n'
        result = module.update_environment(original)
        self.assertIn('MAIL_PASSWORD=unchanged-test-value\n', result)
        self.assertIn('http://localhost:5173,https://llkj.itslit.r-e.kr', result)
        self.assertIn('LIT_JOIN_BASE_URL=https://llkj.itslit.r-e.kr/student/join', result)
        self.assertIn('LIT_PASSWORD_RESET_URL=https://llkj.itslit.r-e.kr/reset-password', result)
        self.assertEqual(module.update_environment(result), result)

    def test_duplicate_origins_are_rejected(self):
        with self.assertRaises(ValueError):
            module.update_environment('LIT_CORS_ALLOWED_ORIGINS=a\nLIT_CORS_ALLOWED_ORIGINS=b\n')
