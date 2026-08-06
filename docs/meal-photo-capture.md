# Meal photo suggestions on Android

This document defines the behavior, privacy boundary, and release gates for Android's meal photo suggestions. It describes the current implementation. It is not approval to ship the feature before every release gate below is complete.

## User-visible behavior

- Meal photo suggestions are optional and supported on Android 11 and later.
- Enabling it requires **Full Photos access**. Selected-photo or partial access is treated as insufficient.
- Enabling records a MediaStore version and generation cursor before Murph receives upload authority. The feature considers only images inserted after that boundary. It does not scan photos that were already present when the user enabled it.
- The boundary cannot advance while Full Photos access is unavailable. If the feature remains on while access is removed or reduced, photos added during that interval may be checked after Full Photos access returns. This limited backfill is still bounded by the original future-only cursor. The enable screen and active-state screen must disclose this behavior.
- Discovery and classification are automatic while suggestions are on, but upload is not. Every likely meal stays on the device until the user sees its thumbnail and explicitly chooses **Yes, send**.
- Screenshots are rejected before classification or review. Detection uses the MediaStore relative path and display name, so the rejection behavior must be validated across supported device makers. Camera-path hints never authorize an upload.
- Users who do not grant Full Photos access can send meal photos through their existing Murph conversation instead. Meal suggestions do not add a manual gallery picker.

## Why Full Photos access is required

The feature watches MediaStore for new Camera inserts after the user opts in. Android's Photo Picker grants access only to media the user selects. It cannot grant access in advance to future camera photos or notify Murph about those photos without another user selection.

For this reason the manifest requests `READ_MEDIA_IMAGES`, plus the older-device equivalent. On Android 14 and later it also recognizes selected-photo access, but does not treat that access as permission to discover future meal-photo suggestions.

Google Play restricts broad photo access. Before any Play release that includes `READ_MEDIA_IMAGES`:

1. Submit the Photo and Video Permissions declaration.
2. Explain that recurring discovery of future camera photos is the user-enabled core behavior that requires broad access.
3. Explain why the Photo Picker cannot observe or authorize future camera inserts.
4. Provide review instructions and a video that show the opt-in, the Full Photos request, background discovery, local thumbnail review, explicit **Yes, send**, and the Off control.
5. Obtain Play approval. If Play does not approve this use, do not ship the broad permission or background-suggestion feature through that track.

## Local classification and upload privacy

The app uses the bundled ML Kit image-labeling model. Model execution happens on the device. Image pixels and classification labels are not sent to Google by ML Kit. Murph uploads an image only after the user sees the local thumbnail and explicitly approves the review item.

Bundled ML Kit still sends Google diagnostic and usage metrics. Google's current disclosure guidance lists device information, application information, per-installation identifiers, performance metrics, and API utilization for bundled features. The Play Data safety form and the user-facing privacy disclosure must cover the data collected by the exact ML Kit SDK version in the release. They must not imply that the SDK sends meal pixels or labels.

Before a Murph upload, the app:

1. Decodes the selected image into a bitmap.
2. Renders a new, size-bounded opaque bitmap.
3. Encodes a fresh JPEG.
4. Removes JPEG application and comment segments, including EXIF-style metadata.
5. Refuses a sanitized payload larger than 1 MiB.

The original library asset is not uploaded. The capture timestamp is sent as a separate, intentional request field.

The app does not persist a second raw-photo file. Its app-private capture state stores the owner digest, MediaStore cursors, and up to 24 review records. A review record contains a capture ID, the local content URI, MediaStore volume/version/media identity, MIME type, capture time, and status. Records are pruned after 14 days and are removed sooner when dismissed or when successful teardown clears the feature. Review thumbnails are generated in memory from a revalidated Photos asset. These local references are sensitive even though they contain no copied image pixels.

## Scoped authority and teardown

- The backend issues a bearer credential scoped to meal-photo upload. It is separate from the member's identity credential and is stored encrypted with an Android Keystore key.
- Enrollment is bound to the member and this app installation. Before every identity-authenticated enrollment or revocation, the app durably allocates a larger positive schema-v2 authority revision. The backend stores that high-water mark, so a delayed enrollment cannot cross a later Off or consent-recovery revocation.
- A schema-v2 identity-authenticated POST to the enrollment endpoint returns a **prepared** scoped credential. That credential is inactive: it is not upload authority, and the backend rejects uploads made with it before activation.
- Android first persists the complete prepared credential durably under its Keystore-backed store. Only after that write succeeds does it send a bodyless PUT to the same enrollment endpoint, authenticated with the prepared scoped bearer. A successful `{ "activated": true }` response promotes that exact credential to active local authority.
- Before renewal POST, Android atomically demotes the current active credential into that same prepared-only slot. A relaunch therefore tries bodyless PUT with the exact retained bearer before issuing another credential. If PUT succeeds, the prior active bearer is restored locally; if PUT returns 401, that exact bearer is no longer current and a larger schema-v2 authority revision may safely prepare its replacement. A POST response lost before local persistence contains only a server-inactive prepared bearer, so a later larger revision safely replaces it.
- Local authorization, scheduling, processing, and upload cannot become usable before activation succeeds. Discovery remains review-first after activation: every upload still requires the user to inspect the local thumbnail and explicitly choose **Yes, send**.
- If the app stops after persisting the prepared credential, or the activation response is ambiguous, it retries the bodyless PUT with the same stored bearer. Exact-token activation retries are idempotent, so recovery does not create or expose a second credential.
- Scoped bearer self-revocation remains a bodyless DELETE to the enrollment endpoint and does not allocate a revision. Activation PUT and scoped DELETE serialize on the server and recheck the exact bearer: PUT followed by DELETE ends revoked, while DELETE followed by PUT makes activation fail. The app must preserve that server ordering and keep local authority closed whenever the final activation result is not confirmed.
- A stable per-generation idempotency secret acts as the local capture salt. The app retains it across scoped bearer rotation and temporary consent recovery so the same MediaStore item keeps the same capture ID. It is encrypted at rest.
- For sign-out, member switching, consent recovery, and explicit Off, the app closes a durable local authorization fence and cancels scheduled work before awaiting a background drain or network call.
- When remote revocation cannot be confirmed, the encrypted old bearer and capture salt remain only as pending-cleanup material. The durable local fence prevents normal processor use, but remote validity remains unresolved until a later cleanup retry succeeds.
- MediaStore metadata, queries, and image decoding share one process-wide daemon execution lane with no waiting queue. Cancellation releases the feature mutex immediately. If a device provider or native decoder ignores cancellation and strands that lane, later media operations reject and the feature fails closed instead of accumulating blocked threads or resuming capture.

Consent recovery and explicit Off are different states:

- **Consent suspension** preserves the future-only generation, cursors, and capture salt. Only the exact consent continuation may restore upload authority after the required consent is accepted.
- **Off** is a durable user decision. It clears the capture configuration immediately and removes the local capture salt after successful remote teardown. If remote revocation is unresolved, encrypted cleanup material is retained without reopening the feature. Foreground refresh and ordinary retries must not turn the feature back on. Enabling it again requires a new explicit opt-in and a new future-only boundary.

## Background and reboot behavior

While enabled, the app enqueues unique periodic work with network-connected and battery-not-low constraints. The interval is six hours. Foreground checks can also process new candidates immediately.

This release intentionally removes `RECEIVE_BOOT_COMPLETED` from the merged manifest because the app also removes Vital's boot receiver and exact-alarm service. As a result, meal-photo work is not guaranteed to continue across a device reboot. The next valid foreground app session re-arms periodic work.

Store copy, support material, and release notes must describe this as periodic background checking with foreground re-arm. They must not call it continuous, real-time, always-on capture, or automatic upload.

## Remaining iOS parity gap

This Android release does **not** ship iOS-style automatic meal-photo upload. Android MediaStore does not expose an immutable screenshot or camera-origin subtype that is strong enough to authorize a background upload. Mutable paths, filenames, and owner-package hints are used only to reject obvious screenshots or inform testing; they are never upload authority.

Automatic upload therefore remains an explicit Android parity gap. Any future implementation needs a separately reviewed provenance design, updated user disclosure and Play/Data Safety review, a physical-device precision corpus, and new release approval. It must not be enabled by relaxing the review-first rule in this implementation.

## Release gates

Do not enable production rollout until all of these gates pass against the exact release build:

- **Future-only proof:** photos present before opt-in never reach classification, local review, or upload. Test same-second inserts, MediaStore version changes, removable-volume replacement, permission loss, and restoration.
- **Permission proof:** test Full, partial, denied, permanently denied, settings recovery, Off, and an access-off interval. Confirm the UI discloses possible post-restoration checking of photos added during the interval.
- **Upload-authority proof:** prove that a schema-v2 POST credential cannot upload before activation; prepared state is durable before PUT; crash and ambiguous-response recovery reuse the exact stored bearer; local authorization, scheduling, processing, and upload remain closed until activation is confirmed; replacement and revoked bearers are rejected; revocation tombstones retry safely; sign-out and member switching cannot upload; and explicit Off survives process death.
- **Ordering-fence proof:** deploy the schema-v2 backend fence and two-phase activation contract first, then prove that a delayed lower-revision identity enrollment cannot cross a higher-revision revocation and that racing bodyless PUT/DELETE operations produce the server-ordered final state. The fence-aware, two-phase backend is the rollback floor after Android sends its first schema-v2 request.
- **Cancellation proof:** on representative devices, cancel or disable while MediaStore metadata, a query, and image decoding are active. Teardown must finish without waiting for uncooperative native/provider work, and a stranded media lane must reject later work without spawning another thread.
- **Sanitization proof:** decode each uploaded fixture and verify that the fresh JPEG contains no application or comment metadata segments and stays within the byte limit.
- **Reboot proof:** verify that no boot receiver is present in the merged release manifest, no meal upload runs after reboot before the app is opened, and a valid foreground session re-enqueues the periodic work.
- **Play policy approval:** receive approval for the broad Photos declaration before production distribution on Google Play.
- **Data safety review:** update the Play Data safety form and privacy copy for ML Kit's current diagnostic and usage collection.
- **Device suggestion corpus:** run the bundled release classifier on a consented, non-production corpus across representative Android versions, camera apps, and device makers. Include camera meals, camera non-meals, screenshots, downloads, edited images, and non-camera meal images in every supported format. Record the build, device, OS, ML Kit version, path classification, label scores, and final local action. Product and privacy owners must approve suggestion quality, screenshot rejection, and the complete absence of pre-boundary suggestions or any upload without explicit **Yes, send**. Unit-test fixtures alone do not satisfy this gate.

## Official references

Android Photo Picker:
https://developer.android.com/training/data-storage/shared/photo-picker

Android selected-photo access:
https://developer.android.com/about/versions/14/changes/partial-photo-video-access

Google Play Photo and Video Permissions policy:
https://support.google.com/googleplay/android-developer/answer/14115180

Google Play Permissions Declaration process:
https://support.google.com/googleplay/android-developer/answer/9214102

ML Kit bundled image labeling:
https://developers.google.com/ml-kit/vision/image-labeling/android

ML Kit terms and privacy behavior:
https://developers.google.com/ml-kit/terms

ML Kit Google Play data disclosure guidance:
https://developers.google.com/ml-kit/android-data-disclosure

Android `RECEIVE_BOOT_COMPLETED` reference:
https://developer.android.com/reference/android/Manifest.permission#RECEIVE_BOOT_COMPLETED
