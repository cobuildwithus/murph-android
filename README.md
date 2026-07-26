# Murph Android Companion

A native Kotlin + Jetpack Compose companion that signs an existing Murph member in and bridges Health Connect into Murph through Junction.

This repository is intentionally narrow. It is not a general Murph mobile client.

## Included

- Privy phone and email OTP sign-in.
- One app-level composition root; no DI framework.
- Explicit app/session and health-sync state machines.
- Junction/Vital Android 5.0.2 with `ConnectionPolicy.Explicit`.
- Four minimum-necessary Junction resources: sleep, workouts, steps, and active calories.
- Optional history permission during setup and background-read permission only when background sync is enabled.
- Optional Junction background synchronization.
- Backend-confirmed, Health Connect-scoped sync status.
- WHOOP → Health Connect setup guidance.
- Settings, legal links, deletion, support, and sign-out.
- No local health database and no token or health-value logging.

## Deliberately excluded

- Automatic meal-photo capture and a Meals tab.
- Chat, vault browsing, challenges, or a general Murph client.
- Direct WHOOP OAuth.
- Samsung Health support before the Health Connect path is proven.
- App-owned Hilt, Room, Retrofit, analytics, and crash-reporting SDKs. Junction's
  background worker transitively includes AndroidX WorkManager and its Room
  runtime; Murph defines no Room database or health-value cache.

## First setup

Requirements:

- JDK 17+
- Android Studio with Android SDK 36
- Gradle 8.11.1
- Android Gradle Plugin 8.10.1 (pinned)
- Kotlin 2.1.20+

The standard Gradle wrapper is checked in. Verify the pinned version with:

```bash
./gradlew --version
```

Configure the public mobile client identifiers in `~/.gradle/gradle.properties`:

```properties
MURPH_PRIVY_APP_ID=your-privy-app-id
MURPH_PRIVY_APP_CLIENT_ID=your-android-app-client-id
MURPH_BACKEND_BASE_URL_DEV=https://linq-webhook-dev.ourrevolution.wtf
MURPH_BACKEND_BASE_URL_PROD=https://www.withmurph.ai
```

Never put the Privy app secret in this app. The Android Privy app client must allow:

- release package: `ai.withmurph.app`
- debug package: `ai.withmurph.app.dev`

Apply the accompanying backend patch before testing. Current Murph `main` rejects `platform: "android"`; the patch also makes sync status source-scoped so an Apple Health receipt cannot make the Android app report Health Connect as synced.

## Build and test

```bash
./scripts/verify.sh
```

The verification script runs unit tests, lint, and assembly for both Debug and
Release. Release tasks fail before compilation when either public Privy
identifier is blank or the production backend URL is not absolute HTTPS.

Debug builds also include a deterministic screenshot activity for visual
comparison without using a real account or health data. Supported `scenario`
values are `login`, `email`, `otp`, `setup`, `awaiting`, `synced`, `delayed`,
`attention`, and `failure`.

Health Connect and Junction behavior must be tested on physical Android devices. At minimum test one Pixel and one Samsung device with a real WHOOP account.

## ReviewGPT

The repository includes the same pinned, managed-browser ReviewGPT workflow as
`murph-ios`, with an Android-specific production review prompt:

```bash
pnpm install --frozen-lockfile
pnpm review:verify
pnpm review:gpt android-review --wait \
  --response-marker ANDROID_REVIEW_COMPLETE \
  --response-file output-packages/android-review-response.md \
  --prompt "Review exact committed head: $(git rev-parse HEAD)"
```

Review the exact committed head with a clean worktree. Resolve accepted
findings, rerun Android verification, commit the remediation, and repeat until
the response reports `REVIEW_OUTCOME: PASS`.

## Data requested

`JunctionHealthSyncService` uses:

```kotlin
setOf(
    VitalResource.Sleep,
    VitalResource.Workout,
    VitalResource.Steps,
    VitalResource.ActiveEnergyBurned,
)
```

This deliberately covers only the first WHOOP bridge use case: sleep, workouts, steps, and active calories.

The manifest declares only the four corresponding Health Connect read permissions. Users still choose each category in the Health Connect system UI. Denied categories remain unavailable and do not block categories the user approved.

The app asks for Health Connect history access during initial setup where supported. Background-read access is separate and is requested only when the member opts into background sync. Junction documents Health Connect backfill as a fixed 30-day window, so the app is configured for 30 days and does not promise broader history.

## Connection lifecycle

- The app does not create a Junction connection merely because a member signs in.
- Before showing setup, the app uses the read-only status endpoint to confirm the Privy identity maps to an active, consented Murph member.
- Tapping **Connect Health Connect** first opens the system permission flow.
  After at least one category is granted, the app revalidates the member and
  requests a backend token with `connectionIntent: "connect"`.
- Later launches use `connectionIntent: "resume"` only after local setup was completed.
- `ConnectionPolicy.Explicit` prevents permission checks from silently reviving a server-side disconnect.
- Foreground sync and background-sync enablement revalidate the current Privy
  member and backend consent before Junction can read or upload health data.
- “Synced” is rendered only from `GET /api/device-sync/companion/status?sourceProviderSlug=health_connect`.
- Signing out tears down the local Junction SDK before ending the Privy session.

## Release requirements

Before a Play release:

1. Apply for Google Play Health Connect access for sleep, exercise, steps, active calories, history, and optional background reads.
2. Complete Play Data Safety disclosures and the Health Apps declaration.
3. Verify the permission-rationale deep link opens the exact production privacy policy.
4. Review the merged manifest; Junction adds foreground-service and boot-receiver declarations.
5. Verify the foreground-sync notification and optional exact-alarm flow on Android 13–16.
6. Verify WHOOP actually exports each product-critical field. Murph cannot manufacture fields WHOOP does not write.

See `ARCHITECTURE.md`, `IMPLEMENTATION_STATUS.md`, and `SOURCE_BASES.md` before extending the app.
