# Native messaging SDK completion

Status: active

Backend PR 4059 owns credential policy and fresh single-use Telegram proofs.
Android PR 52 stays stacked on PR 51 until backend qualification and PR 51 merge.
Use the official SDK unmodified. Default phone and SMS-code controls match iOS;
Telegram invokes SDK login, then either continues or shows canonical say-hi.
Remove the old recipient-proof/custom-scheme flow completely.

The SDK source and tests are implemented. The human authorized GitHub's built-in
workflow token with packages:read and contents:read, using github.actor as the
Maven username. Local use requires the refreshed gh token to show read:packages;
capture it only in the Gradle process environment. No custom package-token
secret is required or changed. Cross-organization package resolution is not yet
qualified.

The installed package's current signing certificate selects the Play or upload
redirect at runtime, with both hosts declared in verified App Link filters.
Unit tests cover both certificates and unknown/missing/multiple signers and
wrong package names. Both published assetlinks files confirm ai.withmurph.app
and their respective expected certificate. Unknown installed signers fail closed.

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

The fresh independent control review passed the built-in workflow-token update
against the same trusted base checklist: only read permissions, the fixed
registry/module filter, bounded step environments and exact test allowance.
Local auth confirms read:packages. The full Gradle verifier passed its 37
script checks, then the official package POM download returned HTTP 401
Unauthorized with the refreshed token. No app compile/unit-test pass is claimed.
Review-tooling verification passed all 13 checks. CI resolved the SDK with the authorized built-in token and compiled the Debug,
hosted-E2E and production-canary Kotlin targets. Its license inventory then
failed because the upstream POM omits license metadata. Added the exact 1.0.0
MIT fallback using the official immutable source LICENSE; verification continues.
