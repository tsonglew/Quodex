#!/usr/bin/env bash
# A minimal same-signature, higher-version APK for installer validation on an owned emulator.
# Do not confirm its installation: it contains no activity or production code.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_HOME:-$root/.tools/android-sdk}"
build_tools="$sdk/build-tools/35.0.0"
output="$root/.tools/update-fixture"
mkdir -p "$output"
version="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$root/app/build.gradle.kts")"
candidate_version="0.1.0-alpha$((version + 1))"
cat > "$output/AndroidManifest.xml" <<MANIFEST
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.tsonglew.quodex" android:versionCode="$((version + 1))" android:versionName="$candidate_version">
<uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36" />
<application android:hasCode="false" android:label="Quodex update test" />
</manifest>
MANIFEST
"$build_tools/aapt2" link -I "$sdk/platforms/android-36/android.jar" --manifest "$output/AndroidManifest.xml" -o "$output/unsigned.apk"
"$build_tools/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$output/candidate.apk" "$output/unsigned.apk"
"$build_tools/apksigner" verify "$output/candidate.apk"
printf '%s\n' "$output/candidate.apk"
