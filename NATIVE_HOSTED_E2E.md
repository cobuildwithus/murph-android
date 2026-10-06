# Protected hosted-native Android E2E

## Production email canary setup

Production canaries select **Use email instead**, request a real first-party
code, receive it through Resend, and enter it through the ordinary code field.
Sign-out and returning login each require a newly received message. No session
or verification record is injected and production auth has no fixed-code bypass.
The legacy PR reset/phone fixture remains unchanged and is not qualification of
first-party signup. SMS delivery and installed-session migration need separate proof.
The historical `initial_privy_otp` / `returning_privy_otp` summary names remain
wire-compatible labels; in production they now report first-party email login.

Configure the `native-android-hosted-e2e-production_canary` GitHub Environment:

| Kind | Name | Value |
| --- | --- | --- |
| Secret | `NATIVE_ANDROID_E2E_LOGIN_EMAIL` | Dedicated persistent canary mailbox. |
| Secret | `NATIVE_ANDROID_E2E_RESEND_API_KEY` | Receiving access in an isolated canary-only Resend account/team. |
| Variable | `NATIVE_ANDROID_E2E_EMAIL_SENDER` | Exact bare mailbox used by production Murph auth email, without its display name. |

Use a separate receiving-only Resend account/team: the Receiving API needs
`full_access`, not `sending_access`, and that key is not recipient-scoped.
**Do not reuse the production sending key or any account containing customer
mail.** Its managed `<inbox-domain>.resend.app` domain avoids custom DNS. Give
iOS and Android distinct local parts, and allow only one live run per identity.
Do not paste credentials into chat, workflow inputs, source, or PR text; install
them directly in protected GitHub secrets. Keep environment/tag approval rules.

Before enabling the pin, link the inbox to the intended dedicated production
test member through the normal account flow, and finish its account onboarding.
Do not create an unrelated member, reset production state, or bypass admission.
Verify the exact sender and a fresh signed email arrive in the receiving account.
Old Privy fixed-code secrets are unused by production and may be removed there
once the new runner is qualified; retain legacy PR configuration separately.

The test runner snapshots the latest 100 message IDs before each request,
polls every three seconds for up to 90 seconds, and requires the exact recipient,
sender, subject, recent timestamp, verified aligned DKIM, and canonical plain-text
code template. Responses are capped at 256 KiB; redirects are rejected. Ambiguous
mail, missing configuration, stale mail, and provider failures fail closed.
The key, message bodies, addresses, and codes never enter retained artifacts.
A full first page can hide mail under unusually high volume; keep this account
canary-only rather than expanding the mailbox scan.

Promotion requires local native verification, the required PR review and main CI,
then a protected live run proving both logins plus the existing health journey.
Only after that proof should the Murph controller's immutable native pin change.
Until protected secrets and identity linkage exist, the implementation is unqualified
for live production use; deterministic tests do not claim actual delivery.

References: [Resend Receiving](https://resend.com/docs/dashboard/receiving/introduction),
[received message API](https://resend.com/docs/api-reference/emails/retrieve-received-email),
[API-key permissions](https://resend.com/docs/api-reference/api-keys/create-api-key).

## Purpose

`.github/workflows/native-android-hosted-e2e.yml` is the protected live lane for
the production Android application graph. It is separate from
`.github/workflows/android-instrumentation.yml`, which remains the synthetic,
no-provider smoke suite and continues to use the `synthetic` build type.

The live lane starts `MainActivity`, uses the production auth and Health
Connect boundaries, and drives the ordinary Compose surfaces. It does not use
`ScreenshotActivity`, fixture state, a fake backend, or another E2E framework.
UI Automator is used only after the production app hands control to Android's
Health Connect system surface.

## Source and deployment binding

The shared backend controller is the only normal dispatcher. Before dispatch it
proves the hosted Web deployment and identity lifecycle, resolves a protected
lightweight Android tag, and requires that tag to resolve to the reviewed full
Android SHA. The private Android workflow independently requires all of these
public inputs:

- `contract_version` (`1`)
- `mode` (`pr` or `production_canary`)
- exact origin-only `web_base_url`
- exact lowercase 40-character `web_sha`
- privacy-safe `correlation_id`
- `dispatch_expires_at`, a backend-issued epoch-second lease
- `identity_lifecycle`
- exact lowercase 40-character `android_sha`
- immutable `android_tag`

A PR run accepts only a non-root `*.vercel.app` origin and
`orchestrator_owned_reset`. A production canary accepts only
`https://www.withmurph.ai` and `non_destructive_existing_identity`. The workflow
must itself be dispatched from the same Android tag and commit; a branch,
mutable source, mismatched tag, or mismatched SHA fails before checkout. The
backend issues a 30-minute dispatch lease. The workflow accepts no lease more
than 35 minutes in the future, rejects an expired lease before checkout, and the
instrumentation process checks the same lease again before launching the live
app. An approval or queued run that outlives its bound deployment therefore
cannot begin the provider journey.

## Protected configuration

The Android repository has two GitHub Environments:

- `native-android-hosted-e2e-pr`
- `native-android-hosted-e2e-production_canary`

The legacy PR environment requires the following configuration (production also
retains the public Privy app variables for installed-session compatibility):

| Kind | Name | Contract |
| --- | --- | --- |
| Variable | `NATIVE_ANDROID_E2E_PRIVY_APP_ID` | Public Android Privy app id for the selected mode. |
| Variable | `NATIVE_ANDROID_E2E_PRIVY_APP_CLIENT_ID` | Public Android Privy client id for the selected mode. |
| Secret | `NATIVE_ANDROID_E2E_PRIVY_LOGIN_IDENTIFIER` | Reusable E.164 non-production phone identity. It must match the backend controller identity. |
| Secret | `NATIVE_ANDROID_E2E_PRIVY_FIXED_OTP` | Exactly six ASCII digits for the protected Privy test identity. |

Environment reviewers and branch/tag deployment rules are the authorization
boundary. Approval must occur while the short-lived dispatch lease remains
valid; approving an older request only starts a fail-closed preflight. Secret
values are never workflow inputs, source text, reports, or artifacts. For each mode, `NATIVE_ANDROID_E2E_PRIVY_APP_ID` must identify the
same Privy application used by the bound hosted Web deployment. In PR mode it
must also equal the shared backend controller's protected app id. The Android
client id must belong to that application and allow the exact CI
package/signing-certificate pair. The PR phone must exactly equal the backend
controller's protected `NATIVE_ANDROID_E2E_PRIVY_TEST_PHONE` value.

For PR mode, the backend controller deletes only the known test identity's
existing rows before the candidate deployment and proves the fresh signup and
post-run state. Production canary mode never deletes or resets the identity.

## Journey and terminal proof

The live driver proves the ordered journey below through production UI and SDK
boundaries:

1. dispatch and runtime configuration validation;
2. real application launch;
3. initial Privy OTP authentication;
4. canonical hosted admission;
5. PR launch-consent recovery and server-owned onboarding continuation, or the
   production canary's existing-member state;
6. Health Connect handoff and system permission state;
7. backend-confirmed connected state;
8. sign-out;
9. returning Privy OTP authentication;
10. returning-member state.

Only one terminal line with the
`MURPH_NATIVE_ANDROID_E2E_STAGE_SUMMARY_JSON:` prefix is accepted. Its schema,
stage order, failure codes, mode, and terminal stage are allowlisted by
`scripts/validate-native-android-e2e-contract.mjs`. The workflow redirects SDK,
Gradle, instrumentation, Privy, and Health Connect prose to an ephemeral raw
file, extracts the one closed summary, deletes the raw file and Android test
results, and then publishes only the validated summary. It does not upload any
artifact.

Missing or malformed configuration, source/deployment mismatch, an unexpected
stage, a missing terminal stage, malformed/multiple summaries, SDK or Gradle
failure, and any test failure all fail closed.

## Rollout and revision rotation

Land and configure the shared-backend controller patch before enabling this
private workflow. The bootstrap pull requests cannot attest themselves because
secret-bearing execution always uses trusted default-branch controller code;
review them independently and run both deterministic Node suites. Then:

1. land the Android patch;
2. create a protected lightweight tag that points directly to the reviewed
   Android commit;
3. set the backend environment's `NATIVE_ANDROID_E2E_ANDROID_REF` and
   `NATIVE_ANDROID_E2E_ANDROID_EXPECTED_SHA` to that exact tag and commit;
4. configure the two Android protected environments and their reviewers/tag
   rules; and
5. enable the backend commit status/canary only after one manually observed
   protected dispatch passes.

Rotate the tag by creating a new protected lightweight tag and updating the
expected SHA together. Never move or recreate an existing protected tag.

## Local deterministic checks

The public contract can be checked without credentials:

```sh
node --test scripts/validate-native-android-e2e-contract.test.mjs
```

The live journey is intentionally not a developer-local command. It requires an
immutable tag, protected GitHub Environment, reusable provider test identity,
and a backend-attested hosted deployment.

## Build variants and device limits

`MURPH_ANDROID_TEST_BUILD_TYPE` defaults to `synthetic`, preserving the ordinary
smoke suite. Its existing screenshot smoke test lives in the synthetic-only
instrumentation source set, so neither live variant compiles or packages
`ScreenshotActivity`. The protected workflow explicitly selects the
non-shipping `hostedE2E` build type for PR candidates and `productionCanary` for
production-origin canaries. `hostedE2E` copies the ordinary debug application
configuration without the debug fixture source set. `productionCanary` keeps
release application configuration. Both are debuggable and debug-signed so CI
never downloads or persists a Play signing key. The corresponding Privy Android
configuration must allow each exact CI package/signing-certificate pair.

The managed Pixel 6 API 35 Google image proves Android framework/system-UI
handoff and permission state. It does not prove Play-distributed signing,
manufacturer-specific Health Connect UI, physical sensor history, background
collection, or device-specific provider behavior. Those remain physical-device
release checks. This lane deliberately does not change Privy freshness,
foreground Health ordering, or backfill behavior.
