import contextlib
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from scripts.native_device_tests import run_instrumentation, verify


def report(passed=18, skipped=2):
    return ''.join('INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\n' for _ in range(passed)) + ''.join(
        'INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: -3\n' for _ in range(skipped)) + 'INSTRUMENTATION_CODE: -1\n'


class NativeDeviceTests(unittest.TestCase):
    def test_timeout_preserves_partial_report_and_still_fails(self):
        partial = report().replace('INSTRUMENTATION_CODE: -1\n', '')

        def timeout(command, **kwargs):
            kwargs['stdout'].write(partial)
            raise subprocess.TimeoutExpired(command, kwargs['timeout'])

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'screenshots' / 'native-instrumentation.txt'
            output = io.StringIO()
            with patch('scripts.native_device_tests.subprocess.run', side_effect=timeout):
                with contextlib.redirect_stdout(output), self.assertRaises(subprocess.TimeoutExpired):
                    run_instrumentation(path)
            self.assertEqual(partial, path.read_text())
            self.assertIn(partial, output.getvalue())
            self.assertNotIn('actual tests passed', output.getvalue())

    def test_full_suite_with_conditional_skips(self):
        self.assertEqual(18, verify(report()))

    def test_failures_with_successful_shell_exit_rejected(self):
        with self.assertRaises(ValueError):
            verify(report() + 'INSTRUMENTATION_STATUS_CODE: -2\n')

    def test_truncated_or_insufficient_suite_rejected(self):
        for value in (report(0, 20), report(17), report().replace('INSTRUMENTATION_CODE: -1', ''),
                      report() + 'INSTRUMENTATION_STATUS_CODE: 1\n'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                verify(value)
