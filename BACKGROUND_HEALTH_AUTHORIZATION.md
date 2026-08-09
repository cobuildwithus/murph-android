# Background health authorization boundary

Status: **blocked for Junction/Vital Android 5.0.2**

Reviewed: 2026-08-05

Scope: unattended Health Connect reads and uploads only

## Decision

Keep Murph health synchronization foreground-only. Do not restore Vital's boot
receiver or exact-alarm service, request Health Connect background-read access,
or wrap `syncData()` in an app-owned background worker.

Android supports background Health Connect reads, but platform permission is
only one of the required gates. Murph must also prove, immediately before every
worker can read or upload, that:

1. no durable sign-out tombstone exists;
2. the verified Privy member still matches the persisted member owner;
3. the backend still recognizes that member and current launch consent; and
4. the worker belongs to the current health-setup generation.

Vital 5.0.2 does not expose a callback or worker factory where those checks can
be inserted for its unattended worker chain. Local Vital sign-in state is not
a substitute for Murph member and consent authorization.

## Evidence

The reviewed vendor source is the published `5.0.2` tag at commit
[`eda6a537`](https://github.com/tryVital/vital-android/tree/eda6a537d0518a7ca7c3716a5c8b25f4a8fae5d0).

- Vital's public background-sync contract schedules exact alarms and requires
  boot handling; it does not accept an application authorization callback
  ([`backgroundSync.kt`, lines 81-110](https://github.com/tryVital/vital-android/blob/eda6a537d0518a7ca7c3716a5c8b25f4a8fae5d0/VitalHealthConnect/src/main/java/io/tryvital/vitalhealthconnect/backgroundSync.kt#L81-L110)).
- When the alarm fires, the SDK reschedules itself and starts its own foreground
  service directly
  ([`SyncBroadcastReceiver.kt`, lines 49-75](https://github.com/tryVital/vital-android/blob/eda6a537d0518a7ca7c3716a5c8b25f4a8fae5d0/VitalHealthConnect/src/main/java/io/tryvital/vitalhealthconnect/SyncBroadcastReceiver.kt#L49-L75)).
- That service invokes the SDK's internal automatic-sync launcher; its only
  caller hook runs before enqueue and cannot authorize each resource worker
  ([`SyncOnExactAlarmService.kt`, lines 20-52](https://github.com/tryVital/vital-android/blob/eda6a537d0518a7ca7c3716a5c8b25f4a8fae5d0/VitalHealthConnect/src/main/java/io/tryvital/vitalhealthconnect/workers/SyncOnExactAlarmService.kt#L20-L52),
  [`VitalHealthConnectManager.kt`, lines 438-482](https://github.com/tryVital/vital-android/blob/eda6a537d0518a7ca7c3716a5c8b25f4a8fae5d0/VitalHealthConnect/src/main/java/io/tryvital/vitalhealthconnect/VitalHealthConnectManager.kt#L438-L482)).
- The starter subsequently enqueues a separate internal worker for each granted
  resource
  ([`ResourceSyncStarter.kt`, lines 149-189](https://github.com/tryVital/vital-android/blob/eda6a537d0518a7ca7c3716a5c8b25f4a8fae5d0/VitalHealthConnect/src/main/java/io/tryvital/vitalhealthconnect/workers/ResourceSyncStarter.kt#L149-L189)).
- Each resource worker proceeds from Vital's local connection state into Health
  Connect reads and provider uploads. It has no Murph authorization seam
  ([`ResourceSyncWorker.kt`, lines 218-270](https://github.com/tryVital/vital-android/blob/eda6a537d0518a7ca7c3716a5c8b25f4a8fae5d0/VitalHealthConnect/src/main/java/io/tryvital/vitalhealthconnect/workers/ResourceSyncWorker.kt#L218-L270),
  [`ResourceSyncWorker.kt`, lines 385-476](https://github.com/tryVital/vital-android/blob/eda6a537d0518a7ca7c3716a5c8b25f4a8fae5d0/VitalHealthConnect/src/main/java/io/tryvital/vitalhealthconnect/workers/ResourceSyncWorker.kt#L385-L476)).

Android's official guidance confirms that background access requires the
feature-gated `READ_HEALTH_DATA_IN_BACKGROUND` permission and commonly uses
WorkManager. That makes the platform operation possible; it does not add the
missing Murph member-authorization hook
([Health Connect background reads](https://developer.android.com/health-and-fitness/health-connect/read-data#background-read)).
Android also recommends WorkManager, rather than exact alarms, for ordinary
scheduled background work
([exact-alarm guidance](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms#use-cases)).

## Why an app-owned wrapper is insufficient

An app-owned worker could validate the member once and then call the public
`syncData()` API. That API still hands execution to the internal starter and
its later per-resource workers. Murph cannot recheck its durable tombstone,
member owner, consent, and setup generation at those worker boundaries. A
sign-out or member change can therefore become durable after the wrapper's
single preflight and before a later resource worker begins.

This is the same missing seam behind both the vendor exact-alarm path and a
custom WorkManager wrapper. Changing the scheduler does not change the
authorization boundary.

## Current enforcement

The application manifest removes Vital's `SyncBroadcastReceiver`,
`SyncOnExactAlarmService`, and inherited `RECEIVE_BOOT_COMPLETED` permission.
It does not request `READ_HEALTH_DATA_IN_BACKGROUND`. `scripts/verify.sh`
builds both variants, inspects each merged manifest, and fails if those entry
points or permissions reappear.

Foreground app entry, foreground return, and **Sync now** remain the only sync
owners. They use `AppSession`'s existing member, backend-consent, setup-owner,
and sign-out checks before calling the Junction adapter.

## Smallest future unlock

Reconsider unattended sync only when the pinned Junction/Vital SDK provides
one of these documented, testable boundaries:

- a customer-supplied authorization callback invoked immediately before every
  resource worker reads or uploads; or
- a public single-resource operation that Murph can execute inside its own
  worker while holding a durable generation fence and revalidating member and
  consent ownership for that operation.

An SDK upgrade alone is not proof. Review the exact published source, add race
tests covering sign-out and member switching between resource workers, and
keep merged-manifest enforcement until those tests pass.

Generic reopen reminders are a separate notification concern. They must not
read health data, call Junction sync APIs, or weaken the foreground-only claim.
