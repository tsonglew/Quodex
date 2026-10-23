#!/usr/bin/env bash
# Destructive only to the test app on the single disposable Android emulator.
set -euo pipefail
script_dir="$(cd "$(dirname "$0")" && pwd)"
sdk="${ANDROID_HOME:?Android SDK required}"
adb="$sdk/platform-tools/adb"
if [[ "$("$adb" -e shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  echo 'Release smoke requires a disposable emulator.' >&2
  exit 1
fi
work_dir="$(mktemp -d)"
trap 'rm -r "$work_dir"' EXIT
tools="$sdk/build-tools/35.0.0"
export QUODEX_SMOKE_PASSWORD=quodex-disposable-test-password
keytool -genkeypair -keystore "$work_dir/test.keystore" -alias smoke -keyalg RSA -keysize 2048 -validity 1 \
  -storepass:env QUODEX_SMOKE_PASSWORD -keypass:env QUODEX_SMOKE_PASSWORD -dname 'CN=Quodex Disposable Test' >/dev/null 2>&1
"$tools/zipalign" -f -P 16 4 "${1:-app/build/outputs/apk/release/app-release-unsigned.apk}" "$work_dir/aligned.apk"
"$tools/apksigner" sign --ks "$work_dir/test.keystore" --ks-key-alias smoke \
  --ks-pass env:QUODEX_SMOKE_PASSWORD --key-pass env:QUODEX_SMOKE_PASSWORD --out "$work_dir/app.apk" "$work_dir/aligned.apk"
"$tools/apksigner" verify "$work_dir/app.apk"
"$tools/zipalign" -c -P 16 4 "$work_dir/app.apk"
"$adb" -e uninstall com.tsonglew.quodex >/dev/null 2>&1 || true
"$adb" -e install --no-incremental "$work_dir/app.apk"
python3 "$script_dir/release_ui_smoke.py" "$adb"
