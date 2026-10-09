#!/usr/bin/env bash
# CI runs on a fresh emulator. Never install the benchmark-signing-key APK
# over a primary user's release-signed installation.
set -euo pipefail

PACKAGE="com.mealplan.mealplanner"
ACTIVITY="com.example.mealplanner.MainActivity"
APK="$(find app/build/outputs/apk/benchmark -maxdepth 1 -name '*.apk' -print -quit)"
[[ -n "$APK" && -f "$APK" ]] || {
    echo "::error::R8 benchmark APK not found" >&2
    exit 1
}

# Always retain diagnostics, even if the UI hierarchy stops responding.
# The trap itself must never hang the CI runner.
save_diagnostics() {
    timeout --kill-after=2s 12s adb logcat -d -b main -b crash > r8-emulator-logcat.txt 2>/dev/null || true
    timeout --kill-after=2s 8s adb shell dumpsys activity activities > r8-emulator-activities.txt 2>/dev/null || true
}
trap save_diagnostics EXIT

if timeout --kill-after=2s 10s adb shell pm list packages |
    tr -d '\r' | grep -Fxq "package:${PACKAGE}"; then
    echo "::error::The emulator already contains MealPlanner; refusing to replace it." >&2
    exit 1
fi

echo "Installing R8-optimized candidate on fresh emulator"
timeout --kill-after=5s 60s adb install "$APK"
timeout --kill-after=2s 10s adb logcat -c
timeout --kill-after=5s 45s adb shell am start -W -n "${PACKAGE}/${ACTIVITY}"

# UIAutomator occasionally blocks indefinitely while collecting a Compose
# hierarchy. Enforce a hard deadline on every attempt AND on the whole script
# (in the workflow) rather than letting the CI check loop forever.
found=0
for attempt in 1 2 3 4 5; do
    echo "Checking the offline login button (attempt ${attempt}/5)"
    pid="$(timeout --kill-after=2s 6s adb shell pidof "$PACKAGE" 2>/dev/null || true)"
    if [[ -z "${pid//[[:space:]]/}" ]]; then
        echo "::error::R8-optimized app process exited during startup" >&2
        exit 1
    fi

    if timeout --kill-after=3s 18s adb shell uiautomator dump /sdcard/mealplanner-window.xml >/dev/null 2>&1; then
        hierarchy="$(timeout --kill-after=2s 8s adb exec-out cat /sdcard/mealplanner-window.xml 2>/dev/null || true)"
        if [[ "$hierarchy" == *"Fortsæt i lokal tilstand"* ]]; then
            found=1
            break
        fi
    else
        echo "::warning::UIAutomator dump timed out/failed (attempt ${attempt}); retrying with bounds"
    fi
    sleep 3
done

if [[ "$found" != 1 ]]; then
    echo "::error::R8-optimized app did not show the offline login option in bounded startup checks" >&2
    timeout --kill-after=2s 8s adb shell dumpsys activity activities 2>/dev/null | tail -n 50 || true
    timeout --kill-after=2s 8s adb logcat -d -t 120 2>/dev/null | tail -n 60 || true
    exit 1
fi

# A crash in the process must fail the test even if the splash/login briefly appears.
timeout --kill-after=2s 12s adb logcat -d -b main -b crash > r8-emulator-logcat.txt
if grep -Fq "Process: ${PACKAGE}" r8-emulator-logcat.txt; then
    echo "::error::R8-optimized app process crashed during startup" >&2
    exit 1
fi

echo "R8-optimized candidate launched and rendered the local login option."
