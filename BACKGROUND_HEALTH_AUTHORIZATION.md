# Background health authorization boundary

Status: **blocked for Junction/Vital Android 5.0.2**

Reviewed: 2026-08-10

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

Vital 5.0.2 does not expose a callback where Murph can revalidate backend
authority for unattended work. Murph's WorkManager factory can intercept the
pinned worker classes, but it deliberately requires a process-local lease that
starts closed and opens only after a live foreground preflight. Local Vital
sign-in state and durable preferences are not substitutes for Murph member and
consent authorization.

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

## Why the foreground wrapper does not unlock unattended sync

For explicit foreground transfer, Murph replaces Vital's umbrella starter,
requires the exact member's process-local lease before each resource enqueue,
and keeps teardown behind the same session mutex until every exact worker is
terminal. The factory requires that lease again when constructing Vital's real
per-resource worker. Process death resets the lease closed, so WorkManager
reconstruction rejects the durable request.

Vital's discoverable Startup initializer is also excluded: it declares
`WorkManagerInitializer` as a dependency and would otherwise initialize the
default factory before `MurphApplication`'s configuration could take effect.
The adapter obtains the on-demand WorkManager instance through that guarded
configuration before creating the Vital manager. The lease starts in a
launch-authorized state, becomes promoted only after `setForeground` succeeds,
and is rejected if the Activity backgrounds first. A promoted visible transfer
may continue; an unpromoted request cannot reach a resource reader.

That design intentionally cannot authorize an alarm, boot, or other unattended
entry point: no live foreground member/backend preflight exists to open the
lease. Making the lease durable would recreate the stale-authority gap this
boundary is designed to close.

## Current enforcement

The application manifest removes Vital's `SyncBroadcastReceiver`,
`SyncOnExactAlarmService`, discoverable Startup initializer, and inherited
`RECEIVE_BOOT_COMPLETED` permission, as well as WorkManager's default
initializer. It does not request `READ_HEALTH_DATA_IN_BACKGROUND`.
`scripts/verify.sh` builds both variants, inspects each merged manifest, and
fails if those entry points, initializers, or permissions reappear.

Foreground app entry, foreground return, and **Sync now** remain the only sync
owners. They use `AppSession`'s existing member, backend-consent, setup-owner,
and sign-out checks before calling the Junction adapter with the validated
member key. The adapter keeps
Vital synchronization paused across permission and connect flows and unpauses
only inside an explicit foreground call. Its default-closed process lease and
authorization-aware factory reject reconstructed work, while its `dataSync`
starter preserves Vital's real per-resource readers and uploaders. Teardown
fences new app-owned health work, cancels and proves the current foreground chain
terminal, and only then crosses the Junction identity, member, or consent
boundary. Backgrounding before foreground-service promotion rejects the launch
and leaves an explicit foreground retry; backgrounding after promotion does not
interrupt the visible transfer.

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
