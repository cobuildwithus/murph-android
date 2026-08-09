# Contacts permission declaration draft

## Current purpose

`android.permission.READ_CONTACTS` supports the optional **Friendly Names** feature. Only after a signed-in member taps Share, Update, or Retry, the app:

1. requests Contacts access in context;
2. reads name and phone rows once in the foreground;
3. keeps a bounded first-name label and normalized phone value on device, dropping unsafe labels and conflicts;
4. sends the minimized projection to Murph's authenticated address-book endpoint; and
5. lets the member replace or stop that projection from Settings.

The app does not write contacts, invite contacts, send them messages, use contacts for identity proof, perform background contact sync, or use the address book for advertising.

## In-app prominent disclosure evidence

Before the action, Settings says that Friendly Names are advisory, may appear to other group participants, do not trigger invitations/messages, and are read only after Share, Update, or Retry. The system permission prompt follows that user action. Capture exact-head reviewer evidence of this screen and the permission request in the separate visual-proof lane.

## Policy decision before release

Google's [Permissions and APIs that Access Sensitive Information policy](https://support.google.com/googleplay/android-developer/answer/16558241) announces a Contacts Permissions policy effective 2026-10-28 and directs apps that do not need broad access to Android Contact Picker.

Friendly Names currently projects a bounded address-book-wide set, which the one-contact picker does not reproduce. Before any submission, and again before that policy's effective date, the authorized policy owner must either:

- confirm and document in Play Console that broad read access is necessary to the listing's promoted Friendly Names core functionality; or
- redesign the feature around user-selected contacts and remove `READ_CONTACTS`.

Do not mark `contactsPolicyUseApproved` true based only on this draft. The decision must use the live policy and exact candidate behavior.
