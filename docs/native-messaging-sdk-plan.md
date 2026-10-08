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
secret is required or changed. Cross-organization package resolution is qualified in hosted CI and locally.

The installed package's current signing certificate selects the Play or upload
redirect at runtime, with both hosts declared in verified App Link filters.
Unit tests cover both certificates and unknown/missing/multiple signers and
wrong package names. Both published assetlinks files confirm ai.withmurph.app
and their respective expected certificate. Unknown installed signers fail closed.

Remaining: final pushed-head capture confirmation,
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
Local auth confirms read:packages. The initial package HTTP 401 was transient;
a fresh authenticated request and full Gradle verification succeeded. Hosted
Android CI also resolved the package with its built-in token and passed.
The official POM omits license metadata, so the existing policy records an
exact-version MIT fallback linked to the immutable official source LICENSE.

Full local verification passed: Debug 609 tests; Release, hosted-E2E,
production-canary and synthetic 599 tests each; no failures or skips. Debug and
Release lint, assembly, merged-manifest checks, license policy and 37 script
checks passed. All five runtime certificate-selection tests pass. Review tooling
passed 13 checks. No production credentials or real members were used.

Inspected fresh synthetic emulator captures for login baseline and all 15
messaging states against iOS captures: phone, SMS code, say-hi, confirming,
invalid number/code, conflict, rate limit, fresh login, approval, network,
Telegram cancellation/unavailable/conflict, and large text. Retired bot-link
screens are deleted. Native font scaling differs at maximum accessibility size;
both screens retain the same control order and scrollable content. The paired
sheets are supplemental comparisons, not substitutes for raw emulator evidence.

## Shared lifecycle correction

iOS review reproduced premature cancellation when returning during unfinished
provider login. Android shared that heuristic; delete it here as well. Scene
activation is not authentication completion. Keep SDK callbacks and the timeout;
explicit Cancel invalidates the current coordinator revision and cancels pending
SDK work. A late start or success cannot link after cancellation. Refresh the
waiting-state visual proof and rerun verification before review.

The correction passes full local verification: Debug 610 tests and 600 tests
in each remaining variant, plus lint, assembly, manifest/license checks and all
13 review-tooling checks. Fresh exact-head visual proof and ReviewGPT remain
required before merge.
