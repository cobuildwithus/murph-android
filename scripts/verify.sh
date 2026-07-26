#!/usr/bin/env sh
set -eu

if [ ! -x ./gradlew ]; then
    echo "Gradle wrapper missing. Run ./scripts/generate-wrapper.sh first." >&2
    exit 1
fi

if grep -Fq 'LaunchedEffect(value)' app/src/main/java/ai/withmurph/companion/ui/login/LoginScreen.kt; then
    echo "OTP submission must not run from composition." >&2
    exit 1
fi

./gradlew --no-daemon test lintDebug lintRelease assembleDebug assembleRelease

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
