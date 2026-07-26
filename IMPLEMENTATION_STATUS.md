# Implementation status

## Implemented in this skeleton

- Complete Gradle project metadata, dependency catalog, manifest, theme, resources, and native package layout.
- Privy initialization, restored auth-state handling, phone/email OTP, identity-token bearer auth, and logout boundary.
- Existing companion sign-in-token and status API client, including an active-member and legal-consent bootstrap check.
- Environment-scoped Junction external-user pseudonym matching `murph-ios`.
- Transactional Junction Health Connect setup, four minimum-necessary resources, 30-day backfill, app-owned foreground sync, setup-time history permission, and optional background sync with separately requested background-read access.
- Current-member and backend-consent preflight before health uploads or background enablement, plus account-switch, reinstall, incomplete-setup, stale-session, and fail-closed sign-out safeguards. Sign-out durably invalidates setup authorization before Junction teardown begins.
- Backend-receipt sync-state derivation with 36-hour and 72-hour thresholds, including an actionable no-receipt state after 72 hours.
- Compose login, setup, status, WHOOP guidance, consent, settings, and failure
  screens matched to the shared `murph-ios` visual system.
- Debug-only deterministic visual fixtures for phone login, email login, OTP,
  setup, waiting, synced, delayed, attention, and failure states.
- Scrollable compact-height login and OTP layouts, plus an explicit country-picker close action.
- Application-lifetime session work across Activity recreation, stale-result rejection for optional background setup, login task-snapshot protection, safe external-action fallbacks, and scrollable trust-failure recovery.
- Successful OTP cleanup so a later automatic logout cannot replay the consumed code or redisplay the prior destination.
- Unit tests for sync-state derivation, external-ID stability, transactional connect/resume behavior, pre-sync trust checks, cancellation recovery, background setup epochs, OTP cleanup, provider availability, backend receipt truth, and member-switch teardown.

## Executable verification

The project has now been resolved and compiled with JDK 17, Gradle 8.11.1,
Android Gradle Plugin 8.10.1, compile SDK 36, and the real vendor artifacts:

- Privy Android `0.12.0` APIs (`Privy.init`, `getAuthState`, `getUser`, `user.identityToken`, SMS/email OTP, `logout`).
- Junction/Vital Android `5.0.2` APIs (`identifyExternalUser`, `SignInToken`, `VitalHealthConnectManager`, explicit connect, the four-resource WHOOP bridge, and manual/background sync).

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

## Required real-device gates

- WHOOP data appears in Health Connect before Murph setup.
- Each granted Junction resource reaches the backend without orphaned webhooks.
- Status receives only Health Connect receipts when both iOS and Android sources exist.
- Practical history depth within Junction's documented 30-day Health Connect window.
- Foreground, app-resume, reboot, exact-alarm, and battery-restriction behavior.
- Sign-out/account-switch does not leave the prior member's local Junction identity active.
- Pixel and Samsung coverage.

## Deferred

- Meal-photo capture.
- Samsung Health.
- Push notifications beyond Junction's required foreground-service notification.
- Analytics and crash reporting.
- Instrumentation screenshot regression tests.
- Any local health-value cache or database.
