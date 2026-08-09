# Health Apps and Health Connect declaration draft

Official references:

- [Publish your health app on Google Play](https://developer.android.com/health-and-fitness/guides/health-connect/publish)
- [Provide information for the Health apps declaration form](https://support.google.com/googleplay/android-developer/answer/14738291)

Select **Health and fitness → Activity and Fitness** and **Health and fitness → Sleep Management** for the current candidate. The app is a wellness companion, not a medical device, diagnosis tool, treatment tool, or human-subject research app.

## Permission justifications

| Permission / resource | Proposed Play Console explanation |
| --- | --- |
| `android.permission.health.READ_SLEEP` / `VitalResource.Sleep` | After a signed-in member explicitly connects Health Connect and chooses this category, Murph reads sleep records through Junction so the member's Murph experience can use their authorized sleep context and show whether connected data reached Murph. |
| `android.permission.health.READ_EXERCISE` / `VitalResource.Workout` | After explicit opt-in, Murph reads exercise sessions through Junction so the member's Murph experience can use their authorized workout context and show backend-confirmed connection status. |
| `android.permission.health.READ_STEPS` / `VitalResource.Steps` | After explicit opt-in, Junction can upload the authorized step records so Murph can use the member-authorized activity context. |
| `android.permission.health.READ_ACTIVE_CALORIES_BURNED` / `VitalResource.ActiveEnergyBurned` | After explicit opt-in, Junction can upload the authorized active-calorie records for the same connected-health feature. |
| `android.permission.health.READ_HEALTH_DATA_HISTORY` | Where the installed provider supports it, the app asks for history access during the same explicit connection flow. Junction remains configured for a 30-day backfill; the app does not promise broader history. |

## Scope and review evidence

- Permissions are requested only after the member taps **Connect Health Connect**.
- The permission contract is read-only; there are no write resources.
- The centralized scope contains four `VitalResource` values covering four Health Connect data-type read permissions, plus the separate history-access permission where supported.
- The member may select a subset. The app requires at least one granted category and reports only backend-confirmed data receipt as connected.
- The app's Health Connect rationale route points to the same in-app legal/privacy surface used by Settings.
- `syncOnAppStart` is false. Background Health Connect permission is absent. Vital's boot receiver and exact-alarm service are removed. Those settings do not disable the SDK's global resource discovery or its unfiltered asynchronous sync after permission and `connect()` flows. The app separately starts one app-owned foreground sync after setup commits, using only the configured-and-granted intersection.
- Junction's current backfill is 30 days. Although `android.permission.health.READ_HEALTH_DATA_HISTORY` is requested where supported, do not claim a broader Android history window.
- The listing and reviewer notes must explain how to install/configure Health Connect and connect a compatible source without implying every device, membership, or source exposes every category.

Before submission, compare this table to `release-facts.json`, the merged manifest, and `JunctionHealthSyncService`. Add, remove, and rejustify every changed type in the Play Console. Google requires a renewed declaration when the accessed data types change.
