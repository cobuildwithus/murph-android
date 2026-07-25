# Implementation status

## Implemented in this skeleton

- Complete Gradle project metadata, dependency catalog, manifest, theme, resources, and native package layout.
- Privy initialization, restored auth-state handling, phone/email OTP, identity-token bearer auth, and logout boundary.
- Existing companion sign-in-token and status API client, including an active-member and legal-consent bootstrap check.
- Environment-scoped Junction external-user pseudonym matching `murph-ios`.
- Explicit Junction Health Connect configuration, four minimum-necessary resources, 30-day backfill, manual sync, setup-time history permission, and optional background sync with separately requested background-read access.
- Account-switch, reinstall distrust, stale-session replacement, and fail-closed sign-out safeguards.
- Backend-receipt sync-state derivation with 36-hour and 72-hour thresholds.
- Compose login, setup, status, WHOOP guidance, settings, and failure screens.
- Unit tests for sync-state derivation, external-ID stability, explicit connect/resume behavior, backend receipt truth, and member-switch teardown.

## Executable verification

The project has now been resolved and compiled with JDK 17, Gradle 8.11.1,
Android Gradle Plugin 8.10.1, compile SDK 36, and the real vendor artifacts:

- Privy Android `0.12.0` APIs (`Privy.init`, `getAuthState`, `getUser`, `user.identityToken`, SMS/email OTP, `logout`).
- Junction/Vital Android `5.0.2` APIs (`identifyExternalUser`, `SignInToken`, `VitalHealthConnectManager`, explicit connect, the four-resource WHOOP bridge, and manual/background sync).

`./scripts/verify.sh` passes, including unit tests, Android lint, and debug APK
assembly. The debug APK was installed and cold-launched on a Pixel 8 API 36
Google APIs emulator; the login screen rendered and remained the resumed
activity without an Android runtime crash.

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
- UI screenshot fixtures and instrumentation tests.
- Any local health-value cache or database.
