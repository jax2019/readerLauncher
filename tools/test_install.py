"""安装策略的离线回归，不连接设备、不修改默认桌面。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('installer', Path(__file__).with_name('install.py'))
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class InstallTests(unittest.TestCase):
    def test_device_profiles(self):
        self.assertEqual(installer.device_profile('MiDuoKanReaderProII', '30'), 'duokan')
        self.assertEqual(installer.device_profile('Neo 3 Ultra', '34'), 'ireader')
        for model, sdk in [('MiDuoKanReaderProII', '34'), ('unknown', '30'), ('Neo 3 Ultra', '30')]:
            with self.assertRaises(RuntimeError):
                installer.device_profile(model, sdk)

    def test_serial_with_slash_stays_in_backup_directory(self):
        local = Path('/tmp/local')
        path = installer.backup_path(local, '37409/CJAGQF2W101166')
        self.assertEqual(path.parent, local)
        self.assertNotEqual(path, installer.backup_path(local, '37409_CJAGQF2W101166'))

    def test_legacy_backup_and_new_backup_precedence(self):
        with tempfile.TemporaryDirectory() as temp:
            local = Path(temp)
            old = {'home': 'com.zhangyue.iReader.Eink/.home.ActivityLauncher'}
            (local / 'device-IREADER.json').write_text(json.dumps(old))
            self.assertEqual(installer.read_backup(local, 'IREADER'), old)
            new = {'serial': 'IREADER', 'home': 'com.example/.Home'}
            installer.backup_path(local, 'IREADER').write_text(json.dumps(new))
            self.assertEqual(installer.read_backup(local, 'IREADER'), new)

    def test_missing_or_wrong_backup(self):
        with tempfile.TemporaryDirectory() as temp:
            local = Path(temp)
            self.assertIsNone(installer.read_backup(local, 'device/serial'))
            path = installer.backup_path(local, 'device/serial')
            for data in [{'serial': 'other', 'home': 'com.example/.Home'},
                         {'home': 'com.ireaderlauncher.app/.MainActivity'},
                         {'home': 'com.example/.Home;reboot'}]:
                path.write_text(json.dumps(data))
                with self.assertRaises(RuntimeError):
                    installer.read_backup(local, 'device/serial')

    def test_resolved_home(self):
        self.assertEqual(installer.resolved_component('priority=0\ncom.duokan.einkreader/com.duokan.home.DkHomeActivity\n'),
                         'com.duokan.einkreader/com.duokan.home.DkHomeActivity')
        self.assertIsNone(installer.resolved_component('No activity found'))

    def exercise_install(self, model, sdk, serial, restore=False, saved=None, valid_cert=True):
        """模拟 ADB 与证书解析，检查真正的安装分支，而不调用真实设备。"""
        calls = []
        current_home = ['com.duokan.einkreader/com.duokan.home.DkHomeActivity']
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'output').mkdir()
            for apk in ('IReaderLauncher.apk', 'IReaderPowerBridge.apk'):
                (root / 'output' / apk).touch()
            if saved:
                (root / '.local').mkdir()
                installer.backup_path(root / '.local', serial).write_text(json.dumps(saved))

            def output(args, **kwargs):
                calls.append(args)
                if args == ['adb', 'devices']:
                    return 'List of devices attached\n' + serial + '\tdevice\n'
                if 'exec-out' in args:
                    return b'certificate container'
                command = args[4:]
                if command[:1] == ['getprop']:
                    return {'ro.product.model': model, 'ro.build.version.sdk': sdk,
                            'ro.build.fingerprint': 'test/fingerprint'}[command[1]]
                if command[:3] == ['cmd', 'package', 'resolve-activity']:
                    return (command[command.index('-n') + 1] if '-n' in command else current_home[0]) + '\n'
                if command[:3] == ['cmd', 'package', 'set-home-activity']:
                    current_home[0] = command[-1]
                return 'Success\n'

            def run(args, **kwargs):
                calls.append(args)
                return SimpleNamespace(stdout=b'test certificate' if args[0] == 'openssl' and args[1] == 'x509' else b'pem')

            expected = installer.hashlib.sha256(b'test certificate').hexdigest() if valid_cert else 'mismatch'
            argv = ['install.py'] + (['--restore-home'] if restore else [])
            with patch.object(installer, 'ROOT', root), patch.object(installer, 'EXPECTED_CERT', expected), \
                    patch.object(installer.subprocess, 'check_output', side_effect=output), \
                    patch.object(installer.subprocess, 'run', side_effect=run), patch('sys.argv', argv), redirect_stdout(StringIO()):
                installer.main()
            record = installer.read_backup(root / '.local', serial)
        return calls, record

    def test_duokan_installs_only_launcher(self):
        calls, record = self.exercise_install('MiDuoKanReaderProII', '30', '37409/CJAGQF2W101166')
        installs = [args for args in calls if 'install' in args]
        self.assertEqual(len(installs), 1)
        self.assertTrue(installs[0][-1].endswith('IReaderLauncher.apk'))
        self.assertFalse(any('exec-out' in args or args[0] == 'openssl' for args in calls))
        self.assertEqual(record['profile'], 'duokan')
        self.assertEqual(record['home'], 'com.duokan.einkreader/com.duokan.home.DkHomeActivity')
        role_changes = [args for args in calls if 'add-role-holder' in args]
        self.assertEqual(role_changes[0][-1], 'com.ireaderlauncher.app')
        self.assertTrue(any('android.permission.WRITE_SECURE_SETTINGS' in args for args in calls))
        self.assertTrue(any('WRITE_SETTINGS' in args and 'allow' in args for args in calls))

    def test_ireader_keeps_certificate_check_and_two_apks(self):
        calls, record = self.exercise_install('Neo 3 Ultra', '34', 'IREADER')
        self.assertEqual(len([args for args in calls if 'install' in args]), 2)
        self.assertTrue(any('exec-out' in args for args in calls))
        self.assertEqual(record['profile'], 'ireader')
        self.assertFalse(any('android.permission.WRITE_SECURE_SETTINGS' in args for args in calls))
        with self.assertRaisesRegex(RuntimeError, '系统签名'):
            self.exercise_install('Neo 3 Ultra', '34', 'IREADER', valid_cert=False)

    def test_restore_uses_backup_and_requires_it(self):
        saved = {'home': 'com.example/.OriginalHome', 'serial': 'DUOKAN'}
        calls, record = self.exercise_install('MiDuoKanReaderProII', '30', 'DUOKAN', restore=True, saved=saved)
        changes = [args for args in calls if 'set-home-activity' in args]
        self.assertEqual(changes[0][-1], saved['home'])
        self.assertEqual([args for args in calls if 'add-role-holder' in args][0][-1], 'com.example')
        self.assertFalse(any('install' in args for args in calls))
        with self.assertRaisesRegex(RuntimeError, '未找到此设备'):
            self.exercise_install('MiDuoKanReaderProII', '30', 'DUOKAN', restore=True)


if __name__ == '__main__':
    unittest.main()
