# Health Apps and Health Connect declaration draft

Official references:

- [Publish your health app on Google Play](https://developer.android.com/health-and-fitness/guides/health-connect/publish)
- [Provide information for the Health apps declaration form](https://support.google.com/googleplay/android-developer/answer/14738291)

The current candidate is a wellness companion, not a medical device, diagnosis tool, treatment tool, or human-subject research app. Before submission, select every live Play Console health-app category that corresponds to the exact read types below. The candidate spans activity and fitness, sleep, body measurements and vitals, nutrition and hydration, and menstrual/reproductive records.

Every permission is read-only, requested only after the signed-in member taps **Connect Health Connect**, and individually selectable in the system permission sheet. Murph receives only categories the member grants and the source app actually writes.

The manifest also declares `android.permission.FOREGROUND_SERVICE_DATA_SYNC` for the visible, explicit device-to-cloud transfer after those grants. It is a foreground-service classification, not a Health Connect record permission, extended-history permission, or background-read permission.

## Exact permission inventory

The release verifier binds this document to the exact manifest permission names. Keep this inventory synchronized with `app/src/main/AndroidManifest.xml` and `play/release-facts.json`.

- `android.permission.health.READ_ACTIVE_CALORIES_BURNED`
- `android.permission.health.READ_BASAL_METABOLIC_RATE`
- `android.permission.health.READ_BLOOD_GLUCOSE`
- `android.permission.health.READ_BLOOD_PRESSURE`
- `android.permission.health.READ_BODY_FAT`
- `android.permission.health.READ_BODY_TEMPERATURE`
- `android.permission.health.READ_CERVICAL_MUCUS`
- `android.permission.health.READ_DISTANCE`
- `android.permission.health.READ_ELEVATION_GAINED`
- `android.permission.health.READ_EXERCISE`
- `android.permission.health.READ_FLOORS_CLIMBED`
- `android.permission.health.READ_HEART_RATE`
- `android.permission.health.READ_HEART_RATE_VARIABILITY`
- `android.permission.health.READ_HEIGHT`
- `android.permission.health.READ_HYDRATION`
- `android.permission.health.READ_INTERMENSTRUAL_BLEEDING`
- `android.permission.health.READ_MENSTRUATION`
- `android.permission.health.READ_NUTRITION`
- `android.permission.health.READ_OVULATION_TEST`
- `android.permission.health.READ_OXYGEN_SATURATION`
- `android.permission.health.READ_POWER`
- `android.permission.health.READ_RESPIRATORY_RATE`
- `android.permission.health.READ_SEXUAL_ACTIVITY`
- `android.permission.health.READ_SLEEP`
- `android.permission.health.READ_SPEED`
- `android.permission.health.READ_STEPS`
- `android.permission.health.READ_TOTAL_CALORIES_BURNED`
- `android.permission.health.READ_VO2_MAX`
- `android.permission.health.READ_WEIGHT`

## Exact Junction resource inventory

The centralized Android scope explicitly lists every resource exposed by the pinned Vital 5.0.2 Health Connect SDK. A unit test compares this authored list with `VitalResource.values()` so a dependency upgrade cannot silently broaden access.

- `VitalResource.Profile`
- `VitalResource.Body`
- `VitalResource.Workout`
- `VitalResource.Activity`
- `VitalResource.Sleep`
- `VitalResource.Glucose`
- `VitalResource.BloodPressure`
- `VitalResource.BloodOxygen`
- `VitalResource.HeartRate`
- `VitalResource.Water`
- `VitalResource.HeartRateVariability`
- `VitalResource.MenstrualCycle`
- `VitalResource.Steps`
- `VitalResource.ActiveEnergyBurned`
- `VitalResource.BasalEnergyBurned`
- `VitalResource.FloorsClimbed`
- `VitalResource.DistanceWalkingRunning`
- `VitalResource.Vo2Max`
- `VitalResource.RespiratoryRate`
- `VitalResource.Temperature`
- `VitalResource.Meal`

## Permission justifications

| Data family | Permissions and Junction resources | Proposed Play Console explanation |
| --- | --- | --- |
| Exercise | `android.permission.health.READ_EXERCISE`, `android.permission.health.READ_ELEVATION_GAINED`, `android.permission.health.READ_POWER`, `android.permission.health.READ_SPEED`; `VitalResource.Workout` | Reads authorized exercise sessions and supported workout details so Murph can use the member's workout context and confirm when connected data reaches Murph. Vital activates workout synchronization only when Exercise is granted; elevation, power, or speed alone cannot activate the aggregate resource. |
| Activity and fitness | `android.permission.health.READ_STEPS`, `android.permission.health.READ_ACTIVE_CALORIES_BURNED`, `android.permission.health.READ_BASAL_METABOLIC_RATE`, `android.permission.health.READ_TOTAL_CALORIES_BURNED`, `android.permission.health.READ_DISTANCE`, `android.permission.health.READ_FLOORS_CLIMBED`, `android.permission.health.READ_VO2_MAX`; `VitalResource.Activity`, `VitalResource.Steps`, `VitalResource.ActiveEnergyBurned`, `VitalResource.BasalEnergyBurned`, `VitalResource.FloorsClimbed`, `VitalResource.DistanceWalkingRunning`, `VitalResource.Vo2Max` | Reads authorized activity and fitness records so Murph can summarize movement, energy, distance, elevation, and cardio-fitness context selected by the member. |
| Sleep | `android.permission.health.READ_SLEEP`; `VitalResource.Sleep` | Reads authorized sleep sessions so Murph can use the member's sleep context and show backend-confirmed connection status. |
| Vitals | `android.permission.health.READ_HEART_RATE`, `android.permission.health.READ_HEART_RATE_VARIABILITY`, `android.permission.health.READ_RESPIRATORY_RATE`, `android.permission.health.READ_OXYGEN_SATURATION`, `android.permission.health.READ_BLOOD_PRESSURE`, `android.permission.health.READ_BLOOD_GLUCOSE`, `android.permission.health.READ_BODY_TEMPERATURE`; `VitalResource.HeartRate`, `VitalResource.HeartRateVariability`, `VitalResource.RespiratoryRate`, `VitalResource.BloodOxygen`, `VitalResource.BloodPressure`, `VitalResource.Glucose`, `VitalResource.Temperature` | Reads authorized vital-sign records so Murph can place the member's selected measurements in longitudinal context. Murph does not diagnose or replace medical care. |
| Profile and body measurements | `android.permission.health.READ_HEIGHT`, `android.permission.health.READ_WEIGHT`, `android.permission.health.READ_BODY_FAT`; `VitalResource.Profile`, `VitalResource.Body` | Reads authorized height, weight, and body-composition records so Murph can interpret member-selected body and fitness trends. |
| Hydration | `android.permission.health.READ_HYDRATION`; `VitalResource.Water` | Reads authorized hydration records for member-requested nutrition and wellness context. |
| Nutrition | `android.permission.health.READ_NUTRITION`; `VitalResource.Meal` | Reads authorized nutrition records for member-requested meal and nutrient context. |
| Menstrual and reproductive health | `android.permission.health.READ_MENSTRUATION`, `android.permission.health.READ_CERVICAL_MUCUS`, `android.permission.health.READ_INTERMENSTRUAL_BLEEDING`, `android.permission.health.READ_OVULATION_TEST`, `android.permission.health.READ_SEXUAL_ACTIVITY`; `VitalResource.MenstrualCycle` | Reads only the reproductive-health categories the member explicitly selects so Murph can use that authorized context when the member asks. Vital activates menstrual-cycle synchronization only when Menstruation is granted; the four detail permissions alone cannot activate the aggregate resource. |

## Scope and review evidence

- Permissions are requested only after the member taps **Connect Health Connect**.
- The permission contract is read-only; there are no write resources.
- The manifest declares 29 Health Connect record-type read permissions.
- The member may select any subset. Vital 5.0.2 activates only complete resource groups: Exercise is required for workout elevation, power, and speed, and Menstruation is required for cervical mucus, intermenstrual bleeding, ovulation tests, and sexual activity. A detail-only selection names its missing aggregate when no configured resource is active, but an orphan detail does not block an unrelated active resource. The app requires at least one active configured resource and reports only backend-confirmed data receipt as connected.
- App-owned permission counts and manual syncs intersect SDK-discovered grants with the reviewed resource set; an unrelated or future SDK grant cannot silently become Murph-owned behavior.
- The app's Health Connect rationale route points to the same in-app legal/privacy surface used by Settings.
- `syncOnAppStart` is false. Background Health Connect permission is absent. Vital's boot receiver and exact-alarm service are removed. SDK synchronization remains paused before permission, through `connect()`, and outside explicit app-owned foreground sync calls. WorkManager 2.11.2 initializes on demand; its factory requires durable member/setup authority, a clear sign-out tombstone, and a default-closed process-local lease for the backend-validated member. A restarted headless process therefore rejects old work. Murph replaces only Vital 5.0.2's `shortService` umbrella worker with a `dataSync` starter while retaining the SDK's real per-resource readers/uploaders, exact unique names, input contract, notification, and configured-and-granted scope. The identity boundary cancels every pinned unique work name before teardown.
- Junction's current backfill is the ordinary 30-day foreground window. Extended-history and background permissions are absent.
- Listing and reviewer notes must explain how to install/configure Health Connect and connect a compatible source without implying every device, membership, region, or source app exposes every category.
- Before submission, verify the expanded permission sheet and at least one real export for activity, sleep, body/weight, and blood pressure on a physical Android device. On Android 14–16, also prove a transfer lasting beyond three minutes remains a visible `dataSync` foreground service without a `shortService` timeout or ANR.

Before submission, compare this document to `play/release-facts.json`, the merged manifest, and `JunctionHealthSyncService`. Add, remove, and rejustify every changed type in the Play Console. Google requires a renewed declaration when accessed data types change.
