# Messaging setup confirmation review

The first ZIP review identified that canonical admission moves through Launching,
which replaced messaging setup with the generic loader. The accepted correction
keeps the same messaging composable mounted while its existing refresh flag is
set. Startup, consent recovery, signed-out and failure routing retain priority.
There is no new progress owner or admission policy.

A suspended real AppSession admission test checks the production routing function
for manual confirmation and browser return, then confirmed, pending and failed
outcomes. The Launching screenshot fixture and emulator test verify the visible
capsule and blocked sign-out action. Full verification, 22 review-tooling tests
and 60 emulator tests passed (one intentional protected live-journey skip).
All four recaptured raw images were inspected before commit. Capture metadata
records the exact source head, APK hash and raw image hashes.

Trusted capture validation comes from separately reviewed tooling PR 55. The
old response remains a findings record, not a final-context PASS. A fresh normal
ZIP review and all final-head hosted checks are still required before merge.
No app upload, real account test, canary or secret change is included.
