#!/usr/bin/env python3
"""Run the full suite with native adb installation and validate runner statuses."""
import re
import subprocess
from pathlib import Path


def verify(report: str) -> int:
    codes = [int(value) for value in re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)$', report, re.M)]
    passed = codes.count(0)
    if (passed < 18 or any(code not in (1, 0, -3, -4) for code in codes)
            or not re.search(r'^INSTRUMENTATION_CODE: -1\s*$', report, re.M)
            or 'INSTRUMENTATION_FAILED' in report or 'FAILURES!!!' in report
            or codes.count(1) != len(codes) - codes.count(1)):
        raise ValueError(f'Incomplete or failed device suite ({passed} passed)')
    return passed


def run_instrumentation(path: Path) -> None:
    path.parent.mkdir(exist_ok=True)
    try:
        with path.open('w') as output:
            # The full 85-test Android 17 suite can reach its last test at 600s.
            # Keep a bounded runtime within the 30-minute job, without rerunning failures.
            result = subprocess.run(['adb', '-e', 'shell', 'am', 'instrument', '-r', '-w',
                                     'com.tsonglew.quodex.test/com.tsonglew.quodex.QuotaTestRunner'],
                                    stdout=output, stderr=subprocess.STDOUT, timeout=900)
    finally:
        # Preserve partial runner statuses in the job log even if adb times out.
        print(path.read_text())
    report = path.read_text()
    result.check_returncode()
    print(f'Native device suite: {verify(report)} actual tests passed')


def main() -> None:
    for apk in ('debug/app-debug.apk', 'androidTest/debug/app-debug-androidTest.apk'):
        subprocess.run(['adb', '-e', 'install', '-r', f'app/build/outputs/apk/{apk}'], check=True, timeout=120)
    run_instrumentation(Path('screenshots/native-instrumentation.txt'))


if __name__ == '__main__':
    main()
