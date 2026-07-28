# Architecture

## One sentence

A native Android companion that authenticates an existing Murph member, explicitly connects a minimum-necessary Health Connect resource set, optionally projects privacy-minimized contact names, and renders backend-confirmed state.

## Shape

```text
app/       one composition root and the app/session state owner
auth/      Privy adapter and OTP flow state
api/       tiny authenticated HTTP boundary
contacts/  the only Android Contacts edge plus a pure bounded projector
core/      app-owned contracts and pure models
health/    the only Junction/Health Connect adapter
storage/   trivial non-health local state
ui/        Compose surfaces and Murph theme
```

## Rules

1. **SDK types stop at the edge.** Only `auth/` imports Privy. Only `health/` imports Junction or Health Connect. Only `contacts/AndroidAddressBookContacts.kt` imports the Android Contacts framework. UI and session logic use app-owned contracts.
2. **One composition root.** `AppGraph` constructs all live objects. Tests construct pure objects or fakes directly. No Hilt, service locator, or reflection.
3. **State machines over flag soup.** `AppPhase`, `AuthSessionState`, `HealthConnectAvailability`, `HealthSyncState`, and `AddressBookSharingState` are the vocabularies rendered by UI.
4. **Backend evidence is truth.** Local permission completion means only that the system flow completed. “Synced” requires a backend receipt scoped to `health_connect` whose timestamp is at or after the current setup boundary. Address-book copy renders only the server status returned by the companion endpoint, never local permission state as success.
5. **Backend membership before health work.** A current, verified Privy member must pass the read-only member/consent status check before setup or every app-triggered health sync. Offline restoration always returns through this check when Privy becomes online-verified, with or without an existing health setup. Structured launch-consent failures enter one native `AppSession` recovery owner, which pauses local Junction authority, loads server-owned documents, accepts only missing launch scopes, and resumes the exact blocked action after the backend reports launch consent granted.
6. **Explicit, transactional lifecycle.** First setup requests system permission before obtaining a `connect` token or identifying Junction; passive restoration sends `resume`. Permission recovery durably revokes the prior setup and tears down its Junction identity before a fresh `connect`. Foreground reconciliation preserves the matching in-flight transaction, while sign-out and member changes invalidate it. An incomplete setup never becomes a durable local Junction identity.
7. **Minimal persistence.** SharedPreferences contains an installation UUID, member key, setup timestamp, last receipt timestamp, pending-sign-out tombstone, address-book server revision, and at most one replacement and one deletion mutation's base revision plus UUIDv4 id. It never stores tokens, health values, contacts, phone numbers, names, hashes, lookup keys, consent documents, consent versions, grants, or provider responses.
8. **Trust-boundary teardown.** Account switching, sign-out, backend rejection, and a signed-in Junction SDK without a completed setup marker clear the local Junction session before more health work. Sign-out atomically persists its tombstone and removes durable setup authorization before waiting on app work or touching either SDK. Startup finishes pending Junction-first, Privy-second teardown before auth restoration and clears the tombstone only after both boundaries and member-state removal succeed. A failed preferences commit restores its pre-call live snapshot so undurable values never authorize later lifecycle work.
9. **One foreground-sync owner.** Junction owns Health Connect reads, backfill, its foreground WorkManager chain, and provider upload; `AppSession` owns when an explicit foreground sync may begin. SDK app-start sync stays disabled.
10. **Local permission truth is independent.** Complete Health Connect revocation renders `NotConnected` and exposes recovery even while Privy verification is temporarily unavailable; it never starts health work by itself.
11. **Minimum health scope.** The resource set is centralized in `JunctionHealthSyncService` and mirrored by the manifest. New health categories require a current product need and updated disclosures.
12. **Foreground-only release.** History is requested during connection. Background Health Connect permission, the Junction boot receiver, and its exact-alarm service are removed because Vital 5.0.2 has no pre-worker hook for Murph's durable sign-out authorization.
13. **Explicit one-shot contact projection.** Contacts are read only after Share, Update, or Retry and only after a server-status preflight plus the Android permission flow. The projector accepts explicit international numbers, emits bounded first-name labels, drops conflicts, and sends a full-list compare-and-swap replacement. Foreground reconciliation never requests permission or reads contacts; when access is lost it may delete only an exact revision this installation still owns. This is not identity proof, routing authority, signup prefill, invitation delivery, messaging, background sync, or contact backup.
14. **Process-owned transitions.** `AppGraph` owns the application-lifetime coroutine scope used for session, login, sync, contact reconciliation, and sign-out work. Activity recreation can replace the renderer without cancelling those transitions.
15. **Default to deletion.** Add a dependency or abstraction only after the current boundaries cannot express a real requirement.

## Data flow

```text
WHOOP → Health Connect → Junction Android SDK → Junction webhooks → Murph device-sync pipeline
                                                               ↘ backend receipt ledger
Android UI ← source-scoped companion status endpoint ←───────────┘

Explicit Share/Update → Android Contacts edge → bounded pure projection
                                              → address-book CAS endpoint → group friendly labels
Android Settings UI ← server address-book status endpoint ←───────┘
```

## Why a separate native repository

The valuable parity with iOS is behavioral: OTP, session reconciliation, explicit health setup, honest status, and Murph styling. SwiftUI source is not reusable, while the Android implementation is dominated by platform contracts. A cross-platform layer would add another runtime and bridge without reducing the platform-specific work.
