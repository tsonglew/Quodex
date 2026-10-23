#!/usr/bin/env bash
# Android 17 can expose boot_completed before framework services accept commands.
set -euo pipefail
: "${ANDROID_HOME:?}" "${DEVICE_API:?}" "${DEVICE_TARGET:?}"
mkdir -p screenshots
emulator_pid=''
cleanup() {
  result=$?
  trap - EXIT
  # A dead/offline emulator can leave adb waiting indefinitely even for logcat.
  python3 scripts/bounded_command.py 10 adb -e logcat -d > screenshots/emulator-logcat.txt 2>&1 || true
  python3 scripts/bounded_command.py 10 adb -e logcat -d -b crash -t 200 > screenshots/emulator-crashes.txt 2>&1 || true
  cat screenshots/emulator-crashes.txt
  python3 scripts/bounded_command.py 5 adb -e emu kill >/dev/null 2>&1 || true
  if [[ -n "$emulator_pid" ]]; then
    kill "$emulator_pid" 2>/dev/null || true
    sleep 2
    kill -KILL "$emulator_pid" 2>/dev/null || true
    wait "$emulator_pid" || true
  fi
  if [[ -f screenshots/emulator-startup.txt ]]; then cat screenshots/emulator-startup.txt; fi
  exit "$result"
}
trap cleanup EXIT
for install_attempt in 1 2 3; do
  echo "Installing Android emulator packages (attempt $install_attempt/3)"
  if sdkmanager --install platform-tools emulator "system-images;android-${DEVICE_API};${DEVICE_TARGET};x86_64"; then break; fi
  if [[ "$install_attempt" == 3 ]]; then exit 1; fi
  sleep "$((install_attempt * 2))"
done
export PATH="$ANDROID_HOME/platform-tools:$PATH"
adb version
emulator_binary="$ANDROID_HOME/emulator/emulator"
avd_root=$(mktemp -d "${RUNNER_TEMP:-/tmp}/quodex-avd.XXXXXX")
export ANDROID_USER_HOME="$avd_root"
export ANDROID_EMULATOR_HOME="$avd_root"
export ANDROID_AVD_HOME="$avd_root/avd"
mkdir -p "$ANDROID_AVD_HOME"
printf 'no\n' | avdmanager create avd --force -n quodex-ci \
  --package "system-images;android-${DEVICE_API};${DEVICE_TARGET};x86_64" --device pixel_6 \
  --path "$ANDROID_AVD_HOME/quodex-ci.avd"
test -s "$ANDROID_AVD_HOME/quodex-ci.ini"
# Emulator target parsing expects an integer major API. Decimal SDK package
# revisions can otherwise fall back to API 3 and disable required memory paths.
case "$DEVICE_API" in 37.0|37.2) ;; *) echo "Unexpected Android 17 image: $DEVICE_API" >&2; exit 1 ;; esac
cp "$ANDROID_AVD_HOME/quodex-ci.ini" screenshots/avd-root-original.ini
sed -i 's/^target=.*/target=android-37/' "$ANDROID_AVD_HOME/quodex-ci.ini"
grep -q '^target=android-37$' "$ANDROID_AVD_HOME/quodex-ci.ini"
cp "$ANDROID_AVD_HOME/quodex-ci.ini" screenshots/avd-root-normalized.ini
# Only host AVD metadata changes; image.sysdir still selects the exact matrix image.
grep '^image.sysdir.1=' "$ANDROID_AVD_HOME/quodex-ci.avd/config.ini" | tee screenshots/avd-image-path.txt
printf '\ndisk.dataPartition.size=4G\n' >> "$ANDROID_AVD_HOME/quodex-ci.avd/config.ini"
"$emulator_binary" -list-avds
"$emulator_binary" -avd quodex-ci -no-window -no-audio -no-boot-anim \
  -no-snapshot -partition-size 4096 -memory 4096 -cores 2 -gpu software \
  -verbose > screenshots/emulator-startup.txt 2>&1 &
emulator_pid=$!
sleep 2
if ! kill -0 "$emulator_pid" 2>/dev/null; then
  echo 'Emulator process exited before framework readiness' >&2
  exit 1
fi
python3 scripts/wait_for_android.py "$ANDROID_HOME/platform-tools/adb" 300 --configure
adb -e shell getprop ro.build.version.sdk | tr -d '\r' | tee screenshots/guest-api.txt
[[ "$(cat screenshots/guest-api.txt)" == 37 ]]
adb -e shell getconf PAGE_SIZE | tr -d '\r' | tee screenshots/guest-page-size.txt
if [[ "$DEVICE_TARGET" == google_apis_ps16k ]]; then
  [[ "$(cat screenshots/guest-page-size.txt)" == 16384 ]]
fi
adb -e shell df -h /data | tee screenshots/emulator-data-space.txt
DEVICE_NATIVE_TESTS=1 bash scripts/device_ci.sh
