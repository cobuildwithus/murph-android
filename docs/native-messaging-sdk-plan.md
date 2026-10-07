# Native messaging SDK completion

Status: active

Backend PR 4059 owns credential policy and fresh single-use Telegram proofs.
Android PR 52 stays stacked on PR 51 until backend qualification and PR 51 merge.
Use the official SDK unmodified. Default phone and SMS-code controls match iOS;
Telegram invokes SDK login, then either continues or shows canonical say-hi.
Remove the old recipient-proof/custom-scheme flow completely.

The SDK source and tests are implemented. Verification currently stops resolving
org.telegram:login-sdk:1.0.0 because no GitHub Packages username/token is supplied.
The preceding 37 script contract checks passed. Required human setup is the
TELEGRAM_PACKAGES_TOKEN repository secret with read:packages, the
TELEGRAM_PACKAGES_USER repository variable, equivalent local Gradle credentials.
No credential values were changed. The public registration file now supplies
app397543190-login.tg.dev, wired as the single property default. A read-only
association check confirms ai.withmurph.app and the 44:E0 certificate, but the
supplied 0E:3B certificate is not yet published by that host. Verify the
intended installed build certificate and App Link resolution before merge.

Remaining: compile and unit/UI verification, exact-head synthetic captures and
side-by-side inspection,
manual ReviewGPT with normal context/response validation, hosted CI, rebase or
retarget after PR 51, then merge. Do not change review-gpt, canary pins or publish
an app. Physical-device SDK approval, real SMS and bot delivery remain unverified.

## Independent credential-wiring review

A fresh read-only reviewer checked settings.gradle.kts and the three affected
workflow credential environments against origin/main revision
3fb40c0c1eae151cdaaf38c40f6edbfff3708308. The narrow workflow contract-test allowance was also independently reviewed.
No code findings: registry scope,
permissions, verification commands and protected E2E dispatch checks are intact.
The human-authorized one-PR-per-repo SDK wiring is the narrow exception to the
normal control-change split; product ReviewGPT remains separately required.
No secrets were added or changed. Fork builds still fail closed without access.
The review-tooling verification passes, including the exact allowed secret
reference and rejection of all other workflow secret references.
