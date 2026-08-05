# Implementation status

## Implemented in this skeleton

- Complete Gradle project metadata, dependency catalog, manifest, theme, resources, and native package layout.
- Privy initialization, restored auth-state handling, one neutral phone/email OTP flow for new and returning members, identity-token bearer auth, and logout boundary.
- Canonical missing-account admission through the existing member-fenced sign-in-token transaction with lifecycle intent omitted, the returned Junction token discarded until explicit Health Connect permission, system-time-zone projection, exact account-conflict teardown, and no parallel app-owned signup endpoint.
- Server-owned `murph.companion.initial-onboarding.v1` setup for contact card, main and supporting personas, voice preview, tone, first-writer Welcome, contact handoff, stale-completion handling, foreground removal-only reconciliation, and exact consent-interrupted continuation.
- Native signed-in launch-consent recovery through the companion legal-consent endpoint, with strict unambiguous `murph.hosted-consent-status.v1` parsing, same-origin HTTPS document links, at-most-two sequential missing-scope acceptance bodies containing every canonical document and exact version in each scope, consistently dismissible presentation backed by a reopenable recovery owner, monotonic-progress enforcement, canonical `CONSENT_DOCUMENT_VERSIONS_STALE` reload handling, partial-success retention, and no persisted consent truth.
- Environment-scoped Junction external-user pseudonym matching `murph-ios`.
- Transactional Junction Health Connect setup and permission recovery, including foreground preservation of an owned Connect attempt, post-consent grant refresh before connection, application-lifetime ownership of setup-time history permission, setup-boundary persistence and one first sync only after that prompt resolves, authoritative sign-out/member-switch cancellation, four minimum-necessary resources, 30-day backfill, and app-owned foreground sync.
- Current-member and backend-consent preflight before health uploads, plus account-switch, reinstall, incomplete-setup, stale-session, native launch-consent recovery, and fail-closed sign-out safeguards. Sign-out atomically records a durable tombstone and invalidates setup authorization before waiting on startup or touching either SDK; process reconstruction finishes Junction-first, Privy-second teardown before restoration.
- Offline-to-online restoration revalidates backend membership and consent before setup, including sessions with no prior Health Connect marker.
- Fault-injected preferences coverage proving failed durable revocation and sign-out commits restore their process-visible authorization snapshot.
- Current-setup backend-receipt sync-state derivation with 36-hour and 72-hour thresholds in the server status clock domain, including a strict pre-connect receipt baseline, an actionable no-receipt state after 72 hours, and no device-wall-clock authority over sync truth.
- Typed stopped/disconnected Junction recovery matching iOS: omitted/passive token requests never reactivate server state, the exact reconnect requirement survives process recreation, and only the visible **Reconnect Health Connect** action may continue to a `connect` request.
- Offline-safe local permission reconciliation so complete revocation overrides cached backend status and exposes reconnect without starting network or SDK health work.
- Optional address-book familiar-name projection with an explicit Settings
  consent surface, one bounded Android Contacts read, strict international
  phone/name sanitization, deterministic conflict-safe selection, server CAS
  replacement, live-member revalidation before each contact read,
  consent-aware preflight/replacement/Stop continuations, durable exact-revision
  permission-loss deletion replay, and no persisted contact values.
- A Home Friendly Names setup card after Health Connect is active, reusing the
  same explicit consent and foreground-only projection until server sharing is
  enabled.
- Compose login, first-run setup, status, WHOOP guidance, consent, settings, and
  failure screens matched to the shared `murph-ios` visual system.
- Debug-only deterministic visual fixtures for phone login, email login, OTP,
  setup, explicit reconnect, waiting, synced, delayed, attention, consent required, consent load
  failure, all first-run onboarding stages, and failure states.
- Scrollable compact-height login and OTP layouts, plus an explicit country-picker close action.
- Application-lifetime session and permission-launch ownership across Activity recreation, login task-snapshot protection, safe external-action fallbacks, foreground/retry/acceptance consent-member revalidation with retryable temporary Privy unavailability, and scrollable trust-failure recovery.
- Successful OTP cleanup so a later automatic logout cannot replay the consumed code or redisplay the prior destination.
- Unit tests for sync-state derivation, external-ID stability, transactional connect/resume behavior, strict native launch-consent parsing, canonical stale reload, bounded progress, partial-success retention, exact blocked-action recovery, pre-sync trust checks, cancellation recovery, OTP cleanup, provider availability, backend receipt truth, member-switch teardown, address-book projection/API strictness, durable mutation replay, CAS behavior, permission-loss cleanup, operation coalescing, ownership fencing, and rendered Settings state.
- Unit tests for new-account admission, member fencing, account-conflict teardown,
  system timezone, strict onboarding parsing and request bodies, save/skip,
  first-writer races, draft preservation, contact handoff, and consent-interrupted
  completion.

## Executable verification

The project has now been resolved and compiled with JDK 17, Gradle 8.11.1,
Android Gradle Plugin 8.10.1, compile SDK 36, and the real vendor artifacts:

- Privy Android `0.12.0` APIs (`Privy.init`, `getAuthState`, `getUser`, `user.identityToken`, SMS/email OTP, `logout`).
- Junction/Vital Android `5.0.2` APIs (`identifyExternalUser`, `SignInToken`, `VitalHealthConnectManager`, explicit connect, the four-resource WHOOP bridge, and manual sync).

`./scripts/verify.sh` passes, including Debug and Release unit tests, Android
lint, and APK assembly. The debug APK was installed and cold-launched on a
Pixel 8 API 36 Google APIs emulator. Phone login, compact landscape scrolling,
and country-picker dismissal were exercised with the keyboard visible.

The first executable build required two app-owned corrections: an invalid
Kotlin throw label in the HTTP adapter and one exact transitive Java-resource
exclusion. No vendor API-signature adjustment was required, and no SDK type
leaks beyond the two adapter files.

OTP delivery still requires a Privy Android app client registered for the
debug and release package names. Health Connect synchronization remains a
physical-device gate because an emulator has no member-owned wearable history.

The address-book scope adds no dependency, database, worker, observer, or
background service. Its Android provider edge and permission behavior remain a
physical-device gate; `./scripts/verify.sh` is still required after every
address-book change.

## Required real-device gates

- WHOOP data appears in Health Connect before Murph setup.
- Each granted Junction resource reaches the backend without orphaned webhooks.
- Status receives only Health Connect receipts when both iOS and Android sources exist.
- Practical history depth within Junction's documented 30-day Health Connect window.
- Foreground and app-resume behavior under ordinary battery restrictions.
- Sign-out/account-switch does not leave the prior member's local Junction identity active.
- Contacts permission grant, denial, permanent denial, app-settings recovery,
  and revocation on both a Pixel and Samsung device.
- Server replacement replay, remote-revision conflict, Stop deletion, exact
  automatic cleanup, and visible group labels against the deployed endpoint.
- Provider behavior at the 5,000-contact, 20,000-phone-value, eight-per-contact,
  and 1,000-projection bounds.
- Pixel and Samsung coverage.

## Deferred

- Meal-photo capture.
- Samsung Health.
- Push notifications beyond Junction's required foreground-service notification.
- Analytics and crash reporting.
- Instrumentation screenshot regression tests.
- Any local health-value cache or database.
- Continuous/background contact sync, contact backup, invites, messaging,
  signup prefill, identity proof, or contact-based routing authority.
