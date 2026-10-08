# Native messaging SDK completion

Status: accepted lifecycle finding corrected and verified; fresh ReviewGPT pending

Backend PR 4059 owns credential policy and fresh single-use Telegram proofs.
It merged as 5cc4e681c4b0a90c4f15163911135d61f44b2676; production admission,
deployment and all 14 unauthenticated auth smoke checks passed. Android PR 52
remains stacked on PR 51; both now inherit the trusted ZIP review tooling from
PRs 53, 54 and 55. Product ReviewGPT and current-head hosted checks remain merge gates.

## Product behavior

Default phone and SMS-code controls reuse login styling. Send code is followed
by an or divider and outline Connect Telegram. The official SDK is unmodified.
Telegram approval either continues after the accepted welcome or shows a
Telegram connected badge, Say hi to Murph, Message Murph, Waiting for your
message… and Use your phone number instead. While resumed, AppSession checks
only the onboarding projection every four seconds and immediately on return,
then re-enters admission once awaiting-inbound clears. Probe failures stay quiet.

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
inspected; say-hi uses merged iOS PR 183. Native font scaling and insets differ.

Full verify passed: 619 Debug unit tests and 609 in each other variant, all lint
and assembly checks. All 22 review-tool tests passed. Foreground instrumentation
passed 61 cases with zero failures/errors and one intentional live-journey skip.
Pause/resume admission success/failure, probe cancellation and sign-out fencing
regressions pass; no new state owner was introduced.

Run full verification and synthetic instrumentation in the foreground. Keep
both PRs unmerged until the required reviews/checks pass; merge PR 51 before
PR 52. Do not publish an app or change secrets/canary pins. Real Telegram
approval and App Link return under both certificates, real SMS/autofill,
TalkBack/keyboard on physical devices and signed Play qualification remain
unverified release gates.

## Accepted review correction

Foreground say-hi probes remain lifecycle-cancellable. A successful probe hands
canonical admission to the existing applicationScope and awaits its independent
child. Pausing cancels only the caller, not admission. The handoff repeats member,
epoch, persisted-owner and pending-sign-out checks, and the existing refresh
flag coalesces duplicate probes. No new scope or state owner is introduced.
Regressions suspend admission across pause/resume for success and actionable
failure, cancel an unfinished probe without admission/health work, and reject a
queued handoff after sign-out. The inherited PR 51 routing correction preserves
the confirming capsule in Launching. Native sign-out remains available.
