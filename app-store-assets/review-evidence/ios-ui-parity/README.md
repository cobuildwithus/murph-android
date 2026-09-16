# iOS UI parity emulator evidence

These raw Android Emulator captures use only debug-only synthetic fixtures.
The associated PR records the exact evidence head and embeds each file through
an immutable commit URL. All changed renderers were built from the candidate
source; affected states were recaptured after the final visual fixes.

## Environment

- Android Emulator API 36, arm64 Google APIs; 1080 × 2400 at 420 dpi.
- System light/dark appearance; normal text and 1.6× font scale.
- Synthetic app version code 3. No production session or member data.
- Initial visual-capture APK SHA-256: `96da6909bdd51ad349e736b8c4d14020832c9b6ce3417220e64463e0ed648605`.
- PNG structure validated with the trusted visual-proof parser: raw 8-bit RGBA,
  only IHDR, sRGB, sBIT, IDAT and IEND chunks. Every image was visually inspected.

The Journal baseline was recaptured after the transport cleanup fix from
synthetic APK SHA-256 `f2ed4c237d418b8838906b76aac888613f7d534dbfa22bd26bbc6530af9c2e9c`.
The UI renderers are unchanged by that fix. Three additional raw 1080 × 1920
(9:16) captures show welcome, Journal and manual Meals for the Play listing,
using the same synthetic APK and no member data.

The final recovery baseline and offline/preparation states were captured from
synthetic APK SHA-256 `e30563c47bd886160a75edeeeec743fe105f6aee89ee36735adfe48a2e8dcaf4`.
The other captures retain their original rendering evidence; these changes add
recovery eligibility and retained preparation ownership.

## Coverage

Welcome, secure OTP form, contact/personality onboarding, optional reminder,
health setup and consent; Journal filled, empty, loading, failed, unavailable,
stale, calendar and record details; Meals empty, add menu, review, sending,
partial failure and accepted-photo grid; Settings and sign-out confirmation.
Dark appearance and large text include the main destinations and welcome.
Large-text welcome actions remain reachable by scrolling the page.

## Verification and limits

`./scripts/verify.sh` passes: all variant unit tests (577 Debug / 567 Release),
Debug/Release lint and builds, synthetic app isolation, merged-manifest and Play
release tooling checks. Two deterministic transport tests reproduce and prevent success/failure returning before connection cleanup. Session tests cover stale responses, consent recovery,
sign-out cancellation, explicit sends and idempotent partial retries. Device
privacy tests cover JPEG sanitization and encrypted, expiring thumbnail storage.
An offline-restored session test proves explicit Journal refresh revalidates
admission without another foreground event. Preparation tests cover replaced UI
observers, abandonment, member switching, source cleanup, and late results. The
emulator delivers a synthetic picker result, recreates the activity while its
retained callback is blocked, then prepares the real JPEG exactly once without
sending it. This isolates UI result ownership; the session tests separately cover
operation cancellation. Camera providers are excluded from the isolated fixture.
The complete `:app:connectedSyntheticAndroidTest` suite passes with zero failures;
54 tests passed, with the protected live hosted journey intentionally skipped without its protected
configuration.

These images prove rendering of synthetic state. They do not prove production
OTP/admission, backend meal ingestion, real Health Connect/provider exports,
Contacts behavior on physical Pixel/Samsung devices, or camera hardware/OEM
picker handoff. The separately configured hosted journey and physical release
matrix remain explicit gaps; no release-readiness assertion is inferred from
these screenshots. Automatic meal capture is intentionally excluded.

## Capture

Build `:app:assembleSynthetic`, install the synthetic APK, then launch:

```sh
adb shell am start -n ai.withmurph.app.synthetic/ai.withmurph.companion.visual.ScreenshotActivity --es scenario journalFilled -f 0x10008000
adb exec-out screencap -p > journalFilled-light.png
```

Wait for the actual screen to render before capture. Select Meals or Settings
for those fixture states; open the calendar, record and confirmation surfaces
through their normal controls. Restore font scale and night mode after capture.
