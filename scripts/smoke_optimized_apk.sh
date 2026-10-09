#!/usr/bin/env bash
# CI uses a fresh emulator. NEVER run on the primary phone: debug signing
# differs from the release signature and an install might require data loss.
set -euo pipefail

PACKAGE="com.mealplan.mealplanner"
APK="$(find app/build/outputs/apk/benchmark -maxdepth 1 -name '*.apk' -print -quit)"
test -n "$APK" || { echo "R8 benchmark APK not found" >&2; exit 1; }

# Preserve crash evidence even if an assertion below fails.
trap 'adb logcat -d -b main -b crash > r8-emulator-logcat.txt || true' EXIT

if adb shell pm list packages | tr -d '\r' | grep -Fxq "package:${PACKAGE}"; then
    echo "Refusing to replace an already installed app; use a fresh emulator." >&2
    exit 1
fi

adb install "$APK"
adb logcat -c
adb shell am start -W -n "${PACKAGE}/com.example.mealplanner.MainActivity"

# Wait for Compose login gate, without a network account or cloud credentials.
found=0
for attempt in $(seq 1 12); do
    if ! adb shell pidof "$PACKAGE" >/dev/null; then
        echo "Optimized app process disappeared during startup" >&2
        exit 1
    fi
    adb shell uiautomator dump /sdcard/mealplanner-window.xml >/dev/null 2>&1 || true
    if adb exec-out cat /sdcard/mealplanner-window.xml 2>/dev/null |
        grep -Fq "Fortsæt i lokal tilstand"; then
        found=1
        break
    fi
    sleep 3
done

if [[ "$found" != "1" ]]; then
    echo "Optimized app did not show the expected local-mode login UI" >&2
    exit 1
fi

adb logcat -d -b main -b crash > r8-emulator-logcat.txt
if grep -Fq "Process: ${PACKAGE}" r8-emulator-logcat.txt; then
    echo "Crash detected from the R8-optimized app" >&2
    exit 1
fi

echo "R8-optimized benchmark APK launched and displayed the login gate."
