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
4. **Backend evidence is truth.** Local permission completion means only that the system flow completed. “Synced” requires a backend receipt scoped to `health_connect`.
5. **Backend membership before setup.** A verified Privy session must pass the read-only member/consent status check before the app renders health setup.
6. **Explicit lifecycle.** First setup sends `connect`; passive restoration sends `resume`. Authentication alone never creates a device-sync connection.
7. **Minimal persistence.** SharedPreferences contains an installation UUID, member key, setup timestamp, and last receipt timestamp. It never stores tokens or health values.
8. **Trust-boundary teardown.** Account switching and sign-out clear the local Junction session before another member can own the app.
9. **No duplicate ingestion engine.** Junction owns Health Connect reads, backfill, foreground workers, and provider upload. Murph does not maintain a second local health reader.
10. **Minimum health scope.** The resource set is centralized in `JunctionHealthSyncService` and mirrored by the manifest. New health categories require a current product need and updated disclosures.
11. **Optional permission timing.** History is requested during connection; background read is requested only when the member enables optional background sync.
12. **Default to deletion.** Add a dependency or abstraction only after the current boundaries cannot express a real requirement.

## Data flow

```text
WHOOP → Health Connect → Junction Android SDK → Junction webhooks → Murph device-sync pipeline
                                                               ↘ backend receipt ledger
Android UI ← source-scoped companion status endpoint ←───────────┘
```

## Why a separate native repository

The valuable parity with iOS is behavioral: OTP, session reconciliation, explicit health setup, honest status, and Murph styling. SwiftUI source is not reusable, while the Android implementation is dominated by platform contracts. A cross-platform layer would add another runtime and bridge without reducing the platform-specific work.
