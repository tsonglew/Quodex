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
            # The full UI suite plus cold WebView readers needs headroom on Android 17.
            # Keep the full suite bounded within the 50-minute job; never retry failures.
            result = subprocess.run(['adb', '-e', 'shell', 'am', 'instrument', '-r', '-w',
                                     'com.tsonglew.quodex.test/com.tsonglew.quodex.QuotaTestRunner'],
                                    stdout=output, stderr=subprocess.STDOUT, timeout=2100)
    finally:
        # Preserve partial runner statuses in the job log even if adb times out.
        print(path.read_text())
    report = path.read_text()
    result.check_returncode()
    print(f'Native device suite: {verify(report)} actual tests passed')


def main() -> None:
    for apk in ('debug/app-debug.apk', 'androidTest/debug/app-debug-androidTest.apk'):
        subprocess.run(['adb', '-e', 'install', '-r', f'app/build/outputs/apk/{apk}'], check=True, timeout=120)
    # These are freshly installed APKs on an owned, disposable CI emulator.
    # Compile both before timing UI assertions so cold ART work does not dominate the suite.
    for package in ('com.tsonglew.quodex', 'com.tsonglew.quodex.test'):
        subprocess.run(['adb', '-e', 'shell', 'cmd', 'package', 'compile', '-m', 'speed', '-f', package],
                       check=True, timeout=120)
    run_instrumentation(Path('screenshots/native-instrumentation.txt'))


if __name__ == '__main__':
    main()
