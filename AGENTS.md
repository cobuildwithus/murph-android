# Agent rules

- Default to deletion and radical simplicity.
- Keep Privy imports inside `auth/` and Junction/Health Connect imports inside `health/`.
- Do not store or log tokens, health values, raw provider payloads, phone numbers, or email addresses.
- Do not render “Synced” from local permission or SDK state.
- Do not replace explicit connect/resume intent with implicit connection creation.
- Do not add Room, Hilt, Retrofit, analytics, or a cross-platform framework without a current requirement that the existing boundaries cannot satisfy.
- Keep all requested Junction resources centralized in `JunctionHealthSyncService` and all corresponding permissions visible in `AndroidManifest.xml`.
- Treat sign-out and member switching as trust boundaries; local Junction teardown must happen first.
