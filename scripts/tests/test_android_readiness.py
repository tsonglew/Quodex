import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('wait_for_android', Path(__file__).parents[1] / 'wait_for_android.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class AndroidReadinessTest(unittest.TestCase):
    def response(self, command, **_kwargs):
        args = command[3:]
        output = '1' if args[0] == 'getprop' else ''
        if args[0] == 'service':
            output = f'Service {args[2]}: found'
        if args[0] == 'pm':
            output = 'package:/system/framework/framework-res.apk'
        return subprocess.CompletedProcess(command, 0, output, '')

    def test_boot_property_does_not_hide_missing_framework_service(self):
        def missing(command, **kwargs):
            result = self.response(command, **kwargs)
            if command[-3:] == ['service', 'check', 'input']:
                result.stdout = 'Service input: not found'
            return result
        with patch.object(module.subprocess, 'run', side_effect=missing):
            self.assertFalse(module.ready('adb'))

    def test_registered_service_with_broken_pipe_is_not_ready(self):
        def broken(command, **kwargs):
            result = self.response(command, **kwargs)
            if command[-3:] == ['input', 'keyevent', 'KEYCODE_WAKEUP']:
                result.returncode = 224
                result.stderr = 'Failure calling service input: Broken pipe (32)'
            return result
        with patch.object(module.subprocess, 'run', side_effect=broken):
            self.assertFalse(module.ready('adb'))

    def test_working_framework_commands_are_ready(self):
        with patch.object(module.subprocess, 'run', side_effect=self.response):
            self.assertTrue(module.ready('adb'))

    def test_configuration_requires_real_unlock_and_settings_commands(self):
        for broken_tail in (['input', 'keyevent', 'KEYCODE_WAKEUP'], ['wm', 'dismiss-keyguard'],
                            ['settings', 'put', 'global', 'animator_duration_scale', '0']):
            def broken(command, **kwargs):
                result = self.response(command, **kwargs)
                if command[-len(broken_tail):] == broken_tail:
                    result.returncode = 224
                return result
            with self.subTest(command=broken_tail), patch.object(module.subprocess, 'run', side_effect=broken):
                self.assertFalse(module.ready('adb', configure=True))
        with patch.object(module.subprocess, 'run', side_effect=self.response):
            self.assertTrue(module.ready('adb', configure=True))
