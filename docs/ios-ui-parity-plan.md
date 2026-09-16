# Android / iOS UI parity

## Scope

Match the native iOS companion screens and behavior, with automatic meal
capture as the sole excluded feature. Preserve Android Health Connect and
system permission behavior. The Play release remains a draft until a replacement
bundle has been verified.

Reference: merged iOS PR 154 (`ecc574660c6bb185cd4a23d57da1c0bdd32d9f82`),
including its Journal Home evidence. Latest merged iOS main was also checked
at `e608d67`; Journal, Meals and Settings remain the live destinations. Reference screenshots are synthetic; never use member data.

## Work

- [x] Adaptive system light/dark palette, typography and controls.
- [x] Signed-out welcome cards and the existing secure OTP flow.
- [x] Home Journal: date selection, summaries, timeline, record details,
      loading, empty, unavailable and retry states, real authenticated API.
- [x] Meals: explicit camera/picker selection, review/remove, bounded sanitized
      JPEG uploads, progress, accepted receipts and partial retry.
- [x] Verify Personal Patterns scope: retained iOS source and historical evidence
      exist, but latest main exposes it only in the screenshot harness. Do not
      add a new Android navigation destination absent from the reference.
- [x] Settings and onboarding match the reference hierarchy and copy, with
      Android-specific Health Connect and reminder controls retained.
- [x] Session switching, sign-out and consent recovery clear private projections
      and invalidate in-flight requests; no health projection is persisted.
- [x] Focused unit tests, compilation, full verification and synthetic emulator
      journeys in light, dark and large text.
- [ ] Inspect and record exact-head screenshots, open PR, run CI and independent
      ReviewGPT, resolve findings, then create the replacement signed bundle.
- [ ] Replace the saved Play draft and submit the verified release for review.

## Evidence / limitations

The parity implementation passes all 570 Debug unit tests and the repository
verification script (all variant unit tests, Debug/Release lint and assembly,
synthetic fixture isolation, and Play tooling checks). Focused image privacy
tests also verify JPEG metadata removal, dimension/byte bounds, member-bound
Keystore encryption, expiry, and stale-write rejection. The complete API 36 emulator suite passes with zero failures; the protected
live hosted journey is intentionally skipped without its protected configuration.
Thirty-five raw, inspected synthetic screenshots cover the final UI in light,
dark and 1.6× text. See `app-store-assets/review-evidence/ios-ui-parity/`.
Physical Android, OEM/provider and live account journeys remain unverified.
Version code 2 is already uploaded; the replacement uses version code 3.
