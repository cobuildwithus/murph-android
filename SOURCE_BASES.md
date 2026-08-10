# Source bases

The current parity work was prepared against these reviewed source snapshots on August 6, 2026:

- `cobuildwithus/murph-ios` PR #48 — `f8cdbfd6555def5f4e0c5f2575417cb8cef76931`
- `cobuildwithus/murph` PR #1296 — `46e3671b47001d2b7cc9eeef6afcb659532ba2a5`
- `cobuildwithus/murph` PR #1341 — `f342e61b1d109635c631d5b86014bb4f1de4bd39`
- `cobuildwithus/murph-android` base — `02b4c872dfdc8cd6fba72a4baae4930c85fdc337`
- `tryVital/vital-android` — `eda6a537d0518a7ca7c3716a5c8b25f4a8fae5d0` / SDK `5.0.2`
- Privy Android SDK — artifact `io.privy:privy-core:0.12.0`

Account admission and initial onboarding require the listed Murph backend PRs
to deploy before this Android change. The mobile client deliberately consumes
those server-owned contracts rather than duplicating signup, catalog, or
completion state.

## Health-scope review

The Android health scope was revalidated on August 5, 2026 against primary
sources rather than inferred from the iOS enum names:

- Junction/Vital Android 5.0.2
  [`VitalResource`](https://github.com/tryVital/vital-android/blob/eda6a537d0518a7ca7c3716a5c8b25f4a8fae5d0/VitalHealthCore/src/main/java/io/tryvital/vitalhealthcore/model/VitalResource.kt),
  [Health Connect record mapping](https://github.com/tryVital/vital-android/blob/eda6a537d0518a7ca7c3716a5c8b25f4a8fae5d0/VitalHealthConnect/src/main/java/io/tryvital/vitalhealthconnect/model/VitalResource.kt),
  [permission behavior](https://github.com/tryVital/vital-android/blob/eda6a537d0518a7ca7c3716a5c8b25f4a8fae5d0/VitalHealthConnect/src/main/java/io/tryvital/vitalhealthconnect/VitalPermissionRequestContract.kt),
  [global grant discovery and connect-time sync](https://github.com/tryVital/vital-android/blob/eda6a537d0518a7ca7c3716a5c8b25f4a8fae5d0/VitalHealthConnect/src/main/java/io/tryvital/vitalhealthconnect/VitalHealthConnectManager.kt),
  and [30-day SDK clamp](https://github.com/tryVital/vital-android/blob/eda6a537d0518a7ca7c3716a5c8b25f4a8fae5d0/VitalHealthCore/src/main/java/io/tryvital/vitalhealthcore/workers/BaseLocalSyncStateManager.kt).
- Android's official [Health Connect data-type and permission table](https://developer.android.com/health-and-fitness/health-connect/data-types)
  and [history-read behavior](https://developer.android.com/health-and-fitness/health-connect/read-data).
- WHOOP's official [Health Connect export and import matrix](https://support.whoop.com/s/article/Google-Health-Integration-For-Android).
- Murph `05f28303e2008324f7ed6a03dbab82bf322acfcf` default Junction
  [timeseries scope](https://github.com/cobuildwithus/murph/blob/05f28303e2008324f7ed6a03dbab82bf322acfcf/packages/contracts/src/junction-resources.ts)
  and [summary/ingestion scope](https://github.com/cobuildwithus/murph/blob/05f28303e2008324f7ed6a03dbab82bf322acfcf/packages/importers/src/device-providers/junction-resources.ts).

That source review showed that Vital 5.0.2 discovers grants across every
resource after permission/connect flows and does not remap the standalone
activity resources. Android therefore pauses SDK synchronization before the
permission flow and through `connect()`, then unpauses only for an explicit
foreground call with the configured-and-granted intersection. It keeps the
already-shipped step and active-calorie permissions and makes `Activity`,
`Steps`, and `ActiveEnergyBurned` explicit configured owners. All three may run
during that call. Murph's current default intake admits the `activity` summary,
while `steps` and `calories_active` are known but not allowed default
timeseries and are normalized away. Their explicit Android owners preserve
shipped client upload behavior without claiming standalone end-to-end
ingestion.
