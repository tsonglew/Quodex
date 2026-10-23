#!/usr/bin/env bash
# Explicit owned-emulator probe; synthetic traffic is intercepted in the runner.
set -euo pipefail
adb_binary=$(command -v adb)
adb() { python3 scripts/bounded_command.py 30 "$adb_binary" "$@"; }
[[ "$(adb -e shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
package_details=$(adb -e shell dumpsys package com.tsonglew.quodex)
[[ "$package_details" == *DEBUGGABLE* ]]
finish() {
  result=$?
  trap - EXIT
  adb -e shell am force-stop com.tsonglew.quodex >/dev/null || true
  if [[ "$result" -ne 0 ]]; then
    # A timed-out instrumentation may not execute its fixture cleanup.
    adb -e shell pm clear com.tsonglew.quodex >/dev/null || true
  fi
  exit "$result"
}
trap finish EXIT
mkdir -p screenshots
python3 scripts/bounded_command.py 360 "$adb_binary" -e shell am instrument -r -w \
  -e class com.tsonglew.quodex.ScheduledRetryDeviceTest -e scheduledRetryProbe true \
  com.tsonglew.quodex.test/com.tsonglew.quodex.QuotaTestRunner > screenshots/scheduled-retry-probe.txt
cat screenshots/scheduled-retry-probe.txt
grep -q '^OK (1 test)' screenshots/scheduled-retry-probe.txt
grep -q '^INSTRUMENTATION_STATUS_CODE: 0' screenshots/scheduled-retry-probe.txt
grep -q '^INSTRUMENTATION_CODE: -1' screenshots/scheduled-retry-probe.txt
adb -e logcat -d -s System.out:I | grep 'SCHEDULED_RETRY backoffMillis=' > screenshots/scheduled-retry-timing.txt
cat screenshots/scheduled-retry-timing.txt
