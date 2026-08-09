# Data Safety draft

This is a source-backed worksheet, not an already-submitted Play Console claim. An authorized operator must confirm backend retention, security controls, processor roles, and every third-party SDK's runtime behavior before using it. Google makes the developer responsible for SDK collection and sharing as well as app-owned code.

Official reference: [Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469).

## Code-derived collection paths

| Play data type | Collected off device? | Code-derived path and purpose | Sharing answer |
| --- | --- | --- | --- |
| Personal info — email address | Conditional | A member can choose email OTP; `PrivyAuthService` sends the address to Privy for authentication, and the verified identity can enter Murph's canonical account-creation or returning-sign-in path. | Confirm whether the service-provider exception applies before selecting No. Purpose includes account management. |
| Personal info — phone number | Conditional | A member can choose SMS OTP; `PrivyAuthService` sends the number to Privy for authentication, and the verified identity can enter Murph's canonical account-creation or returning-sign-in path. | Confirm whether the service-provider exception applies before selecting No. Purpose includes account management. |
| Personal info — user ID | Yes after sign-in | A Privy member ID scopes Murph API requests and canonical account admission. A SHA-256-derived external ID scopes the Junction member. Purpose: account management and app functionality. | Confirm processor roles before selecting No. |
| Device or other IDs — app installation ID | Yes during canonical admission and later health lifecycle requests | A random installation UUID is sent to the Murph sign-in-token endpoint before any explicit health connection and again for later health lifecycle control. | Confirm the backend purposes and retention across admission and health lifecycle use before selecting No. |
| App activity — other actions | Yes during health lifecycle, launch consent, and optional first-run setup | Explicit Health Connect `connect` or `resume` intent is sent to Murph's sign-in-token endpoint and affects server lifecycle state. Each accepted launch-consent scope, its accepted document IDs/versions, and the Android companion source are also sent to Murph. The chosen Murph contact-card avatar ID and persona, supporting-persona, voice, and tone IDs are sent to authenticated onboarding endpoints for app functionality and personalization. No user photo or voice recording is uploaded by these flows. | Confirm backend retention, purposes, and whether the current Console asks for another category before selecting the final answer. |
| Operator taxonomy decision — system time-zone ID | Yes during canonical admission and later health lifecycle requests | `ZoneId.systemDefault().id` is sent to Murph's sign-in-token endpoint. The source proves transmission, but not the backend's use or retention. | Do not silently omit or label it. An authorized privacy/Play owner must document its actual backend use and decide against the live taxonomy; current candidate questions include whether it is a listed data type such as Personal info — Other info, or outside the enumerated types. Do not classify it as Approximate location without evidence that it represents physical location. |
| Health info — sleep | Only after explicit permission | Junction reads the selected Health Connect sleep records and sends them through the connected-health pipeline so Murph can use the member-authorized health context. | Confirm Junction's processor role and downstream disclosures before selecting No. |
| Fitness info — exercise | Only after explicit permission | Junction reads selected Health Connect exercise sessions for the same user-facing connected-health feature. | Same confirmation required. |
| Fitness info — steps and active calories | Only after explicit permission | Junction can upload authorized records through `VitalResource.Steps` and `VitalResource.ActiveEnergyBurned`. | Same confirmation required. |
| Contacts — contact names and phone numbers | Only after Share, Update, or Retry | Android contact rows are bounded and minimized on device, then advisory names plus normalized phone numbers are sent to Murph's address-book endpoint for Friendly Names. Purpose: app functionality and personalization. | Confirm Murph's processing/retention and whether any processor disclosure is Play-defined sharing. |

The exact source permission names are `android.permission.health.READ_SLEEP`, `android.permission.health.READ_EXERCISE`, `android.permission.health.READ_STEPS`, `android.permission.health.READ_ACTIVE_CALORIES_BURNED`, and `android.permission.health.READ_HEALTH_DATA_HISTORY`. The history permission is requested where supported, while Junction is configured for a 30-day backfill and the app makes no broader-history promise.

## Security and lifecycle facts the code proves

- Murph API URLs must be absolute HTTPS URLs.
- App backup and device transfer exclude all app storage.
- The app does not persist health values, contacts, phone numbers, names, provider payloads, or identity tokens in its own storage.
- Contact reads are foreground, explicit, bounded, and replace a server-side projection; Stop requests server-side deletion.
- Health access is read-only and starts only after the member launches the system permission flow. SDK app-start sync and the Vital boot/exact-alarm components are removed from this release. Vital 5.0.2 still discovers granted resources globally and may launch its own unfiltered asynchronous sync after permission and `connect()` flows; the app separately starts one app-owned post-commit foreground sync using the configured-and-granted intersection.
- Sign-out and member switching revoke durable local health authorization and tear down Junction before Privy logout.
- Initial-onboarding drafts stay in memory. The server receives only the selected avatar ID for contact-card preparation and the saved persona, supporting-persona, voice, and tone IDs; skip sends no preferences.
- Settings exposes a **Delete Account** action that opens the configured HTTPS deletion resource. Source inspection proves the path exists, not that the external resource completes account and associated-data deletion.

These facts do not prove the backend's encryption at rest, retention schedule, deletion completion, or each SDK's diagnostic/analytics collection.

The final merged manifest also contains SDK/runtime permissions that are not themselves Play user-data types: `android.permission.FOREGROUND_SERVICE`, `android.permission.WAKE_LOCK`, `android.permission.USE_BIOMETRIC`, `android.permission.USE_FINGERPRINT`, and the app-scoped `ai.withmurph.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. `android.permission.INTERNET` carries the declared off-device paths, while `android.permission.ACCESS_NETWORK_STATE` reads connectivity state. Re-evaluate these whenever the resolved SDK graph changes.

## Submission blockers requiring operator evidence

- Review Privy's transitive analytics/client-ID modules and current SDK documentation/configuration. `PrivyLogLevel.NONE` disables logs; it is not evidence that analytics collection is disabled.
- Review Junction/Vital's current SDK and service data practices, including operational identifiers and diagnostics.
- Confirm whether all transmitted data is encrypted in transit across Murph, Privy, Junction, and any downstream service.
- Confirm retention and deletion behavior for Murph's health receipts, address-book projection, account records, and processor copies.
- Confirm the Play definitions of collected, shared, optional, ephemeral, purpose, and service-provider transfer against current contracts and production settings.
- Confirm the exact Play Data Safety classification and purpose for explicit Health Connect lifecycle intent, launch-consent acceptance, and the server-saved avatar/persona/voice/tone choices. Record `accountOnboardingAndConsentDataSafetyReviewed` only after this matches the live form.
- Confirm the backend purpose and retention for the system time-zone ID, then make and record an explicit live-taxonomy decision. The current form does not name timezone, so neither omission nor an invented location classification is acceptable. Record `systemTimeZoneTaxonomyDecided` only after that review.
- Re-run the merged-manifest verifier; any new permission or `VitalResource` requires this worksheet and the Play forms to be updated.
- This candidate contains canonical account creation after OTP verification, but the path is not runtime-proven until a compatible backend is deployed and exact-candidate admission passes. A functional account-creation path requires a readily discoverable in-app deletion path and an external web resource under Google policy. From the exact signed candidate, verify **Settings → Delete Account**, verify the web resource works without requiring the app, confirm it lets a member request account and associated-data deletion, and confirm any legitimate retention is clearly disclosed. Record `accountDeletionFlowVerified` only after both paths and deletion behavior are proven. See [Google Play's account deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111).

Do not mark `dataSafetySdkPracticesConfirmed` or `dataSafetyFormSubmitted` true until every blocker above is resolved for the exact candidate.
