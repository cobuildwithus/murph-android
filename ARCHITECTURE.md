# Architecture

## One sentence

A native Android companion that creates or restores a Murph member, renders server-owned first-run setup, explicitly connects a minimum-necessary Health Connect resource set, optionally projects privacy-minimized contact names, and renders backend-confirmed state.

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
3. **State machines over flag soup.** `AppPhase`, `AuthSessionState`, `InitialOnboardingStage`, `InitialSetupStep`, `HealthConnectAvailability`, `HealthSyncState`, and `AddressBookSharingState` are the vocabularies rendered by UI.
4. **Backend evidence is truth.** Local permission completion means only that the system flow completed. “Synced” requires a backend receipt scoped to `health_connect` that is strictly newer than both the receipt observed immediately before the current setup and that response's server `observedAt`; the observation remains the floor when no prior receipt exists. Setup age, receipt freshness, and relative-time copy use the same server clock, never the device wall clock. If refresh fails, the cached projection is labeled **Last checked online** instead of rendering a frozen sync classification as current. Address-book copy renders only the server status returned by the companion endpoint, never local permission state as success.
5. **Canonical admission before product work.** One neutral phone/email OTP flow serves new and returning members. A current, verified Privy member first calls the dedicated member-fenced companion admission endpoint, which creates or restores only the canonical Murph account and returns exactly `{ "ok": true }`; it never requests a Junction token or grants device authority. Only after admission may status, onboarding, or health setup run, and source-scoped status never substitutes for admission. Offline restoration returns through the same gate. Exact account-conflict responses tear down local member and Junction authority. Structured launch-consent failures enter one native `AppSession` recovery owner, which pauses local Junction authority, strictly validates same-origin HTTPS server documents, accepts only missing launch scopes with all canonical scope documents and exact versions, enforces monotonic progress, reloads `CONSENT_DOCUMENT_VERSIONS_STALE`, and resumes the exact blocked action after the backend reports launch consent granted.
6. **Explicit, transactional lifecycle.** Account admission never touches Junction. First setup requests system permission before obtaining a `connect` token or identifying Junction, while known same-member passive restoration sends `resume`. Neither path may reverse durable stopped/disconnected state. The exact typed reconnect response atomically revokes the old setup snapshot and records **Reconnect Health Connect** across process recreation; only that visible action may proceed to `connect`, and its typed authority remains until final setup commits. Permission recovery durably revokes the prior setup and tears down its Junction identity before a fresh `connect`. Every connect attempt refreshes the server receipt baseline immediately before its token request, repeating after a consent detour; a consent-interrupted continuation also refreshes current Health Connect grants before it can identify or connect. The application-lifetime session owns the optional history-permission continuation, commits the setup marker, receipt baseline, observation time, and reconnect clearance as one restart snapshot only after that prompt resolves, and starts the first sync exactly once. A failed commit restores the prior snapshot and tears down the incomplete live Junction identity. Foreground reconciliation preserves the matching in-flight transaction, while sign-out and member changes invalidate it. An incomplete setup never becomes a durable local Junction identity.
7. **Server-owned onboarding, process-local draft.** After admission, `AppSession` loads `murph.companion.initial-onboarding.v1`. The server owns status, catalog, defaults, contact-card metadata, and handoff actions. Android keeps only the current draft in memory, posts one exact save or skip body, shows Welcome only for `completedNow: true`, and treats stale completion as already resolved. Foreground refresh may remove completed onboarding but never overwrite an in-progress draft. Consent recovery resumes the exact interrupted load, completion, or contact-card request.
8. **Minimal persistence.** SharedPreferences contains an installation UUID, member key, a provisional-admission fence for a first member, one member-scoped forward-only native setup step, server-observed setup timestamp, pre-setup receipt baseline, last qualifying receipt and status-observation timestamps, a typed reconnect-required continuation, pending-sign-out tombstone, address-book server revision, and at most one replacement and one deletion mutation's base revision plus UUIDv4 id. The provisional fence prevents offline restoration until canonical admission commits. The setup step is navigation only: it advances from Health Connect to Friendly Names to Complete after an existing durable success boundary or explicit **Not now**, never substitutes for permission, connection, or server truth, and clears at member trust boundaries. SharedPreferences never stores tokens, onboarding drafts, health values, contacts, phone numbers, names, hashes, lookup keys, consent documents, consent versions, grants, or provider responses.
9. **Trust-boundary teardown.** Account switching, sign-out, backend rejection, and a signed-in Junction SDK without a completed setup marker clear the local Junction session before more health work. Sign-out atomically persists its tombstone and removes durable setup authorization before waiting on app work or touching either SDK. Startup finishes pending Junction-first, Privy-second teardown before auth restoration and clears the tombstone only after both boundaries and member-state removal succeed. A failed preferences commit restores its pre-call live snapshot so undurable values never authorize later lifecycle work.
10. **One foreground-sync owner.** Junction owns Health Connect reads, backfill, its foreground WorkManager chain, and provider upload; `AppSession` owns when an explicit foreground sync may begin. SDK app-start sync stays disabled.
11. **Local permission truth is independent.** Complete Health Connect revocation renders `NotConnected` and exposes recovery even while Privy verification is temporarily unavailable; it never starts health work by itself.
12. **Minimum health scope.** The resource set is centralized in `JunctionHealthSyncService` and mirrored by the manifest. New health categories require a current product need and updated disclosures.
13. **Foreground-only release.** History is requested during connection. Background Health Connect permission, the Junction boot receiver, and its exact-alarm service are removed because Vital 5.0.2 has no pre-worker hook for Murph's durable sign-out authorization.
14. **Explicit one-shot contact projection.** Contacts are read only after Share, Update, or Retry and only after a server-status preflight plus the Android permission flow and a fresh live-member check. Consent-gated preflight, replacement, Stop, and permission-loss cleanup each retain their exact continuation; a durable UUIDv4 mutation is replayed only for its saved revision, while an explicit Stop may refetch and delete the latest server revision. The projector accepts explicit international numbers, emits bounded first-name labels, drops conflicts, and sends a full-list compare-and-swap replacement. Foreground reconciliation never requests permission or reads contacts; when access is lost it may delete only an exact revision this installation still owns. Initial setup exposes the full Friendly Names disclosure once; after Share or **Not now**, Settings remains the discoverable opt-in owner without repeating the setup prompt on Home. This is not identity proof, routing authority, signup prefill, invitation delivery, messaging, background sync, or contact backup.
15. **Process-owned transitions.** `AppGraph` owns the application-lifetime coroutine scope used for session, login, onboarding, permission-launch requests, sync, contact reconciliation, consent recovery, and sign-out work. Activity recreation can replace the renderer without cancelling or consuming an authorized system permission launch. Foreground return, retry, and acceptance recheck the Privy member/account boundary before any continuation. Local token-capture uncertainty remains a typed retryable or read-only state; only an actual backend `401` is authoritative rejection. Temporary unverified state never discards the recovery owner.
16. **Default to deletion.** Add a dependency or abstraction only after the current boundaries cannot express a real requirement.

## Data flow

```text
WHOOP → Health Connect → Junction Android SDK → Junction webhooks → Murph device-sync pipeline
                                                               ↘ backend receipt ledger
Android UI ← source-scoped companion status endpoint ←───────────┘

Explicit Share/Update → Android Contacts edge → bounded pure projection
                                              → address-book CAS endpoint → group friendly labels
Android Settings UI ← server address-book status endpoint ←───────┘

Privy OTP → canonical member admission → initial-onboarding endpoint
Android onboarding UI ← server catalog/status → exact save or skip
```

## Why a separate native repository

The valuable parity with iOS is behavioral: account admission, server-owned first-run setup, consent recovery, session reconciliation, explicit health setup, honest status, Friendly Names, and Murph styling. SwiftUI source is not reusable, while the Android implementation is dominated by platform contracts. A cross-platform layer would add another runtime and bridge without reducing the platform-specific work.
