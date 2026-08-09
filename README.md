# Murph Android Companion

A native Kotlin + Jetpack Compose companion that signs an existing Murph member in and bridges Health Connect into Murph through Junction.

This repository is intentionally narrow. It is not a general Murph mobile client.

## Included

- Privy phone and email OTP sign-in.
- One app-level composition root; no DI framework.
- Explicit app/session and health-sync state machines.
- Junction/Vital Android 5.0.2 with `ConnectionPolicy.Explicit`.
- The complete pinned Vital 5.0.2 Health Connect read surface: 21 centralized resources backed by 29 data-type read permissions.
- Thirty-day foreground backfill within ordinary Health Connect read access.
- Foreground sync on app entry, foreground return, and explicit **Sync now**.
- Backend-confirmed, Health Connect-scoped sync status.
- Native launch-consent recovery for signed-in members when the backend returns structured hosted-consent-required responses.
- Provider-neutral Health Connect setup and recovery guidance.
- Optional, server-backed familiar-name projection for unregistered phone participants in groups.
- Settings, legal links, deletion, support, and sign-out.
- No local health/contact database and no token, health-value, contact-value, or provider-response logging.

## Deliberately excluded

- Automatic meal-photo capture and a Meals tab.
- Chat, vault browsing, challenges, or a general Murph client.
- Direct wearable-provider OAuth.
- A direct Samsung Health SDK integration; supported Samsung Health records may relay through Health Connect.
- Contact backup, continuous/background contact sync, invitations, messaging,
  identity proof, signup prefill, or contact-derived routing authority.
- App-owned Hilt, Room, Retrofit, analytics, and crash-reporting SDKs. Junction
  transitively includes AndroidX WorkManager and its Room
  runtime; Murph defines no Room database or health-value cache.

## First setup

Requirements:

- JDK 17+
- Android Studio with Android SDK 36
- Gradle 8.11.1
- Android Gradle Plugin 8.10.1 (pinned)
- Kotlin 2.1.20+
- Node.js 24.14.1 for repository verification and review tooling

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

The native UI smoke suite launches the same debug-only synthetic scenarios used
for exact-head visual evidence, renders the production Compose surfaces, and
asserts their semantics without pixel snapshots. A uniquely packaged synthetic
build variant uses a plain `Application`, removes AndroidX Startup providers,
and has no network, Contacts, or Health Connect data permissions. It therefore
cannot initialize Privy, Junction, member storage, or network work. Run it on
an attached device with:

```bash
./gradlew connectedSyntheticAndroidTest
```

For the deterministic Pixel 2 / API 30 automated-test device used by GitHub
Actions, run:

```bash
./gradlew pixel2Api30SyntheticAndroidTest
```

On hosts without hardware rendering, append
`-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect`.
`Android Instrumentation / synthetic-ui-smoke` provisions that managed device
and runs the suite for pull requests and `main`.

Debug builds also include a deterministic screenshot activity for visual
comparison without using a real account or health data. Supported `scenario`
values are `login`, `email`, `otp`, `setup`, `awaiting`, `synced`, `delayed`,
`attention`, `consentRequired`, `consentLoadFailure`, and `failure`.

Every PR that changes a shipped path under `app/src/main/` or
`app/src/release/` must include current emulator PNGs from the exact pushed
head. Keep them under `app-store-assets/review-evidence/<feature>/`, embed their
exact-head raw GitHub URLs in the PR's `Android visual proof` section, and name
physical-device-only gaps. Use only debug synthetic fixtures and raw 8-bit RGBA
emulator screenshots. Inspect the pixels and keep no text, profile, Exif, or
private metadata.

`Android Visual Proof / verify` uses a base-owned `pull_request_target`
workflow that executes only the trusted base verifier while inspecting the
candidate as data. A PR cannot weaken the gate and certify itself. The bootstrap
PR needs independent review because its base does not yet contain the workflow.
Run its contract tests locally with:

```bash
node --test scripts/check-android-visual-proof.test.mjs
```

Health Connect and Junction behavior must still be tested on physical Android
devices. At minimum test one Pixel and one Samsung device with a real connected
health source. The synthetic instrumentation suite does not claim SDK or
provider coverage.

## ReviewGPT

The repository includes the same pinned, managed-browser ReviewGPT workflow as
`murph-ios`, with an Android-specific production review prompt:

```bash
pnpm install --frozen-lockfile
pnpm review:verify
pnpm review:gpt android-review --wait   --response-marker ANDROID_REVIEW_COMPLETE   --response-file output-packages/android-review-response.md   --prompt "Review exact committed head: $(git rev-parse HEAD)"
```

Review the exact committed head with a clean worktree. Resolve accepted
findings, rerun Android verification, commit the remediation, and repeat until
the response reports `REVIEW_OUTCOME: PASS`.

## Data requested

`JunctionHealthSyncService` explicitly enumerates every `VitalResource` exposed
by the pinned Vital 5.0.2 Health Connect SDK:

```kotlin
setOf(
    VitalResource.Profile,
    VitalResource.Body,
    VitalResource.Workout,
    VitalResource.Activity,
    VitalResource.Sleep,
    VitalResource.Glucose,
    VitalResource.BloodPressure,
    VitalResource.BloodOxygen,
    VitalResource.HeartRate,
    VitalResource.Water,
    VitalResource.HeartRateVariability,
    VitalResource.MenstrualCycle,
    VitalResource.Steps,
    VitalResource.ActiveEnergyBurned,
    VitalResource.BasalEnergyBurned,
    VitalResource.FloorsClimbed,
    VitalResource.DistanceWalkingRunning,
    VitalResource.Vo2Max,
    VitalResource.RespiratoryRate,
    VitalResource.Temperature,
    VitalResource.Meal,
)
```

The set is intentionally explicit even though the SDK exposes `values()`: a
unit test compares the two, so a dependency upgrade cannot silently broaden
permissions or Play declarations. The Android manifest declares the 29
record-type read permissions required by this pinned set. Users still choose
each category in the Health Connect system UI; denied categories remain
unavailable and do not block categories the user approved. No write permission
is requested.

Vital 5.0.2 scans all resources during permission reconciliation and its
`remapped()` operation is an identity operation in this version. Murph pauses
SDK synchronization before permission and through `connect()`, then unpauses
only around an explicit foreground call with the configured-and-granted
intersection. An empty intersection is a no-op. Shared permissions can activate
multiple SDK resources, so the app declares the relevant aggregate owners
explicitly. Actual source availability and backend receipt remain physical-device
release gates; this permission surface does not claim that every source exports
or Murph ingests every requested category.

Junction/Vital Android 5.0.2 hard-clamps backfill to the ordinary 30-day Health
Connect read window, so the app does not request broader history access with no
reachable benefit. Background reads, boot receivers, and exact-alarm
synchronization are intentionally excluded from this release. See
[`BACKGROUND_HEALTH_AUTHORIZATION.md`](BACKGROUND_HEALTH_AUTHORIZATION.md) for
the durable authorization requirements blocking unattended health work.

### Optional address-book projection

The manifest also declares `READ_CONTACTS` for an optional, foreground-only
Settings action. The app explains the feature before launching Android's
permission prompt and reads contacts only after the member chooses **Share**,
**Update**, or **Retry**. A one-shot pass reads person-contact given name,
family name, and at most eight phone values per contact, bounded to 5,000
contacts and 20,000 phone values.

The Contacts edge accepts provider-normalized international values and converts
ordinary national values with a usable two-letter device region. It rejects
extensions, post-dial syntax, letters, and malformed international output. The
app emits at most 1,000 unique friendly labels, each containing up to four
deterministically sorted, case-insensitively deduplicated safe aliases. Each
alias starts with one structurally safe first-name token and may include a last
initial; the combined label remains bounded to 48 characters and 96 UTF-8
bytes. Selection is deterministic by SHA-256 rank.

Contact rows, projected values, and hashes exist only for that request. Murph
does not persist or log them on Android. The server stores no phone numbers in
readable form. Friendly labels are not identity proof; they may appear in group
replies other participants can see, and the feature sends no invitation or
message.

## Connection lifecycle

- The app does not create a Junction connection merely because a member signs in.
- Before showing setup, the app uses the read-only status endpoint to confirm the Privy identity maps to an active, consented Murph member.
- A session restored while offline repeats that validation when Privy becomes
  online-verified, even when Health Connect has never been set up.
- Tapping **Connect Health Connect** first opens the system permission flow.
  After at least one category is granted, the app revalidates the member and
  refreshes the server receipt baseline immediately before requesting a backend
  token with `connectionIntent: "connect"`. If launch consent interrupts that
  continuation, Murph refreshes Health Connect grants and the receipt baseline
  again before connecting, and aborts when either check cannot complete.
- The application session records completed setup, then starts exactly one
  app-owned foreground sync attempt after the setup marker, receipt baseline,
  observation time, and reconnect clearance commit as one restart snapshot.
  SDK automatic synchronization stays paused across permission and connect,
  and the adapter unpauses only for that explicit configured-resource call. A
  failed commit rolls back the live Junction identity. The SDK's reachable
  backfill is already limited to 30 days, so setup does not request an
  extended-history grant.
- Later launches use `connectionIntent: "resume"` only after local setup was completed.
- If omitted or passive `resume` receives
  `SDK_SIGN_IN_RECONNECT_REQUIRED`, Android preserves that typed reason and
  shows **Reconnect Health Connect**. Ordinary refresh remains read-only; only
  that visible action can reach a `connectionIntent: "connect"` request after
  the Health Connect permission flow. Setup revocation and the typed reconnect
  marker commit together, and the marker remains authoritative through token,
  identify, and connection work until final setup commits.
- Reconnecting after all permissions were revoked first removes the previous
  setup marker and receipt, then tears down the old Junction identity before a
  fresh `connect` transaction can begin.
- Foreground return preserves a matching in-flight `connect` transaction;
  sign-out or an authoritative member change still invalidates and tears it down.
- `ConnectionPolicy.Explicit` prevents permission checks from silently reviving a server-side disconnect.
- Every app-triggered foreground sync revalidates the current Privy member and
  backend consent before Junction can read or upload health data.
- When the backend returns structured launch consent required, Murph keeps the
  Privy member session, signs out only the local Junction SDK, strictly loads
  same-origin HTTPS legal and health-data documents in native UI, and posts at
  most the two canonical missing scopes with exact returned document versions.
  `CONSENT_DOCUMENT_VERSIONS_STALE` reloads server truth, partial success is
  retained, and a valid response must make monotonic progress.
- Consent recovery resumes the exact blocked startup, connect, sync, Health
  Connect permission, address-book permission, saved replacement, Stop, or
  exact permission-loss deletion action. A Stop requested while acceptance is
  in flight replaces an older continuation rather than being lost.
- Session, login, permission-launch, sync, retry, consent, and sign-out
  transitions run in the application-lifetime `AppGraph` scope. Activity
  recreation only replaces the renderer; it cannot consume a pending system
  permission launch before the Activity is resumed.
- Returning from a consent document or account-control page reloads consent and
  rechecks the Privy member/account boundary before any paused action resumes.
- “Synced” is rendered only from `GET /api/device-sync/companion/status?sourceProviderSlug=health_connect`.
- A source-scoped receipt must also be at or after the current setup boundary;
  an older Health Connect receipt cannot prove the fresh connection worked.
- Complete local permission revocation renders Not connected even while online
  account verification is temporarily unavailable.
- Login destinations and OTP digits are protected from Android task snapshots,
  and a successful OTP is cleared before the app enters the signed-in session.
- Signing out atomically records a durable pending-sign-out tombstone and
  invalidates health restoration before waiting on other app work. Startup
  finishes Junction-first, Privy-second teardown before any session restore.
- A failed preferences commit restores the pre-call live authorization snapshot,
  so an undurable tombstone or marker removal cannot drive SDK work.
- Address-book Settings state comes from
  `GET /api/device-sync/companion/address-book`; local permission never claims a
  successful share.
- Share and Update preflight the server revision, then request Contacts access,
  reverify the live Privy member before reading, project one bounded list, and
  use a UUIDv4 full-list compare-and-swap replacement. A `409` is surfaced
  without overwriting the newer projection.
- Stop can refetch and delete the latest revision because it only reduces
  sharing. Consent-gated Stop reuses its durable deletion mutation when the
  revision is unchanged. Foreground permission-loss cleanup never requests
  permission or reads contacts, persists only revision plus UUIDv4 mutation id,
  and replays only the exact locally owned deletion after consent is current.
- Sign-out and member switches invalidate contact work and clear the local
  revision/replay metadata so a late completion cannot mutate the next member's
  state.

## Release requirements

An assembled Release APK or AAB is not authorization to publish. Before any
Play upload, run `:app:checkPlaySubmissionReadiness` from a clean checkout at
the exact source commit with the exact signed AAB and the ignored private
operator assertions. The gate rejects synthetic Privy identifiers and
non-production backend hosts, validates the bundle with the build's pinned
bundletool, requires complete per-entry coverage by the approved upload signer,
treats its base manifest as authoritative, requires its SDK, backup/network,
permission, component, and intent-filter security contract to match the local
Release boundary, binds the source, artifact manifest, artifact, and Play packet
by digest, and requires the real vendor, provider-export, and
Pixel/Samsung acceptance evidence. See
`play/README.md` for the fail-closed procedure.

Before a Play release:

1. Apply for Google Play Health Connect access for every one of the 29 declared data-type read permissions.
2. Complete Play Data Safety disclosures, the Health Apps declaration, and the
   Contacts permission disclosure for optional familiar-name projection.
3. Verify the permission-rationale deep link opens the exact production privacy policy.
4. Inspect the exact signed AAB and prove Junction's boot receiver and exact-alarm service remain removed.
5. Verify foreground sync and its notification behavior on Android 13–16.
6. Verify the member's health apps export each product-critical field. Murph cannot manufacture fields that do not reach Health Connect.
7. Verify Contacts grant, denial, permanent denial, app-settings recovery,
   permission revocation cleanup, compare-and-swap conflict handling, and Stop
   deletion on at least one Pixel and one Samsung device.

See `ARCHITECTURE.md`, `IMPLEMENTATION_STATUS.md`, and `SOURCE_BASES.md` before extending the app.
