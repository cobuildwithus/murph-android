#!/usr/bin/env sh
set -eu

if [ ! -x ./gradlew ]; then
    echo "Gradle wrapper missing. Run ./scripts/generate-wrapper.sh first." >&2
    exit 1
fi

if ! command -v node >/dev/null 2>&1; then
    echo "Node.js is required to test the Android visual-proof verifier." >&2
    exit 1
fi

node --test scripts/check-android-visual-proof.test.mjs

if grep -Fq 'LaunchedEffect(value)' app/src/main/java/ai/withmurph/companion/ui/login/LoginScreen.kt; then
    echo "OTP submission must not run from composition." >&2
    exit 1
fi

./gradlew --no-daemon test lintDebug lintRelease assembleDebug assembleRelease assembleSynthetic checkPlayReleaseTooling

synthetic_manifest=app/build/intermediates/merged_manifest/synthetic/processSyntheticMainManifest/AndroidManifest.xml
if [ ! -f "$synthetic_manifest" ]; then
    echo "Synthetic merged manifest missing: $synthetic_manifest" >&2
    exit 1
fi
if grep -Eq 'MurphApplication|MainActivity|Initializer|<(uses-permission|permission|queries|provider|service|receiver|activity-alias)([[:space:]>]|$)' "$synthetic_manifest"; then
    echo "Synthetic UI fixture can initialize live app or data boundaries: $synthetic_manifest" >&2
    exit 1
fi
if ! grep -Fq 'package="ai.withmurph.app.synthetic"' "$synthetic_manifest" ||
   ! grep -Fq 'android:name="android.app.Application"' "$synthetic_manifest" ||
   ! grep -Fq 'android:name="ai.withmurph.companion.visual.ScreenshotActivity"' "$synthetic_manifest" ||
   [ "$(grep -Ec '<activity([[:space:]>]|$)' "$synthetic_manifest")" -ne 1 ]; then
    echo "Synthetic UI fixture owners are missing: $synthetic_manifest" >&2
    exit 1
fi

for merged_manifest in \
    app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml \
    app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml
do
    if [ ! -f "$merged_manifest" ]; then
        echo "Merged manifest missing: $merged_manifest" >&2
        exit 1
    fi
    if grep -Eq 'SCHEDULE_EXACT_ALARM|READ_HEALTH_DATA_IN_BACKGROUND|RECEIVE_BOOT_COMPLETED|SyncBroadcastReceiver|SyncOnExactAlarmService' "$merged_manifest"; then
        echo "Merged manifest contains forbidden background health entry points: $merged_manifest" >&2
        exit 1
    fi
done
