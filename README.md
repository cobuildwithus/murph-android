# Murph Android Companion

A native Kotlin + Jetpack Compose companion that creates or restores a Murph account, completes the shared first-run setup, and bridges Health Connect into Murph through Junction.

This repository is intentionally narrow. It is not a general Murph mobile client.

## Included

- Privy phone and email OTP account entry for new and returning members.
- One app-level composition root; no DI framework.
- Explicit app/session and health-sync state machines.
- Junction/Vital Android 5.0.2 with `ConnectionPolicy.Explicit`.
- Four minimum-necessary Junction resources: sleep, workouts, steps, and active calories.
- Optional history permission during setup.
- Foreground sync on app entry, foreground return, and explicit **Sync now**.
- Backend-confirmed, Health Connect-scoped sync status.
- Native launch-consent recovery for signed-in members when the backend returns structured hosted-consent-required responses.
- Server-owned first-run contact-card, persona, voice, tone, and welcome setup.
- A forward-only native Health Connect → Friendly Names setup sequence with explicit **Not now** choices.
- WHOOP → Health Connect setup guidance.
- Optional, server-backed familiar-name projection for unregistered phone participants in groups.
- Settings, legal links, deletion, support, and sign-out.
- No local health/contact database and no token, health-value, contact-value, or provider-response logging.

## Deliberately excluded

- Automatic meal-photo capture and a Meals tab.
- Chat, vault browsing, challenges, or a general Murph client.
- Direct WHOOP OAuth.
- Samsung Health support before the Health Connect path is proven.
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

Deploy the companion backend from Murph PRs #1296 and #1341 before testing
account creation or first-run setup. Android deliberately reuses the backend's
canonical account-admission and onboarding owners; it does not create a second
signup or catalog source in the app.

## Build and test

```bash
./scripts/verify.sh
```

The verification script runs unit tests, lint, and assembly for both Debug and
Release. Release tasks fail before compilation when either public Privy
identifier is blank or the production backend URL is not absolute HTTPS.

Debug builds also include a deterministic screenshot activity for visual
comparison without using a real account or health data. Supported `scenario`
values are `login`, `email`, `otp`, `setup`, `disconnected`,
`disconnectedUnavailable`, `awaiting`,
`synced`, `delayed`,
`savedStatus`, `attention`, `reconnectRequired`, `consentRequired`, `consentBanner`,
`consentLoadFailure`, `onboardingLoading`, `onboardingContact`,
`onboardingPersona`,
`onboardingSupporting`, `onboardingVoice`, `onboardingTone`,
`onboardingError`, `onboardingSaving`, `onboardingWelcome`, `friendlyNames`,
and `failure`.

Every PR that changes a shipped path under `app/src/main/` or
`app/src/release/` must include current emulator PNGs from the exact pushed
head. Keep them under `app-store-assets/review-evidence/<feature>/`, embed their
exact-head raw GitHub URLs in the PR's `Android visual proof` section, and name
physical-device-only gaps. Use only debug synthetic fixtures. Inspect the
pixels and keep the raw emulator format: opaque 8-bit, non-interlaced RGBA with
exact sRGB/sBIT chunks and no text, profile, Exif, or private metadata.

`Android Visual Proof / verify` uses a base-owned `pull_request_target`
workflow that executes only the trusted base verifier while inspecting the
candidate as data. A PR cannot weaken the gate and certify itself. The bootstrap
PR needs independent review because its base predates the workflow. The check
validates exact-head linkage and reusable-pixel freshness; capture provenance
remains an independent-review responsibility.
Run its contract tests locally with:

```bash
node --test scripts/check-android-visual-proof.test.mjs
```

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

The app asks for Health Connect history access during initial setup where supported. Junction documents Health Connect backfill as a fixed 30-day window, so the app is configured for 30 days and does not promise broader history. Background Health Connect reads, boot receivers, and exact-alarm synchronization are intentionally excluded from this release.

### Optional address-book projection

The manifest also declares `READ_CONTACTS` for an optional, foreground-only
Settings action. The app explains the feature before launching Android's
permission prompt and reads contacts only after the member chooses **Share**,
**Update**, or **Retry**. A one-shot pass reads person-contact given name,
family name, and at most eight phone values per contact, bounded to 5,000
contacts and 20,000 phone values.

Only ASCII international numbers beginning with `+` or `00` and containing
8–15 digits are eligible. The app emits at most 1,000 unique friendly labels,
each containing one structurally safe first-name token and an optional last
initial, bounded to 48 characters and 96 UTF-8 bytes. Conflicting names for the
same normalized number are dropped. Selection is deterministic by SHA-256 rank.

Contact rows, projected values, and hashes exist only for that request. Murph
does not persist or log them on Android. The server stores no phone numbers in
readable form. Friendly labels are not identity proof; they may appear in group
replies other participants can see, and the feature sends no invitation or
message.

## Connection lifecycle

- One neutral OTP flow serves new and returning members. After Privy verifies
  the destination, the app calls the member-fenced companion admission endpoint
  before status, onboarding, or health work. Admission creates or restores only
  the Murph account; it does not mint a Junction token or grant device-sync
  authority. Junction remains untouched until the member explicitly grants
  Health Connect access.
- Every member-bound bootstrap, token, status, consent, onboarding, contact,
  and sync continuation is fenced to the current Privy member. Canonical
  account-conflict responses tear down local member and Junction authority and
  require a different sign-in.
- Pending first-run setup is loaded from the server after admission. Contact
  card, persona, supporting persona, voice, and tone choices remain local draft
  state until one exact save; Skip completes without preferences. First-writer
  completion shows Welcome only to the request that completed setup, while a
  stale completion closes quietly. Foreground refresh can remove completed
  onboarding but never replace an in-progress draft.
- After account onboarding, a member-scoped local navigation step presents
  optional Health Connect as step 1 of 2 and Friendly Names as step 2 of 2.
  Each step advances only after its existing durable completion boundary or an
  explicit **Not now** choice. The step is not permission, sync, or server
  truth; it clears on member trust boundaries and never regresses after setup.
- The app does not create a Junction connection merely because a member signs in.
- Before showing setup, the app completes admission and then uses the read-only
  status endpoint to confirm the Privy identity maps to an active, consented
  Murph member.
- A session restored while offline repeats that validation when Privy becomes
  online-verified, even when Health Connect has never been set up.
- Tapping **Connect Health Connect** first opens the system permission flow.
  After at least one category is granted, the app revalidates the member and
  refreshes the server receipt baseline immediately before requesting a backend
  token with `connectionIntent: "connect"`. If launch consent interrupts that
  continuation, Murph refreshes Health Connect grants and the receipt baseline
  again before connecting, and aborts when either check cannot complete.
- The application session then owns the optional history-permission prompt
  across Activity recreation. It records completed setup and starts the first
  sync only after that prompt resolves or is unavailable. The setup marker,
  receipt baseline, observation time, and reconnect clearance commit as one
  restart snapshot; a failed commit rolls back the live Junction identity.
- Later launches use `connectionIntent: "resume"` only after local setup was completed.
- If omitted or passive `resume` receives
  `SDK_SIGN_IN_RECONNECT_REQUIRED`, Android preserves that typed reason and
  shows **Reconnect Health Connect**. Ordinary refresh remains read-only; only
  that visible action can reach a `connectionIntent: "connect"` request after
  the Health Connect permission flow. Setup revocation and the typed reconnect
  marker commit together, and the marker remains authoritative through token,
  identify, connection, and history-permission work until final setup commits.
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
  most the two canonical missing scopes with every canonical document and its
  exact returned version for each accepted scope.
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
- If that status cannot be refreshed, Android labels the cached projection
  **Last checked online** and never presents its frozen relative time or sync
  classification as a current result.
- A source-scoped receipt must be strictly newer than both the final
  pre-connect receipt baseline and that response's server observation time;
  an older or equal Health Connect receipt cannot prove the fresh connection
  worked, including when the pre-connect response had no receipt.
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
- Initial setup shows the complete Friendly Names disclosure once. After Share
  or **Not now**, Settings remains the discoverable opt-in owner and reuses the
  same explicit foreground consent and one-shot projection.

## Release requirements

Before a Play release:

1. Apply for Google Play Health Connect access for sleep, exercise, steps, active calories, and history.
2. Complete Play Data Safety disclosures, the Health Apps declaration, and the
   Contacts permission disclosure for optional familiar-name projection.
3. Verify the permission-rationale deep link opens the exact production privacy policy.
4. Review the merged manifest and prove Junction's boot receiver and exact-alarm service remain removed.
5. Verify foreground sync and its notification behavior on Android 13–16.
6. Verify WHOOP actually exports each product-critical field. Murph cannot manufacture fields WHOOP does not write.
7. Verify Contacts grant, denial, permanent denial, app-settings recovery,
   permission revocation cleanup, compare-and-swap conflict handling, and Stop
   deletion on at least one Pixel and one Samsung device.

See `ARCHITECTURE.md`, `IMPLEMENTATION_STATUS.md`, and `SOURCE_BASES.md` before extending the app.
