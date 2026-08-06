# Agent rules

- Default to deletion and radical simplicity.
- Keep Privy imports inside `auth/` and Junction/Health Connect imports inside `health/`.
- Do not store or log tokens, health values, raw provider payloads, phone numbers, or email addresses.
- Do not render “Synced” from local permission or SDK state.
- Do not replace explicit connect/resume intent with implicit connection creation.
- Do not add Room, Hilt, Retrofit, analytics, or a cross-platform framework without a current requirement that the existing boundaries cannot satisfy.
- Keep all requested Junction resources centralized in `JunctionHealthSyncService` and all corresponding permissions visible in `AndroidManifest.xml`.
- Treat sign-out and member switching as trust boundaries; local Junction teardown must happen first.
- Every PR that changes any shipped path under `app/src/main/` or
  `app/src/release/`, including a rename or deletion, must include a current
  exact-head emulator baseline plus every materially affected visible state in
  the PR body. Keep durable copies under
  `app-store-assets/review-evidence/<feature>/`, and record the evidence
  head. If a state cannot be rendered in the Android Emulator, name the
  physical-device gap instead of substituting a mockup. The
  `Android Visual Proof / verify` check validates the exact-head evidence
  linkage, PNG privacy/structure, and comparison-base reuse constraints. It
  does not generate captures or prove scenario provenance; the independent
  reviewer must confirm that the manually captured state matches the change.
- Capture durable evidence from debug-only synthetic fixtures and inspect every
  image before commit. Keep only safe PNG color/bit-significance chunks and no
  text, profile, Exif, or private metadata. Never use a real account, health
  value, contact, or other private member state.
- Changes to `.github/workflows/android-visual-proof.yml` or
  `scripts/check-android-visual-proof*` require independent trusted review;
  the trusted check rejects those paths and the candidate's copy of the gate
  never certifies itself. The first bootstrap PR relies on independent review
  because its base predates the trusted verifier.
