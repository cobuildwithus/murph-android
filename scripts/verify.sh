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

expected_health_read_permissions='android.permission.health.READ_ACTIVE_CALORIES_BURNED
android.permission.health.READ_BASAL_METABOLIC_RATE
android.permission.health.READ_BLOOD_GLUCOSE
android.permission.health.READ_BLOOD_PRESSURE
android.permission.health.READ_BODY_FAT
android.permission.health.READ_BODY_TEMPERATURE
android.permission.health.READ_CERVICAL_MUCUS
android.permission.health.READ_DISTANCE
android.permission.health.READ_ELEVATION_GAINED
android.permission.health.READ_EXERCISE
android.permission.health.READ_FLOORS_CLIMBED
android.permission.health.READ_HEART_RATE
android.permission.health.READ_HEART_RATE_VARIABILITY
android.permission.health.READ_HEIGHT
android.permission.health.READ_HYDRATION
android.permission.health.READ_INTERMENSTRUAL_BLEEDING
android.permission.health.READ_MENSTRUATION
android.permission.health.READ_NUTRITION
android.permission.health.READ_OVULATION_TEST
android.permission.health.READ_OXYGEN_SATURATION
android.permission.health.READ_POWER
android.permission.health.READ_RESPIRATORY_RATE
android.permission.health.READ_SEXUAL_ACTIVITY
android.permission.health.READ_SLEEP
android.permission.health.READ_SPEED
android.permission.health.READ_STEPS
android.permission.health.READ_TOTAL_CALORIES_BURNED
android.permission.health.READ_VO2_MAX
android.permission.health.READ_WEIGHT'

for merged_manifest in \
    app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml \
    app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml
do
    if [ ! -f "$merged_manifest" ]; then
        echo "Merged manifest missing: $merged_manifest" >&2
        exit 1
    fi
    if grep -Eq 'SCHEDULE_EXACT_ALARM|READ_HEALTH_DATA_IN_BACKGROUND|READ_HEALTH_DATA_HISTORY|RECEIVE_BOOT_COMPLETED|SyncBroadcastReceiver|SyncOnExactAlarmService' "$merged_manifest"; then
        echo "Merged manifest contains forbidden background or extended-history health entry points: $merged_manifest" >&2
        exit 1
    fi
    if grep -Eq 'androidx\.work\.WorkManagerInitializer|io\.tryvital\.vitalhealthconnect\.VitalHealthConnectInitializer' "$merged_manifest"; then
        echo "Merged manifest can bypass Murph's guarded WorkManager factory: $merged_manifest" >&2
        exit 1
    fi
    if ! grep -Fq 'android.permission.health.READ_STEPS' "$merged_manifest" ||
        ! grep -Fq 'android.permission.health.READ_ACTIVE_CALORIES_BURNED' "$merged_manifest"; then
        echo "Merged manifest dropped a shipped activity permission: $merged_manifest" >&2
        exit 1
    fi
    if ! grep -Fq 'android.permission.FOREGROUND_SERVICE_DATA_SYNC' "$merged_manifest" ||
        ! grep -Fq 'android:name="androidx.work.impl.foreground.SystemForegroundService"' "$merged_manifest" ||
        ! grep -Fq 'android:foregroundServiceType="dataSync"' "$merged_manifest"; then
        echo "Merged manifest does not bind explicit health transfer to dataSync: $merged_manifest" >&2
        exit 1
    fi
    actual_health_read_permissions=$(grep -o 'android.permission.health.READ_[A-Z0-9_]*' "$merged_manifest" | sort -u)
    if [ "$actual_health_read_permissions" != "$expected_health_read_permissions" ]; then
        echo "Merged manifest Health Connect read permissions differ from the reviewed 29-name allowlist: $merged_manifest" >&2
        exit 1
    fi
done
