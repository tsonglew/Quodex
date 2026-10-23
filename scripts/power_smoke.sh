#!/usr/bin/env bash
# Owned emulator only. Uses the intercepted synthetic lifecycle fixture.
set -euo pipefail
adb_binary=$(command -v adb)
adb() { python3 scripts/bounded_command.py 90 "$adb_binary" "$@"; }
output_dir=${POWER_OUTPUT_DIR:-screenshots/power}
mkdir -p "$output_dir"
package=com.tsonglew.quodex
runner=com.tsonglew.quodex.test/com.tsonglew.quodex.QuotaTestRunner
test_class=com.tsonglew.quodex.LifecycleDeviceTest
original_wifi=$(adb -e shell settings get global wifi_on | tr -d '\r')
original_data=$(adb -e shell settings get global mobile_data | tr -d '\r')
original_deep=$(adb -e shell dumpsys deviceidle enabled deep | tr -d '\r')
original_screen=$(adb -e shell dumpsys deviceidle get screen | tr -d '\r')
original_saver=$(adb -e shell settings get global low_power | tr -d '\r')
original_op=$(adb -e shell cmd appops get "$package" RUN_ANY_IN_BACKGROUND | tr -d '\r')
# Refuse to overwrite a pre-existing restriction or active forced-idle experiment.
[[ "$original_saver" == 0 || "$original_saver" == null ]]
[[ "$original_op" == *'No operations.'* || "$original_op" == *'RUN_ANY_IN_BACKGROUND: allow'* ]]
[[ "$(adb -e shell dumpsys deviceidle get deep | tr -d '\r')" == ACTIVE ]]
restore() {
  adb -e shell dumpsys deviceidle unforce >/dev/null || true
  if [[ "$original_deep" == 0 ]]; then adb -e shell dumpsys deviceidle disable deep >/dev/null || true; fi
  if [[ "$original_screen" == true ]]; then adb -e shell input keyevent KEYCODE_WAKEUP >/dev/null || true; fi
  adb -e shell cmd power set-mode 0 >/dev/null || true
  # AOSP's unrestricted default behavior is MODE_ALLOWED; explicit "default"
  # can leave JobScheduler readyNotRestrictedInBg=false on API 31.
  adb -e shell cmd appops set "$package" RUN_ANY_IN_BACKGROUND allow >/dev/null || true
  adb -e shell dumpsys battery reset >/dev/null || true
  if [[ "$original_saver" == null ]]; then adb -e shell settings delete global low_power >/dev/null || true; fi
}
finish() {
  restore
  # A failure can leave the synthetic fixture connected. Keep it stopped before
  # allowing OS-launched production processes to see the network again.
  adb -e shell am force-stop "$package" >/dev/null || true
  if [[ "$original_wifi" == 1 ]]; then adb -e shell svc wifi enable >/dev/null || true; fi
  if [[ "$original_data" == 1 ]]; then adb -e shell svc data enable >/dev/null || true; fi
}
trap finish EXIT
# Instrumentation intercepts requests, but a later OS-launched process does not.
# Isolate the emulator for the entire fixture lifetime as a second boundary.
adb -e shell svc wifi disable
adb -e shell svc data disable
phase() {
  adb -e shell am instrument -r -w -e class "$test_class" -e lifecyclePhase "$2" "$runner" > "$output_dir/$1-$2.txt"
  cat "$output_dir/$1-$2.txt"
  grep -q '^OK (1 test)' "$output_dir/$1-$2.txt"
  grep -q '^INSTRUMENTATION_CODE: -1' "$output_dir/$1-$2.txt"
}
for mode in doze saver restricted; do
  phase "$mode" seed
  adb -e shell input keyevent KEYCODE_HOME
  adb -e shell dumpsys battery unplug >/dev/null
  case "$mode" in
    doze)
      adb -e shell dumpsys deviceidle enable deep >/dev/null
      adb -e shell input keyevent KEYCODE_SLEEP
      adb -e shell dumpsys deviceidle force-idle > "$output_dir/doze-state.txt"
      [[ "$(adb -e shell dumpsys deviceidle get deep | tr -d '\r')" == IDLE ]]
      ;;
    saver)
      adb -e shell cmd power set-mode 1
      [[ "$(adb -e shell settings get global low_power | tr -d '\r')" == 1 ]]
      adb -e shell dumpsys power > "$output_dir/saver-state.txt"
      ;;
    restricted)
      adb -e shell cmd appops set "$package" RUN_ANY_IN_BACKGROUND ignore
      adb -e shell cmd appops get "$package" RUN_ANY_IN_BACKGROUND > "$output_dir/restricted-state.txt"
      grep -q 'ignore' "$output_dir/restricted-state.txt"
      ;;
  esac
  sleep 5
  case "$mode" in
    doze) [[ "$(adb -e shell dumpsys deviceidle get deep | tr -d '\r')" == IDLE ]] ;;
    saver) [[ "$(adb -e shell settings get global low_power | tr -d '\r')" == 1 ]] ;;
    restricted) adb -e shell cmd appops get "$package" RUN_ANY_IN_BACKGROUND | grep -q 'ignore' ;;
  esac
  adb -e shell dumpsys jobscheduler "$package" > "$output_dir/$mode-jobs.txt"
  if [[ "$mode" == doze ]]; then
    grep -q 'readyNotDozing: false' "$output_dir/$mode-jobs.txt"
  fi
  if [[ "$mode" == restricted ]]; then
    grep -q 'readyNotRestrictedInBg: false' "$output_dir/$mode-jobs.txt"
  fi
  restore
  restored_op=$(adb -e shell cmd appops get "$package" RUN_ANY_IN_BACKGROUND | tr -d '\r')
  [[ "$restored_op" == *'No operations.'* || "$restored_op" == *'RUN_ANY_IN_BACKGROUND: allow'* ]]
  # Process replacement gives a real disk/Keystore/cache recovery check.
  adb -e shell am force-stop "$package"
  phase "$mode" restore
done
