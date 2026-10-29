#!/usr/bin/env bash
# Keep execution and artifact collection in one shell; preserve the test result.
set +e
if [[ "${DEVICE_NATIVE_TESTS:-0}" == 1 ]]; then
  ./gradlew assembleDebug assembleDebugAndroidTest --stacktrace && python3 scripts/native_device_tests.py
else
  ./gradlew connectedDebugAndroidTest --stacktrace
fi
test_result=$?
if [[ "$test_result" -eq 0 ]]; then
  # Gradle/UTP may remove the test packages when its suite finishes.
  if adb -e install -r app/build/outputs/apk/debug/app-debug.apk &&
     adb -e install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; then
    bash scripts/widget_touch_smoke.sh && bash scripts/lifecycle_smoke.sh && bash scripts/system_time_smoke.sh && bash scripts/network_smoke.sh
    test_result=$?
    if [[ "$test_result" -eq 0 && "$(adb -e shell getprop ro.build.version.sdk | tr -d '\r')" == 29 ]]; then
      bash scripts/power_smoke.sh && bash scripts/scheduled_retry_smoke.sh
      test_result=$?
    fi
  else
    test_result=1
  fi
fi
mkdir -p screenshots
adb pull /sdcard/Pictures/QuodexTest/. ./screenshots/ || true
adb shell dumpsys appwidget > screenshots/widget-host-diagnostics.txt
adb logcat -d -s AndroidRuntime GlanceAppWidget AppWidgetServiceImpl QuotaDeviceEnvironment > screenshots/device-diagnostics.txt
adb logcat -d -s ActivityManager WindowManager InputDispatcher lmkd > screenshots/system-diagnostics.txt
if [[ "$test_result" -eq 0 && -n "${RELEASE_APK:-}" ]]; then
  bash scripts/release_smoke.sh "$RELEASE_APK" > screenshots/release-smoke.log 2>&1
  test_result=$?
  cat screenshots/release-smoke.log
  adb logcat -d -s AndroidRuntime > screenshots/release-runtime.txt
fi
exit "$test_result"
