# Google Play release packet

This directory is the code-derived starting point for a Google Play submission. It deliberately separates facts that the repository can prove from declarations that an authorized Play Console operator must verify.

The checked-in packet does **not** mean the app is ready to publish. `play/operator-assertions.json` is ignored and must be completed for the exact release artifact and Play Console packet outside Git. Never put credentials, signing material, private contract text, account identifiers, or reviewer login details in this repository.

The Android source contains canonical account admission, but source presence is not runtime proof. Submission remains blocked until a Murph backend compatible with lifecycle-neutral admission is deployed and the exact signed candidate proves new-account admission end to end.

The current source-derived health scope is four centralized `VitalResource` values covering four Health Connect data-type read permissions: sleep, exercise, steps, and active calories. The app also requests Health Connect history access where the installed provider supports it, while Junction remains configured for a 30-day backfill. Availability still depends on the member's device, membership, and Health Connect sources; the packet does not claim that every source exports every requested type.

## What is here

- `release-facts.json`: machine-readable package, permission, Health Connect, legal-link, and operator-gate snapshot.
- `listing/en-US/`: truthful English listing copy and release notes. Visual assets are intentionally managed by the exact-head visual-proof lane.
- `declarations/data-safety.md`: draft Data Safety answers tied to code paths, plus the vendor facts that still require confirmation.
- `declarations/health-apps.md`: Health Apps and Health Connect declaration copy for every requested data type.
- `declarations/contacts.md`: broad Contacts permission purpose, disclosure evidence, and the upcoming policy decision.
- `release-checklist.md`: signing, testing, Play Console, reviewer-access, staged-rollout, and rollback steps.
- `operator-assertions.example.json`: the private assertions bound to the exact release artifact, its bundletool-extracted manifest, and Play Console packet.

## Verification

Run the source packet and dependency inventory checks during ordinary development:

```sh
node scripts/check-play-release-packet.mjs
node --test scripts/check-play-release-packet.test.mjs scripts/check-third-party-licenses.test.mjs
./gradlew :app:checkThirdPartyLicenses
```

Run the merged-manifest check with release configuration present:

```sh
./gradlew :app:checkPlayReleaseMergedManifest
```

Before creating a release build, an authorized operator must separately confirm that the private Junction agreement covers the exact Android SDK artifacts and distribution. Record only the non-secret result:

```sh
export MURPH_JUNCTION_ANDROID_COMMERCIAL_LICENSE_CONFIRMED=true
```

Then create the ignored assertions file from the example. Set the artifact path to the exact signed AAB intended for upload and provide the approved upload-certificate SHA-256 shown by Play Console. Print the three safe evidence hashes, copy those hashes into the assertions file, and run the strict check:

```sh
cp play/operator-assertions.example.json play/operator-assertions.json
export MURPH_PLAY_RELEASE_ARTIFACT=/secure/path/to/exact-release.aab
export MURPH_PLAY_OPERATOR_ASSERTIONS_FILE=play/operator-assertions.json
export MURPH_PLAY_UPLOAD_CERT_SHA256=...
./gradlew :app:printPlaySubmissionEvidence
./gradlew :app:checkPlaySubmissionReadiness
```

The strict task is the only repository-approved authorization boundary before a Play upload. Every Release artifact embeds a generated source commit, clean/dirty marker, and digest of its public Privy/backend build configuration. The gate snapshots the selected artifact, requires every content entry—including that provenance—to share the approved upload-certificate signer, and checks the signed metadata against its current clean checkout and expected production configuration. The pinned bundletool version used by the Android build validates the exact signed AAB and dumps its authoritative base manifest. A secure JDK XML parser derives that manifest's SDK, backup/network, permission, component-exposure, and intent-filter contract; it must match the local merged Release boundary and checked-in release facts. The gate also requires private assertions for that source commit, artifact-manifest hash, AAB hash, listing, declarations, and checklist; rejects synthetic Privy identifiers and non-production backend hosts; and fails on stale permission/resource facts, missing operator confirmation, unrecognized dependency licenses, or Junction coverage that is not explicitly confirmed for Android. The operator assertions require successful production-package Privy registration, the intended production backend, real provider export, the Pixel/Samsung physical-device matrix, canonical admission, account/onboarding/launch-consent Data Safety review, an explicit system-timezone taxonomy decision, and verified in-app plus external account-deletion paths for the exact candidate.

## Refresh rule

Refresh this packet whenever the merged manifest, SDK graph, requested `VitalResource` set, data paths, legal URLs, account-creation behavior, target SDK, version, listing behavior, or Google Play policy changes. Review the current official policies before every submission; the links in these documents were checked on 2026-08-05 and are not a substitute for the live Play Console requirements.
