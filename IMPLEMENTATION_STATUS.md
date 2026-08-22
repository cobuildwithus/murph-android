# Implementation status

## Implemented in this skeleton

- Complete Gradle project metadata, dependency catalog, manifest, theme, resources, and native package layout.
- Privy initialization, restored auth-state handling, phone/email OTP, identity-token bearer auth, and logout boundary.
- Existing companion sign-in-token and status API client, including an active-member and legal-consent bootstrap check.
- Native signed-in launch-consent recovery through the companion legal-consent endpoint, with strict unambiguous `murph.hosted-consent-status.v1` parsing, same-origin HTTPS document links, at-most-two sequential exact missing-scope acceptance bodies, monotonic-progress enforcement, canonical `CONSENT_DOCUMENT_VERSIONS_STALE` reload handling, partial-success retention, and no persisted consent truth.
- Environment-scoped Junction external-user pseudonym matching `murph-ios`.
- Transactional Junction Health Connect setup and permission recovery, including foreground preservation of an owned Connect attempt, final server receipt-baseline refresh immediately before Connect and again after consent recovery, post-consent grant refresh, current-grant classification after either a successful prompt or Vital's repeated-request `NotPrompted` disposition, detail-dependency recovery only when no independent configured resource is active, one atomic restart snapshot for setup authorization and receipt truth, fail-closed rollback on commit failure, authoritative sign-out/member-switch cancellation, the complete pinned Vital 5.0.2 read surface (21 explicit resources and 29 data-type permissions), an explicit 365-day foreground backfill configuration without an extended-history grant (pinned Vital 5.0.2 currently caps its effective local sync-state request at 30 days), and exactly one app-owned foreground sync attempt after setup commits or foreground returns. SDK synchronization remains paused before permission, through `connect()`, and outside those explicit app-owned sync calls.
- Current-member and backend-consent preflight before health uploads, plus account-switch, reinstall, incomplete-setup, stale-session, native launch-consent recovery, and fail-closed sign-out safeguards. WorkManager 2.11.2 initializes on demand through an authorization-aware factory that requires durable authority plus a default-closed process-local lease for the exact validated member. Both its default Startup initializer and Vital's dependent initializer are removed; the adapter obtains guarded WorkManager before creating the Vital manager, so cold/headless reconstruction cannot bypass Murph's factory. The lease separately records launch authorization, successful foreground promotion, and actual delegated resource-worker execution. Backgrounding before promotion rejects the launch before any child reader and leaves the ordinary foreground retry. Backgrounding after promotion cancels and drains the transfer; if a child may have begun, the unclassified durable owner requires explicit reconnect and cannot be cleared by local completion or a partial source receipt. Only qualifying backend evidence after recovery can make Synced eligible. WorkInfo cancellation cannot cross an identity boundary until the pinned worker body's `finally` drains the execution count. Murph replaces only Vital 5.0.2's three-minute `shortService` starter with a visible `dataSync` umbrella while preserving the SDK's real per-resource readers/uploaders. A failed resource does not starve later authorized categories; the starter attempts them and reports aggregate failure, while cancellation or lease revocation stops immediately. Identity teardown atomically records a durable tombstone, revokes the live process lease, cancels every pinned unique Vital work chain, awaits zero actual delegated executions, signs out the SDK, and only then removes durable setup/member authority. The Delete Account action completes that local Junction boundary before opening the external deletion resource; an abandoned flow must pass fresh backend admission before health work. Process reconstruction finishes Junction-first, Privy-second teardown before restoration.
- Offline-to-online restoration revalidates backend membership and consent before setup, including sessions with no prior Health Connect marker.
- Fault-injected preferences coverage proving failed durable revocation and sign-out commits restore their process-visible authorization snapshot.
- Current-setup backend-receipt sync-state derivation with 36-hour and 72-hour thresholds, including rejection of receipts predating the setup boundary and an actionable no-receipt state after 72 hours.
- Offline-safe local permission reconciliation so complete revocation overrides cached backend status and exposes reconnect without starting network or SDK health work.
- Optional address-book familiar-name projection with an explicit Settings
  consent surface, one bounded Android Contacts read, strict international
  phone/name sanitization, deterministic conflict-safe selection, server CAS
  replacement, live-member revalidation before each contact read,
  consent-aware preflight/replacement/Stop continuations, durable exact-revision
  permission-loss deletion replay, and no persisted contact values.
- Compose login, setup, provider-neutral status guidance, consent, settings, and failure
  screens matched to the shared `murph-ios` visual system.
- Debug-only deterministic visual fixtures for phone login, email login, OTP,
  setup, waiting, synced, delayed, attention, consent required, consent load
  failure, and failure states.
- Exact-head visual-proof enforcement for every shipped `main` or `release`
  app-path change, using a base-owned verifier, strict raw-emulator PNGs,
  durable evidence URLs, and an explicit physical-device gap declaration.
- Fail-closed Play submission readiness bound to a clean source commit and the
  exact signed AAB. The pinned bundletool validates that artifact and treats its
  base manifest as authoritative, while the gate requires complete approved
  upload-signer coverage and an exact SDK, backup/network, permission,
  component, and intent-filter security contract. Synthetic vendor/backend
  configuration is rejected, and private operator evidence is required for
  production Privy registration, real provider export, and the Pixel/Samsung
  device matrix before upload.
- Scrollable compact-height login and OTP layouts, plus an explicit country-picker close action.
- Application-lifetime session and permission-launch ownership across Activity recreation, login task-snapshot protection, safe external-action fallbacks, foreground/retry/acceptance consent-member revalidation with retryable temporary Privy unavailability, and scrollable trust-failure recovery.
- Successful OTP cleanup so a later automatic logout cannot replay the consumed code or redisplay the prior destination.
- Unit tests for sync-state derivation, external-ID stability, transactional connect/resume behavior, strict native launch-consent parsing, canonical stale reload, bounded progress, partial-success retention, exact blocked-action recovery, pre-sync trust checks, cancellation recovery, OTP cleanup, provider availability, complete pinned-resource parity, backend receipt truth, member-switch teardown, address-book projection/API strictness, durable mutation replay, CAS behavior, permission-loss cleanup, operation coalescing, ownership fencing, and rendered Settings state.
- Native instrumentation smoke coverage for login, onboarding, backend-confirmed
  sync, and launch-consent recovery using the production Compose surfaces and
  debug-only synthetic state. The suite uses a Gradle-managed Pixel 2 / API 30
  automated-test device in GitHub Actions. Its isolated build variant removes
  live application startup, network access, and health/contact permissions.

## Executable verification

The project has now been resolved and compiled with JDK 17, Gradle 8.11.1,
Android Gradle Plugin 8.10.1, compile SDK 36, and the real vendor artifacts:

- Privy Android `0.12.0` APIs (`Privy.init`, `getAuthState`, `getUser`, `user.identityToken`, SMS/email OTP, `logout`).
- Junction/Vital Android `5.0.2` APIs (`identifyExternalUser`, `SignInToken`, `VitalHealthConnectManager`, explicit connect, the complete 21-resource Health Connect permission surface, and manual sync).

The visual-proof contract tests pass directly and now run first in
`./scripts/verify.sh`. The prior executable Android verification covered Debug
and Release unit tests, Android lint, and APK assembly. The debug APK was
installed and cold-launched on a Pixel 8 API 36 Google APIs emulator. Phone
login, compact landscape scrolling, and country-picker dismissal were exercised
with the keyboard visible.

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

- Data from a connected health source appears in Health Connect before Murph setup.
- At least one real record reaches the backend for the product-critical activity, sleep, body/weight, and blood-pressure families.
- Every granted Junction resource that the chosen source actually writes reaches the backend without orphaned webhooks.
- Status receives only Health Connect receipts when both iOS and Android sources exist.
- Practical history depth for the configured 365-day Health Connect request, including the pinned SDK's current 30-day effective cap and ordinary-access limits without extended-history permission.
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
- A direct Samsung Health SDK integration; supported data may relay through Health Connect.
- Push notifications beyond Junction's required foreground-service notification.
- Analytics and crash reporting.
- Pixel-baseline screenshot regression tests; native instrumentation covers
  semantic smoke behavior instead.
- Any local health-value cache or database.
- Continuous/background contact sync, contact backup, invites, messaging,
  signup prefill, identity proof, or contact-based routing authority.
