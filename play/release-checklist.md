# Google Play release checklist

Every unchecked item is a release blocker. Keep private evidence in the approved operator system, not in Git.

## Developer account and app

- [ ] Use a verified Google Play **organization** developer account appropriate for a health app. Confirm current requirements in [Choose a developer account type](https://support.google.com/googleplay/android-developer/answer/13634885).
- [ ] Register the exact package `ai.withmurph.app` and verify the package/signing-key status in Play Console.
- [ ] Confirm the public developer name, support contact, organization verification, payments profile where applicable, and distribution countries. Do not copy private account facts here.
- [ ] Complete Ads, Target audience and content, Content rating, Government apps, Financial features, News, and any other live App content declarations truthfully.

## Signing and artifact

- [ ] Enroll the app in Play App Signing and establish a separate upload key following [Android app-signing guidance](https://developer.android.com/studio/publish/app-signing).
- [ ] Keep the keystore and passwords in the approved secret manager/CI boundary. Never commit, print, or pass secret values on a shared command line.
- [ ] Verify the upload certificate fingerprint against Play Console using secret-safe tooling.
- [ ] Set `MURPH_PLAY_UPLOAD_CERT_SHA256` to that exact public SHA-256 for both evidence generation and the final readiness check.
- [ ] Increment `versionCode` monotonically and set the intended `versionName`; update `release-facts.json` and release notes in the same candidate.
- [ ] Supply the public production Privy native-client values and the production HTTPS backend.
- [ ] Deploy a Murph backend compatible with Android's lifecycle-neutral canonical admission request, then prove new-account admission end to end from the exact signed candidate. Do not submit listing copy that advertises account creation until `canonicalAccountAdmissionVerified` is true.
- [ ] Obtain explicit confirmation that the private Junction commercial grant covers the exact Android 5.0.2 artifacts and Play distribution; record only `MURPH_JUNCTION_ANDROID_COMMERCIAL_LICENSE_CONFIRMED=true` for the build.
- [ ] Run `./gradlew :app:testReleaseUnitTest :app:lintRelease :app:checkPlayReleaseMergedManifest :app:checkReleaseThirdPartyLicenses`.
- [ ] Generate a **signed Android App Bundle** with the approved upload key in the signing owner (CI or Android Studio). The repository intentionally contains no signing material.
- [ ] Verify the signed AAB's application ID, version, upload certificate, bundle permissions, supported devices, and absence of debug-only components before upload.
- [ ] Preserve the generated `app/build/reports/licenses/THIRD_PARTY_NOTICES.txt` with the release evidence and include any notice surface required by legal review.

## Store listing and policies

- [ ] Review the checked-in English title, short description, full description, and release notes against the exact candidate. Google currently limits them to 30, 80, 4,000, and 500 characters respectively.
- [ ] Upload phone screenshots and required icon/feature graphics from the separate exact-head visual-proof workflow; confirm they show only synthetic/private-safe data.
- [ ] Verify the privacy, Terms, consumer health notice, AI safety, account deletion, and support links from a signed production build and from Play's crawler context.
- [ ] Complete the Data Safety form from `declarations/data-safety.md` only after confirming backend and SDK behavior, classifying explicit Health Connect lifecycle intent, launch-consent acceptance, and the server-saved avatar/persona/voice/tone choices, and recording an explicit Play taxonomy decision for the system time-zone ID sent during admission and health lifecycle requests.
- [ ] Complete the Health Apps declaration and every Health Connect data-type justification from `declarations/health-apps.md`; confirm the exact candidate still has all 21 pinned resources, 29 data-type read permissions, and no write, extended-history, or background permission.
- [ ] Complete the Play foreground-service declaration for the explicit `dataSync` health transfer and confirm the Console description matches its visible notification and device-to-cloud purpose.
- [ ] Resolve the broad Contacts policy decision in `declarations/contacts.md` against the live policy.
- [ ] Because the candidate performs canonical account creation, verify the signed app's readily discoverable **Settings → Delete Account** path first cancels active health work and signs out Junction, then opens the externally reachable deletion web resource. Abandon the web flow once and prove foreground return requires fresh backend admission before health setup or sync. Verify deletion of associated account data and any clearly disclosed retention. Do not release if either request path is missing or nonfunctional.
- [ ] Point `MURPH_PLAY_RELEASE_ARTIFACT` at the exact signed AAB intended for upload, run `./gradlew :app:printPlaySubmissionEvidence`, record its artifact-manifest/AAB/Console-packet hashes in the ignored assertions file, and re-run `./gradlew :app:checkPlaySubmissionReadiness`.

## Reviewer access and real-device proof

- [ ] Put reusable, non-production reviewer access and English OTP instructions only in Play Console. The instructions must let Google exercise both returning sign-in and canonical first-run account creation without relying on expiring, location-dependent, or privately committed credentials. See [Prepare your app for review](https://support.google.com/googleplay/android-developer/answer/9859455).
- [ ] Demonstrate first-run account admission, launch-consent acceptance, system time-zone transmission, optional contact-card setup, avatar/persona/supporting-persona/voice/tone selection or skip, saved completion, and **Settings → Delete Account** from the exact signed candidate.
- [ ] Demonstrate Health Connect setup, granular selective grants, initial data receipt, permission loss, reconnect, sign-out, and member switch on supported Pixel and Samsung devices.
- [ ] On Android 14–16, exercise a real multi-resource transfer beyond three minutes and confirm the notification remains visible, the service is classified as `dataSync`, and no `shortService` timeout or ANR occurs.
- [ ] On API 31–36, block the starter immediately before foreground promotion, press Home, and release it. Confirm no service or resource read starts and foreground return shows the **Sync now** retry. Then complete promotion before pressing Home and confirm the visible transfer continues to a backend receipt.
- [ ] On API 28–30, commit a sign-out, member-switch, and account-deletion tombstone immediately before process death, allow WorkManager to reconstruct exact Vital work without launching `MainActivity`, and prove Murph's rejecting factory runs with no old-member Health Connect read or Junction receipt.
- [ ] Verify detail-only Health Connect selections name the required Workouts or Menstruation base grant when no usable resource is active. Then verify an unrelated granted resource remains usable despite an orphan detail, and that correcting the detail selection syncs without broadening any unrelated category.
- [ ] Verify real backend receipt for at least activity, sleep, body/weight, and blood-pressure records, and document which requested categories each chosen source app does not export.
- [ ] Demonstrate optional Contacts denial, Share, Update, Retry, permission loss, and Stop on the same device matrix.
- [ ] Confirm unsupported/unavailable Health Connect states and no-background-sync behavior match the listing and reviewer notes.
- [ ] Review Play pre-launch reports, Android vitals, accessibility, crashes/ANRs, policy warnings, device catalog exclusions, and bundle size.

## Rollout and rollback

- [ ] Upload first to internal testing, then the intended closed/open/production track after declarations are approved.
- [ ] Use managed publishing and a staged production rollout with an assigned monitor and explicit halt threshold.
- [ ] Monitor authentication, backend-confirmed device-sync receipts, address-book errors, crashes/ANRs, and Play policy status without logging personal or health data.
- [ ] Keep the prior artifact available for Play rollback/rollout halt and verify server compatibility across both versions.
- [ ] Run `:app:checkPlaySubmissionReadiness` from the clean exact source commit with the signed AAB and private assertions. Do not upload an artifact that has not passed this gate.
- [ ] Record the final bundle hash, version, commit, declaration approvals, test evidence, rollout owner, and rollback decision in the private release record.
