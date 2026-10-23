#!/usr/bin/env bash
# Owned CI emulators only. The probe restores wall clock, timezone and auto settings.
set -euo pipefail
mkdir -p screenshots
adb -e shell am instrument -r -w -e class com.tsonglew.quodex.SystemTimeDeviceTest \
  -e systemTimeProbe true com.tsonglew.quodex.test/com.tsonglew.quodex.QuotaTestRunner \
  > screenshots/system-time-probe.txt
cat screenshots/system-time-probe.txt
grep -q '^OK (1 test)' screenshots/system-time-probe.txt
grep -q '^INSTRUMENTATION_STATUS_CODE: 0' screenshots/system-time-probe.txt
grep -q '^INSTRUMENTATION_CODE: -1' screenshots/system-time-probe.txt
