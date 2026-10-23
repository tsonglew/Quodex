#!/usr/bin/env bash
# Synthetic account only; the test runner intercepts all network traffic.
set -euo pipefail
output_dir=${LIFECYCLE_OUTPUT_DIR:-screenshots}
restart=${LIFECYCLE_RESTART:-force-stop}
case "$restart" in
  force-stop|reboot) ;;
  upgrade) : "${LIFECYCLE_APK:?Upgrade mode requires the new APK}" ;;
  *) echo "Unknown lifecycle restart: $restart" >&2; exit 1 ;;
esac
mkdir -p "$output_dir"
runner='com.tsonglew.quodex.test/com.tsonglew.quodex.QuotaTestRunner'
test_class='com.tsonglew.quodex.LifecycleDeviceTest#connectedCacheAndWidgetRecoverAfterExternalProcessDeath'
for phase in seed restore; do
  adb -e shell am instrument -w -e class "$test_class" -e lifecyclePhase "$phase" -e lifecycleRestart "$restart" "$runner" \
    > "$output_dir/lifecycle-${phase}.txt"
  cat "$output_dir/lifecycle-${phase}.txt"
  # am instrument may exit zero even when the test fails.
  if ! grep -q '^OK (1 test)' "$output_dir/lifecycle-${phase}.txt"; then exit 1; fi
  if [[ "$phase" == seed ]]; then
    case "$restart" in
      force-stop) adb -e shell am force-stop com.tsonglew.quodex ;;
      reboot)
        adb -e reboot
        python3 scripts/wait_for_android.py "$(command -v adb)" 300
        ;;
      upgrade) adb -e install -r "$LIFECYCLE_APK" ;;
    esac
  fi
done
