import importlib.util
from pathlib import Path
import unittest
import tempfile
import subprocess
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('report_mjs', Path(__file__).with_name('configure-report-mjs-nginx.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class MjsConfigTest(unittest.TestCase):
    def test_inserts_only_asset_mime_and_is_idempotent(self):
        source = 'server {\n# LIT_WEB_SPA_V1\nlocation ^~ /api/ { proxy_pass http://127.0.0.1:5174; }\nlocation /assets/ { root /var/www/lit-web/current; try_files $uri =404; }\n}'
        result = module.render(source)
        self.assertIn('types { text/javascript mjs; }', result)
        line = next(line for line in result.splitlines() if 'location ~' in line)
        self.assertEqual(line.count(chr(92)), 1)
        self.assertIn('location ^~ /api/ { proxy_pass http://127.0.0.1:5174; }', result)
        self.assertIn('location /assets/ { root /var/www/lit-web/current;', result)
        self.assertEqual(result, module.render(result))
    def test_unknown_configs_are_not_modified(self):
        for source in ('server {}', '# LIT_WEB_SPA_V1\nroot /var/www/lit-web/current;\nlocation ^~ /assets/ {}'):
            with self.assertRaises(ValueError):
                module.render(source)

    def test_nginx_validation_failure_restores_original(self):
        source = '# LIT_WEB_SPA_V1\nlocation /assets/ { root /var/www/lit-web/current; }\n'
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'site'
            path.write_text(source)
            failure = subprocess.CalledProcessError(1, ['nginx', '-t'])
            with patch.object(module.subprocess, 'run', side_effect=[failure, None, None]):
                with self.assertRaises(subprocess.CalledProcessError):
                    module.apply(directory)
            self.assertEqual(path.read_text(), source)
