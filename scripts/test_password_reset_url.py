import importlib.util
from pathlib import Path
import tempfile
import unittest
spec = importlib.util.spec_from_file_location('reset_url', Path(__file__).with_name('configure-password-reset-url.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ResetUrlTest(unittest.TestCase):
    def test_only_missing_or_empty_setting_is_initialized_and_secrets_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'app.env'
            for original in ['SMTP_PASSWORD=private\n', 'SMTP_PASSWORD=private\nLIT_PASSWORD_RESET_URL=\n']:
                path.write_text(original)
                self.assertTrue(module.initialize_reset_url(path, 'https://example.test'))
                self.assertIn('SMTP_PASSWORD=private\n', path.read_text())
                self.assertIn('LIT_PASSWORD_RESET_URL=https://example.test/reset-password\n', path.read_text())
                self.assertFalse(module.initialize_reset_url(path, 'https://other.test'))
            path.write_text('SMTP_PASSWORD=private\n')
            with self.assertRaises(ValueError):
                module.initialize_reset_url(path, 'http://example.test')
            self.assertEqual(path.read_text(), 'SMTP_PASSWORD=private\n')
