# Health Connect review packet

Status: code-backed declaration draft; physical-device and deployed-service proof
is still required. This document does not assert Google approval or completed
end-to-end testing. Keep all 29 existing read permissions and 21 SDK resources.

## Explain the complete product

Murph's Android companion connects the member's Health Connect records to their
Murph account. The member uses those records in a private wellness conversation
at https://www.withmurph.ai or through their configured messaging channel.
Android provides account setup, permission controls, sync status, and manual sync;
it does not contain a native dashboard for every requested measurement.

Declare the applicable fitness, wellness, and coaching use cases and relevant
health categories in the current Console form. Do not declare diagnosis,
treatment, emergency monitoring, fertility prediction, or clinical decision
support. A broad wellness label alone does not justify every permission.

The following text describes the specific member benefit to demonstrate. For
each row, show the source record in Health Connect, sync it, then show a private
Murph answer that uses that same record. Questions below are synthetic test
prompts, not evidence that a test has passed. Do not paste a claim into the
Console until the exact release and deployed backend can demonstrate it.

## Per-permission explanation and demonstration

All names below have the prefix `android.permission.health.`. Access is read-only.

| Permission | Member benefit and proposed declaration explanation | Demonstration question |
| --- | --- | --- |
| `READ_STEPS` | Use recorded step counts to summarize daily movement and compare activity across days. | How did my steps change this week? |
| `READ_ACTIVE_CALORIES_BURNED` | Use recorded activity energy to compare exercise effort across days without treating it as total daily energy. | Compare my active calories over the last two days. |
| `READ_BASAL_METABOLIC_RATE` | Use the source's resting-energy estimate to explain resting versus activity energy in the member's recorded energy history. | What resting-energy estimate is in my connected records? |
| `READ_TOTAL_CALORIES_BURNED` | Use recorded total energy expenditure when summarizing daily energy history, preserving its distinction from active energy. | Show my total recorded energy expenditure alongside active calories. |
| `READ_DISTANCE` | Use recorded movement distance to summarize how far the member walked or ran and compare days. | How far did I travel on foot this week? |
| `READ_FLOORS_CLIMBED` | Include stair-climbing activity in daily movement summaries; steps alone do not describe vertical activity. | How many floors did my source record this week? |
| `READ_EXERCISE` | Use exercise sessions, types, times, and durations to summarize completed workouts and discuss training history. | Summarize my recent workouts. |
| `READ_ELEVATION_GAINED` | Describe climbing in recorded workouts so the member can compare flat and hilly sessions. | Compare the elevation gain in these two workouts. |
| `READ_POWER` | Use recorded workout power to compare effort in compatible workouts without inferring power from duration alone. | Compare the recorded power in these two rides. |
| `READ_SPEED` | Use recorded workout speed to compare pace across compatible sessions. | Compare my recorded speed in these two runs. |
| `READ_VO2_MAX` | Summarize source-estimated cardio-fitness changes over time, identifying estimates as estimates. | How has my recorded VO2 max changed? |
| `READ_SLEEP` | Use recorded sleep sessions and available stages to summarize duration, timing, and consistency. | Compare my sleep duration and timing this week. |
| `READ_HEART_RATE` | Summarize recorded heart-rate patterns and available workout heart-rate context. | Summarize my recorded heart rate during this workout. |
| `READ_HEART_RATE_VARIABILITY` | Compare recorded HRV with the member's own history for wellness and recovery conversations. | How does my recent HRV compare with my previous week? |
| `READ_RESPIRATORY_RATE` | Summarize recorded breathing-rate trends alongside available sleep and wellness history. | How has my recorded respiratory rate changed? |
| `READ_OXYGEN_SATURATION` | Let the member review recorded oxygen-saturation history and its source, without offering emergency monitoring. | Summarize my recent recorded oxygen saturation. |
| `READ_BLOOD_PRESSURE` | Let the member review dated systolic and diastolic readings and compare their recorded history. | Show my recent blood-pressure readings and their dates. |
| `READ_BLOOD_GLUCOSE` | Summarize recorded glucose history for member-requested wellness questions without prescribing medication or treatment. | Summarize my recorded glucose over these dates. |
| `READ_BODY_TEMPERATURE` | Let the member review recorded temperature trends with units and source context. | What temperature readings are in my connected history? |
| `READ_HEIGHT` | Make the member's source-recorded height available when reviewing body measurements. | What height does my connected source report? |
| `READ_WEIGHT` | Use recorded weights to summarize changes over time and discuss the member's wellness goals. | How has my recorded weight changed? |
| `READ_BODY_FAT` | Use recorded body-fat measurements to distinguish body-composition changes from weight changes alone. | Compare my recorded body-fat and weight trends. |
| `READ_HYDRATION` | Summarize recorded water intake for hydration and nutrition conversations. | How much water did my connected source record yesterday? |
| `READ_NUTRITION` | Use source-recorded meals and available nutrients to summarize food intake in nutrition conversations. | Summarize yesterday's connected meals and nutrients. |
| `READ_MENSTRUATION` | Summarize the member's recorded periods and flow history when they ask about those observations. | Summarize my recorded period dates and flow. |
| `READ_CERVICAL_MUCUS` | Include explicitly shared cervical-mucus observations in a member-requested review of their reproductive-health record. | Which cervical-mucus observations did I record this month? |
| `READ_INTERMENSTRUAL_BLEEDING` | Distinguish recorded bleeding between periods from period-flow records in the member's requested history. | Show the dates of my recorded bleeding between periods. |
| `READ_OVULATION_TEST` | Retrieve explicitly shared test results as recorded observations, without predicting ovulation or recommending contraception. | What ovulation-test results did I record and when? |
| `READ_SEXUAL_ACTIVITY` | Retrieve explicitly shared sexual-activity entries when the member asks to review that part of their reproductive-health history. | Show the dates of my recorded sexual-activity entries. |

Reproductive observations and clinical vitals require especially clear feature
evidence. Optional consent and generic personalization are not substitutes for
demonstrating why each type is needed. Do not infer missing observations.

## Code evidence and limits

- Android scope: `JunctionHealthSyncService` and the manifest's 29 read permissions.
  Workout details require Exercise; reproductive details require Menstruation.
- Backend source reviewed at `3b677bd7d6` in the Murph repository:
  `packages/contracts/src/junction-resources.ts` admits summary and timeseries
  resources; `packages/importers/src/device-providers/junction.ts` normalizes
  activity, workouts, sleep, body, profile, meals, vitals, hydration, and dated
  reproductive observations. Workout summaries include power, speed, and
  elevation fields when the provider supplies them.
- `packages/assistant-engine/src/assistant/system-prompt.ts` directs the assistant
  to consult connected wearable and structured measurement records when answering
  a member. This establishes a code path, not successful production receipt.
- Standalone `calories_basal` and `floors_climbed` are opt-in backend resources.
  Floors can also arrive in activity summaries. Verify the actual production
  resource configuration and Android provider payloads before claiming either
  path works. Do not equate BMR in power units with daily basal energy in kcal;
  verify the SDK's conversion and the units presented to the member.
- SDK support and a generic Synced state do not prove every field survives
  provider export, webhook admission, normalization, and member queries. Power,
  speed, elevation, and reproductive detail arrays need field-level checks too.

If a requested type cannot reach a demonstrated feature, leave resubmission
blocked and repair that path. Do not silently remove its permission or claim
that planned functionality already works.

## Record the reviewer walkthrough

1. Use the exact signed production-package candidate on a physical Android
   device and a dedicated review account. Keep access instructions and account
   details in the private Console App access section, never in this repository.
2. Show the launcher icon and app name, then account admission and Health Connect
   setup. Show the disclosure before the system permission sheet and demonstrate
   that a partial grant is accepted.
3. Configure a compatible source to write identifiable test records. Record the
   source, dates, units, and permission group privately. Synthetic UI screenshots
   alone cannot prove the Health Connect pipeline.
4. Choose Sync now and wait for backend-confirmed receipt. Open the same account's
   private Murph conversation and demonstrate each row above using the imported
   records. Include the transition from Android to the conversation so reviewers
   can find the feature. Do not send test health details to a group conversation.
5. Show permission revocation and the in-app privacy/deletion controls. Complete
   the existing Pixel/Samsung release matrix. Keep identifiable or sensitive
   recordings out of Git; provide authorized review evidence privately.
6. Update the Console listing, Health apps declaration, Health Connect permission
   explanations, Data safety answers, and App access instructions together. Supply
   the demonstration video where the Console requests it.

## Branding and submission

The manifest's normal and round icons both resolve to `@drawable/ic_murph`.
That drawable now uses the dotted Murph mark on cream, with the same 24-dot
geometry as `murph_mark.xml` and the light brand palette used by Murph Web.
Check the installed release against the current main and any custom store
listings; app name is **Murph**. Do not submit an old bundle containing the letter
icon. The Play listing icon must be the same dotted brand mark.

The candidate uses version code 2; recheck that it is unused immediately before
upload. Prepare the signed AAB and complete
`checkPlaySubmissionReadiness` with exact-artifact private assertions before
upload. This patch does not establish the approved signing certificate,
production SDK configuration, or physical-device results.

Official guidance checked 2026-09-15:

- [Android Health Permissions: Guidance and FAQs](https://support.google.com/googleplay/android-developer/answer/12991134?hl=en)
- [Publish your Health Connect app](https://developer.android.com/health-and-fitness/health-connect/publish)

Google evaluates actual functionality and the submitted evidence. Clearer copy
supports review; it does not guarantee approval.
