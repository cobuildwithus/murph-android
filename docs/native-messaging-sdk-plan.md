# Native messaging SDK completion

Status: final evidence and product ReviewGPT pending

Backend PR 4059 owns credential policy and fresh single-use Telegram proofs.
It merged as 5cc4e681c4b0a90c4f15163911135d61f44b2676; production admission,
deployment and all 14 unauthenticated auth smoke checks passed. Android PR 52
remains stacked on PR 51; both now inherit the trusted ZIP review tooling from
PR 53. Product ReviewGPT and current-head hosted checks remain merge gates.

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
PR 52 inherits those controls unchanged. Run normal ZIP ReviewGPT in Hercules
with the fixed prompt and exact PR/head/body context after verification and
final capture. Follow docs/review-workflow.md for failures and dispositions.

## Evidence and remaining work

The earlier 26-state T7 evidence covered ordinary errors and maximum-font full
containment, scroll-end clearance and return to Sign out. The later say-hi
redesign changes visible output, so recapture the final rebased head and replace
stale APK/visual claims before ReviewGPT. Inspect every raw synthetic capture;
never substitute a mockup or include private member data. Compare corresponding
Android/iOS states in supplemental sheets while retaining the raw evidence.

Run full verification and synthetic instrumentation in the foreground. Keep
both PRs unmerged until the required reviews/checks pass; merge PR 51 before
PR 52. Do not publish an app or change secrets/canary pins. Real Telegram
approval and App Link return under both certificates, real SMS/autofill,
TalkBack/keyboard on physical devices and signed Play qualification remain
unverified release gates.
