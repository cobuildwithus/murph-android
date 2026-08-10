# Architecture

## One sentence

A native Android companion that creates or restores a Murph member, renders server-owned first-run setup, explicitly connects a minimum-necessary Health Connect resource set, optionally projects privacy-minimized contact names, and renders backend-confirmed state.

## Shape

```text
app/       one composition root and the app/session state owner
auth/      Privy adapter and OTP flow state
api/       tiny authenticated HTTP boundary plus one strict pre-login diagnostic
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
6. **Explicit, transactional lifecycle.** Account admission never touches Junction. First setup pauses SDK automatic synchronization before requesting system permission, then obtains a `connect` token and identifies Junction only after a grant, while known same-member passive restoration sends `resume`. Neither path may reverse durable stopped/disconnected state. The exact typed reconnect response atomically revokes the old setup snapshot and records **Reconnect Health Connect** across process recreation; only that visible action may proceed to `connect`, and its typed authority remains until final setup commits. Permission recovery durably revokes the prior setup and tears down its Junction identity before a fresh `connect`. Every connect attempt refreshes the server receipt baseline immediately before its token request, repeating after a consent detour; a consent-interrupted continuation also refreshes current Health Connect grants before it can identify or connect. The application-lifetime session commits the setup marker, receipt baseline, observation time, reconnect clearance, and initial-step advance as one restart snapshot, then briefly unpauses Vital for exactly one app-owned foreground sync attempt, immediately or on foreground return. A failed commit restores the prior snapshot and tears down the incomplete live Junction identity. Foreground reconciliation preserves the matching in-flight transaction, while sign-out and member changes invalidate it. An incomplete setup never becomes a durable local Junction identity.
7. **Server-owned onboarding, process-local draft.** After admission, `AppSession` loads `murph.companion.initial-onboarding.v1`. The server owns status, catalog, defaults, contact-card metadata, and handoff actions. Android keeps only the current draft in memory, posts one exact save or skip body, shows Welcome only for `completedNow: true`, and treats stale completion as already resolved. Foreground refresh may remove completed onboarding but never overwrite an in-progress draft. Consent recovery resumes the exact interrupted load, completion, or contact-card request. Pending onboarding owns an immersive Home shell without the signed-in tab bar even when consent or reconnect recovery temporarily replaces its content; Settings becomes available after onboarding completes or is skipped. Retryable onboarding failures preserve the draft and stage actions while a persistent bottom status offers the existing sign-out boundary without entering the form. Onboarding-owned consent recovery uses the same sign-out boundary instead of opening Settings.
8. **Minimal persistence.** SharedPreferences contains an installation UUID, member key, one member-scoped forward-only native setup step, server-observed setup timestamp, pre-setup receipt baseline, last qualifying receipt and status-observation timestamps, one member-scoped sync-reminder opt-in with its opaque evidence basis, Android boot count, and absolute elapsed-realtime trigger, a typed reconnect-required continuation, pending-sign-out tombstone, address-book server revision, and at most one replacement and one deletion mutation's base revision plus UUIDv4 id. The setup step is navigation only: it advances from Health Connect to Friendly Names to Complete after an existing durable success boundary or explicit **Not now**, never substitutes for permission, connection, or server truth, and clears at member trust boundaries. SharedPreferences never stores tokens, onboarding drafts, health values, contacts, phone numbers, names, lookup keys, consent documents, consent versions, grants, or provider responses.
9. **Trust-boundary teardown.** Account switching, sign-out, backend rejection, and a signed-in Junction SDK without a completed setup marker clear the local Junction session before more health work. Sign-out atomically persists its tombstone and removes durable setup authorization, fences new app-owned health work, drains the current foreground chain under the health mutex, and only then crosses the Junction identity, Privy member, or consent boundary. Same-member foreground resume uses that mutex and revalidates its epoch, foreground claim, member, setup, authentication, phase, and consent owner before Vital's same-ID identify path may reset SDK identity. Startup finishes pending Junction-first, Privy-second teardown before auth restoration and clears the tombstone only after both boundaries and member-state removal succeed. Terminal backend rejection snapshots durable member ownership before teardown: an established owner retains support, account deletion, and legal controls, while an unbound admission candidate remains support-only. A failed preferences commit restores its pre-call live snapshot so undurable values never authorize later lifecycle work.
10. **One foreground-sync owner.** Junction owns Health Connect reads, backfill, its foreground WorkManager chain, and provider upload; `AppSession` owns when an explicit foreground sync may begin. SDK app-start sync stays disabled.
11. **Local permission truth is independent.** Complete Health Connect revocation renders `NotConnected` and exposes recovery even while Privy verification is temporarily unavailable; it never starts health work by itself.
12. **Reviewed health scope.** The resource set is centralized in `JunctionHealthSyncService` and mirrored by the manifest. It includes sleep, workout, daily activity, steps, active energy, HRV, respiratory rate, blood oxygen, body measurements, height profile, and VO2 max. Vital 5.0.2 globally discovers granted resources after permission and connect flows, but the adapter holds its public synchronization pause across both paths and unpauses only while sending the configured-and-granted intersection to explicit foreground sync. `Activity`, `Steps`, and `ActiveEnergyBurned` remain separate explicit owners matching the shipped grants because this SDK version does not remap the standalone resources. The admitted `activity` summary is the current end-to-end owner for daily steps and active calories; standalone `steps` and `calories_active` uploads are compatibility-preserved client behavior that current Murph defaults normalize away. New health categories require a current product need, proven Junction and Murph ingestion support, and updated Play disclosures.
13. **Foreground-only release.** Background and extended-history Health Connect permissions, the Junction boot receiver, and its exact-alarm service are excluded because the configured SDK reads only the ordinary 30-day foreground window and Vital 5.0.2 has no pre-worker hook for Murph's durable sign-out authorization.
14. **Explicit one-shot contact projection.** Contacts are read only after Share, Update, or Retry and only after a server-status preflight plus the Android permission flow and a fresh live-member check. Consent-gated preflight, replacement, Stop, and permission-loss cleanup each retain their exact continuation; a durable UUIDv4 mutation is replayed only for its saved revision, while an explicit Stop may refetch and delete the latest server revision. The Contacts edge canonicalizes provider-normalized or device-region national phone values to E.164 while rejecting extensions and post-dial values. The pure projector emits bounded first-name labels, preserves the longest deterministic safe prefix of up to four aliases for one number, and sends a full-list compare-and-swap replacement. Foreground reconciliation never requests permission or reads contacts; when access is lost it may delete only an exact revision this installation still owns. Initial setup presents Friendly Names as a compact optional banner over the existing health status and opens the full disclosure only when the member chooses setup or retry. After Share or **Not now**, Settings remains the discoverable opt-in owner without repeating the setup prompt on Home. This is not identity proof, routing authority, signup prefill, invitation delivery, messaging, background sync, or contact backup.
15. **Process-owned transitions.** `AppGraph` owns the application-lifetime coroutine scope used for session, login, onboarding, permission-launch requests, sync, contact reconciliation, consent recovery, and sign-out work. Activity recreation can replace the renderer without cancelling or consuming an authorized system permission launch. Foreground return, retry, and acceptance recheck the Privy member/account boundary before any continuation. Local token-capture uncertainty remains a typed retryable or read-only state; only an actual backend `401` is authoritative rejection. Temporary unverified state never discards the recovery owner.
16. **Content-free auth diagnostics.** Failed OTP sends, resends, and confirmations may emit one best-effort pre-login diagnostic through the application scope. The Privy edge converts SDK failures into closed app-owned categories; only an allowlisted provider machine code and bounded HTTP status may survive. Destinations, OTPs, tokens, identifiers, raw provider prose, causes, persistence, retries, and login-flow blocking are forbidden.
17. **One synthetic UI seam.** Native instrumentation launches `ScreenshotActivity`, which renders real production Compose surfaces from synthetic `AppUiState`. Its uniquely packaged build variant uses a plain `Application`, removes AndroidX Startup providers, and has no network, Contacts, or Health Connect data permissions, so the smoke suite cannot initialize auth, health, member storage, or network work. Tests assert semantic UI state rather than pixel baselines; physical-device SDK and provider behavior remains a separate release gate.
18. **Default to deletion.** Add a dependency or abstraction only after the current boundaries cannot express a real requirement.
19. **Reminder is not sync.** The optional Health Connect reminder derives a remaining duration from the server-observed `NeedsAttention` snapshot, then schedules one inexact elapsed-realtime alarm. Device wall time is never freshness authority. A new opt-in first commits a fresh, current-member `health_connect` status, then atomically persists the preference and its initial monotonic deadline; stale or offline state cannot reset the 72-hour window, while opt-out remains offline-safe. The deadline survives foreground cancellation and offline process recreation for the same evidence basis; a newer candidate may only move an unexpired deadline earlier, while an expired deadline uses the 15-minute reentry floor on the next foreground exit. A cached matching basis from an older Android boot is also treated as due and uses that floor; only a fresh source-scoped backend status may establish a normal new-boot deadline. A new setup or qualifying receipt replaces the basis. `MainActivity` synchronously marks one process-local reminder fence foreground before starting asynchronous session refresh and marks it background before session teardown; the cold-process default is background. Fresh status responses persist their authoritative deadline even while foregrounded, then reconcile delivery only while that fence is backgrounded. When an opted-in setup or reconnect completes, the replacement deadline derived from its final fresh status commits atomically with the new setup snapshot, so process death or stale follow-up state cannot re-anchor the window. An already-dispatched receiver checks the same fence before posting or rescheduling. Foreground exit also restores an eligible local deadline while the UI is still launching. The receiver revalidates the active member preference plus the opaque, installation-salted member/setup/receipt basis before posting generic copy. Cold receiver delivery does not construct `AppGraph`, Privy, or Junction; the sole production graph consumer initializes them lazily when `MainActivity` opens on the main thread. Sign-out, member switch, setup revocation, or opt-out cancels delivery and clears the deadline; foreground return cancels pending and delivered OS notifications without discarding it. Android clears the elapsed-realtime alarm on reboot; because the boot receiver remains removed, Murph restores it only after the next foreground exit. A notification tap is consumed once across the Activity intent and Compose shell, opens Settings, and never reads health data or starts background sync.

## Health-data capability boundary

The expanded scope was reviewed against the client and importer paths below
using Junction/Vital Android 5.0.2 and Murph's Junction importer:

| Product data | Junction resource | Health Connect read permission | Murph ingestion | Provider caveat |
| --- | --- | --- | --- | --- |
| Sleep | `Sleep` | `READ_SLEEP` | `sleep` summary | WHOOP exports sleep. |
| Workouts | `Workout` | `READ_EXERCISE` | `workouts` summary | WHOOP exports exercise. |
| Daily activity summary | `Activity` | Any optional activity record can activate it; this manifest's `READ_STEPS`, `READ_ACTIVE_CALORIES_BURNED`, or `READ_VO2_MAX` is sufficient | admitted `activity` summary | The aggregator consumes permitted activity records such as steps, active energy, and exercise duration. VO2 max can activate the owner but is not a summary input. |
| Standalone steps upload | `Steps` | `READ_STEPS` | `steps` is known but excluded from current default intake | Preserved shipped client behavior; the `activity` summary is the current ingested owner. |
| Standalone active-energy upload | `ActiveEnergyBurned` | `READ_ACTIVE_CALORIES_BURNED` | `calories_active` is known but excluded from current default intake | Preserved shipped client behavior; the `activity` summary is the current ingested owner. |
| HRV | `HeartRateVariability` | `READ_HEART_RATE_VARIABILITY` | bounded `hrv` daily aggregate | Junction supports the record, but WHOOP does not list HRV among its Health Connect exports. Other Health Connect sources may provide it. |
| Respiratory rate | `RespiratoryRate` | `READ_RESPIRATORY_RATE` | bounded `respiratory_rate` daily aggregate | WHOOP exports this for eligible memberships. |
| Blood oxygen | `BloodOxygen` | `READ_OXYGEN_SATURATION` | bounded `blood_oxygen` daily aggregate | WHOOP exports SpO2 for eligible devices and memberships. |
| Body measurements | `Body` | `READ_WEIGHT`, `READ_BODY_FAT` | `body` summary | WHOOP imports rather than exports these values; other Health Connect sources may provide them. |
| Height | `Profile` | `READ_HEIGHT` | `profile` summary | WHOOP imports rather than exports height. |
| VO2 max | `Vo2Max` | `READ_VO2_MAX` | bounded `vo2_max` daily aggregate | WHOOP explicitly does not export VO2 max to Health Connect; other sources may provide it. |

Ordinary heart rate is not requested in this release. Junction can read and
upload the raw stream, but Murph intentionally excludes unbounded intraday
`heartrate` from default ingestion. Vital Android 5.0.2 couples the
sleep/workout supplementary heart-rate permission to discovery and automatic
sync of the standalone `HeartRate` resource, so the app cannot ask for bounded
summary enrichment without also starting that raw path. Vital Android 5.0.2
also has no standalone resting-heart-rate resource, so WHOOP's exported RHR
cannot be ingested through this SDK version without adding a second health-data
owner. Both remain explicit SDK capability gaps rather than broader consent.

Vital 5.0.2 permission reconciliation scans every `VitalResource`, and the
version's `remapped()` operation does not collapse standalone activity
resources into `Activity`. Murph therefore pauses SDK synchronization before
the permission flow and keeps it paused through `connect()`, then unpauses only
around the configured-and-granted foreground call. The shipped `READ_STEPS`
and `READ_ACTIVE_CALORIES_BURNED` grants are preserved, and `Activity`, `Steps`,
and `ActiveEnergyBurned` are all explicit configured resources. All three may
run during that call. Murph's default intake admits the daily `activity` summary
but currently normalizes the standalone `steps` and `calories_active`
timeseries away; those owners remain solely to preserve the already-shipped
client behavior.

WHOOP Recovery and Strain scores are not Health Connect record types and WHOOP
does not list either score among its exported categories. Android therefore
does not add a generic metadata reader or claim score parity. Of the exported
Recovery components, only respiratory rate and SpO2 are eligible through the
reviewed resource set above; RHR remains blocked by Vital Android 5.0.2.

Health Connect's ordinary permission already covers the same 30-day window that
Junction/Vital Android 5.0.2 hard-clamps inside `BaseLocalSyncStateManager`.
Android therefore does not request the broader history permission without a
reachable benefit. This provider-side ceiling cannot produce iOS's 365-day
history; revisit both configuration and consent only after a reviewed SDK
version removes it.

## Data flow

```text
Health apps → Health Connect → Junction Android SDK → Junction webhooks → Murph device-sync pipeline
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
