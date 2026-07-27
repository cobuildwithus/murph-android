# Architecture

## One sentence

A native Android companion that authenticates an existing Murph member, explicitly connects a minimum-necessary Health Connect resource set, and renders backend-confirmed receipt status.

## Shape

```text
app/       one composition root and the app/session state owner
auth/      Privy adapter and OTP flow state
api/       tiny authenticated HTTP boundary
core/      app-owned contracts and pure models
health/    the only Junction/Health Connect adapter
storage/   trivial non-health local state
ui/        Compose surfaces and Murph theme
```

## Rules

1. **SDK types stop at the edge.** Only `auth/` imports Privy. Only `health/` imports Junction or Health Connect. UI and session logic use app-owned contracts.
2. **One composition root.** `AppGraph` constructs all live objects. Tests construct pure objects or fakes directly. No Hilt, service locator, or reflection.
3. **State machines over flag soup.** `AppPhase`, `AuthSessionState`, `HealthConnectAvailability`, and `HealthSyncState` are the vocabularies rendered by UI.
4. **Backend evidence is truth.** Local permission completion means only that the system flow completed. “Synced” requires a backend receipt scoped to `health_connect` whose timestamp is at or after the current setup boundary.
5. **Backend membership before health work.** A current, verified Privy member must pass the read-only member/consent status check before setup or every app-triggered health sync.
6. **Explicit, transactional lifecycle.** First setup requests system permission before obtaining a `connect` token or identifying Junction; passive restoration sends `resume`. Permission recovery durably revokes the prior setup and tears down its Junction identity before a fresh `connect`. An incomplete setup never becomes a durable local Junction identity.
7. **Minimal persistence.** SharedPreferences contains an installation UUID, member key, setup timestamp, last receipt timestamp, and a pending-sign-out tombstone. It never stores tokens or health values.
8. **Trust-boundary teardown.** Account switching, sign-out, backend rejection, and a signed-in Junction SDK without a completed setup marker clear the local Junction session before more health work. Sign-out atomically persists its tombstone and removes durable setup authorization before waiting on app work or touching either SDK. Startup finishes pending Junction-first, Privy-second teardown before auth restoration and clears the tombstone only after both boundaries and member-state removal succeed.
9. **One foreground-sync owner.** Junction owns Health Connect reads, backfill, its foreground WorkManager chain, and provider upload; `AppSession` owns when an explicit foreground sync may begin. SDK app-start sync stays disabled.
10. **Minimum health scope.** The resource set is centralized in `JunctionHealthSyncService` and mirrored by the manifest. New health categories require a current product need and updated disclosures.
11. **Foreground-only release.** History is requested during connection. Background Health Connect permission, the Junction boot receiver, and its exact-alarm service are removed because Vital 5.0.2 has no pre-worker hook for Murph's durable sign-out authorization.
12. **Process-owned transitions.** `AppGraph` owns the application-lifetime coroutine scope used for session, login, sync, and sign-out work. Activity recreation can replace the renderer without cancelling those transitions.
13. **Default to deletion.** Add a dependency or abstraction only after the current boundaries cannot express a real requirement.

## Data flow

```text
WHOOP → Health Connect → Junction Android SDK → Junction webhooks → Murph device-sync pipeline
                                                               ↘ backend receipt ledger
Android UI ← source-scoped companion status endpoint ←───────────┘
```

## Why a separate native repository

The valuable parity with iOS is behavioral: OTP, session reconciliation, explicit health setup, honest status, and Murph styling. SwiftUI source is not reusable, while the Android implementation is dominated by platform contracts. A cross-platform layer would add another runtime and bridge without reducing the platform-specific work.
