#!/usr/bin/env bash
set -Eeuo pipefail

mkdir -p diagnostics
PKG=com.mealplan.mealplanner

APK="$(find target/app/build/outputs/apk/release -maxdepth 1 -name '*.apk' | head -n 1)"
if [ -z "$APK" ]; then
  echo "No release APK produced"
  exit 1
fi

echo "Testing release APK: $APK"
echo "App ID: $PKG"
KEYSTORE="$PWD/diagnostics/emulator-test-signing.jks"
keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 1 -noprompt >/dev/null 2>&1

APKSIGNER="$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -name apksigner | sort -V | tail -1)"
if [ -z "$APKSIGNER" ]; then
  echo "apksigner not found"
  exit 1
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out diagnostics/signed-release.apk "$APK"
"$APKSIGNER" verify diagnostics/signed-release.apk
adb install -r diagnostics/signed-release.apk
adb shell pm path "$PKG" > diagnostics/package-path.txt
adb shell dumpsys package "$PKG" > diagnostics/package-details.txt

adb logcat -c
adb shell am force-stop "$PKG" || true
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 | tee diagnostics/launch-command.txt
sleep 12

adb logcat -d -b crash -v threadtime > diagnostics/crash-buffer.txt
adb logcat -d -v threadtime -t 2000 > diagnostics/recent-logcat.txt

PID="$(adb shell pidof "$PKG" | tr -d '\r' || true)"
echo "Process after launch: ${PID:-none}"
echo "Process after launch: ${PID:-none}" > diagnostics/process-state.txt

if [ -s diagnostics/crash-buffer.txt ]; then
  echo "=== Android crash buffer ==="
  cat diagnostics/crash-buffer.txt
  if grep -Eq 'FATAL EXCEPTION|Force finishing activity|Process: com.mealplan.mealplanner' diagnostics/crash-buffer.txt; then
    echo "Release process crashed. See logcat artifact."
    exit 1
  fi
fi

if [ -z "$PID" ]; then
  echo "Process not alive. Recent startup logs:"
  grep -Ei -A12 'fatal exception|androidruntime|classnotfound|noclassdeffound|process.*died|exception' diagnostics/recent-logcat.txt | tail -n 200 || true
  exit 1
fi

echo "Release app launched and remains alive for 12 seconds."
