# ZIP review transport

Status: merged in PR 53; path-guard follow-up verification pending

Replace GitHub-connector review with a guarded tracked-text snapshot, modeled on
iOS. Keep the exact PR/head/body/prompt/tool response attestation, with a ZIP
manifest and archive hash capture. Exclude private paths, generated output,
local Gradle properties, signing material and evidence binaries. Verify archive
members and bytes before sending and again when validating the response.

Keep this control-plane change separate from product PR 52. Use a fresh
independent reviewer with the base revision's checklist; do not run the changed
ReviewGPT gate against itself. Run contract/packager tests, review verification
and hosted CI. Merge only after those gates pass. Then update the product stack
and run normal ZIP ReviewGPT in Hercules. No app publication, secrets or native
canary changes are authorized.

Validation: all 21 contract/package tests pass, including the real pinned
launcher dry-run and a capture built by the pinned tool. Full Android verify
passes (unit tests, Debug/Release lint, Debug/Release/Synthetic assembly and
release tooling). A fresh independent reviewer applied the base checklist and
reported no remaining findings after launcher/archive/capture fixes. Hosted CI
must pass before merge.

The existing PR 52 registry workflow wiring is moved into this trusted tooling
change: only the built-in GitHub token is passed to the Gradle verification step,
with contents/packages read permissions and a narrowly tested allowance. No
secret values or app behavior change. The exact ReviewGPT pin is 0.5.153; its
manifest, integrity lock and version-specific release-age exception move together.

The real product archive exposed a false positive after merge: the privacy
scanner treated repository-relative ui/home source paths in the generated
manifest as absolute home directories. A narrow boundary correction preserves
absolute home-path rejection, including file URIs, quoted values and prose,
while allowing ordinary repository directories. The regression reproduces the
failure before the fix. This follow-up also needs independent control review
and hosted checks before product ReviewGPT resumes.
