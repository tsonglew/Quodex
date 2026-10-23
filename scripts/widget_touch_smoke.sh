#!/usr/bin/env bash
# Repeat the actual RemoteViews touch test on the owned disposable emulator.
set -euo pipefail
[[ "$(adb -e shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
mkdir -p screenshots/widget-touch
for attempt in 1 2 3 4 5; do
  report="screenshots/widget-touch/attempt-$attempt.txt"
  python3 scripts/bounded_command.py 120 adb -e shell am instrument -r -w \
    -e class 'com.tsonglew.quodex.WidgetRefreshActionDeviceTest#realRemoteViewsClickFetchesOnceForThreeWidgets' \
    com.tsonglew.quodex.test/com.tsonglew.quodex.QuotaTestRunner > "$report" 2>&1
  cat "$report"
  grep -q '^OK (1 test)' "$report"
  grep -q '^INSTRUMENTATION_CODE: -1' "$report"
done
