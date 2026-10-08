# Native messaging SDK completion

Status: one-flow simplification and accepted findings corrected; fresh ReviewGPT pending

Backend PR 4059 owns credential policy and fresh single-use Telegram proofs.
It merged as 5cc4e681c4b0a90c4f15163911135d61f44b2676; production admission,
deployment and all 14 unauthenticated auth smoke checks passed. Android PR 52
remains stacked on PR 51; both now inherit the trusted ZIP review tooling from
PRs 53, 54 and 55. Product ReviewGPT and current-head hosted checks remain merge gates.

## Product behavior

Default phone and SMS-code controls reuse login styling. Send code is followed
by an or divider and outline Connect Telegram. The official SDK is unmodified.
A successful Telegram link continues setup exactly like a phone link. The app
gates only on the backend's messagingSetupRequired, which already treats a
linked Telegram account that awaits the member's first message as set up. When
the bot cannot message first, onboarding's final Message Murph on Telegram opens
the chat; there is no separate say-hi step, readiness polling or client hold.
Progress renders inline in the content flow and never covers a control. Admission
after an SDK login runs in the application scope, like the SMS path.

The installed signing certificate selects the Play or upload redirect at
runtime; both hosts have verified App Link filters. Tests cover both known
certificates and reject unknown/missing/multiple signers and wrong packages.
The published assetlinks files were checked against both expected fingerprints.
No bot-link token, recipient proof, bot code or custom return scheme remains.
Proof, phone and SMS code stay in memory. SDK callbacks, timeout and explicit
Cancel end pending login; member/session and revision fences reject late results.

## Build and review boundaries

CI uses only the built-in GitHub token with contents/packages read permissions
and github.actor as the Maven username. Local Gradle receives the read:packages
gh token only in its process environment. No package-token secret is required.
Cross-organization resolution passed locally and in hosted CI. The official
SDK POM omits license metadata; the existing policy pins its MIT fallback to
an immutable official source LICENSE.

The independent control-plane review for PR 53 covers the exact 0.5.153 pin,
lockfile integrity, package-specific age exception, disabled lifecycle scripts,
guarded tracked-source ZIP, capture validation and narrow workflow token wiring.
PR 52 inherits those controls unchanged. Run normal ZIP ReviewGPT in a signed-in managed lane
with the fixed prompt and exact PR/head/body context after verification and
final capture. Follow docs/review-workflow.md for failures and dispositions.

## Evidence and remaining work

The remediation capture contains 30 raw ordinary/error/maximum-text states at
source head dfd407699c07cc853386928f6170c58175cff183. All were personally inspected,
including four actual Launching admission states at ordinary and maximum text.
Labels, borders, divider, scroll-end clearance and return to Sign out pass.
Synthetic APK SHA-256:
ae6a21dfdf63234fe8f9ce4e8c217ac75f7aff10e62060d050295cc2abe47708.
Capture metadata, raw image hashes and comparison bounds are retained beside
the evidence. The evidence-only commit changes no application/build inputs.
All 25 corresponding Android/iOS pairs and nine supplemental sheets were also
inspected. Native font scaling and insets differ.

Full verify passed: 619 Debug unit tests and 609 in each other variant, all lint
and assembly checks. All 22 review-tool tests passed. Foreground instrumentation
passed 61 cases with zero failures/errors and one intentional live-journey skip.
The say-hi probe and its regressions were removed with the one-flow change.

Run full verification and synthetic instrumentation in the foreground. Keep
both PRs unmerged until the required reviews/checks pass; merge PR 51 before
PR 52. Do not publish an app or change secrets/canary pins. Real Telegram
approval and App Link return under both certificates, real SMS/autofill,
TalkBack/keyboard on physical devices and signed Play qualification remain
unverified release gates.

## Accepted review corrections

Telegram linking has two phases: Approving (start and SDK login) can be
cancelled; once the proof is submitted, Confirming hides Cancel and always
finishes, so a cancel never reports a link the backend completed as failed. Only
the SDK login is Activity-bound; completion and admission run in the application
scope. Code autofocus keys on the OTP field being visible, so a re-check after
Activity recreation never focuses an unattached field.

A submitted SMS code or Telegram proof whose response is lost returns an Unknown
outcome; the app then re-reads canonical readiness instead of reporting a dead
end, so a link the backend committed still continues setup. Nothing is resent.
A messaging 5xx without a recognized domain code stays indeterminate
(HostedAuthException.Response), not a definitive rejection.

Cancel during the suspended auth observation in requireCurrent now stops the
next SDK launch or completion: auth is observed first, then revision and member
are compared. The PR 51 routing correction keeps messaging setup mounted during
its own Launching re-check. The earlier say-hi probe/lifecycle findings no
longer apply because that step was removed. Native sign-out remains available.
