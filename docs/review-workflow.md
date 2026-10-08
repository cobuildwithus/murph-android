# Android review workflow

The repository has three independent hosted checks:

- **Android CI** runs the full unit-test, Debug/Release lint, and Debug/Release
  assembly surface on every pull-request head and on `main`.
- **Review Tooling** installs the lockfile-pinned `@cobuild/review-gpt` package
  without lifecycle scripts, verifies the guarded ZIP and response contract,
  and lists the registered presets on the same heads.
- **Android Visual Proof** runs the trusted default-branch workflow revision of
  the screenshot verifier against the candidate's exact Git objects and
  rendered PR body.
  Candidate code is inspected as data only in the privileged workflow.

These checks complement local verification. They do not replace physical
Health Connect, WHOOP, Contacts, or OEM-device proof.

## Exact-head PR review

Use `android-pr-review` only after focused verification, a clean worktree, and
an exact pushed PR head. Start it alongside GitHub Actions rather than waiting
for CI:

```sh
git fetch origin main
reviewed_head="$(git rev-parse HEAD)"
pnpm review:pr <pr-url-or-number> output-packages/pr-review.md
pnpm review:validate output-packages/pr-review.md <pr-number> "$reviewed_head"
```

`review:pr` accepts only the PR identity and response path, then invokes the
fixed `android-pr-review` preset without user-supplied prompt or preset
arguments. It attaches a guarded ZIP, retains the current ChatGPT app setting,
and adds a small runner-generated invocation containing:

- canonical repository, PR URL/number, and base and head commits;
- SHA-256 of the current PR body;
- the fixed prompt id/version and prompt SHA-256;
- the pinned ReviewGPT package version;
- the response context digest and exact checked head.

The ZIP is the sole repository-content source; no GitHub connector is required.
`scripts/package-audit-context.sh --zip --with-tests` reads only committed Git
blobs at the exact head. `REVIEW_GPT_PR_URL` identifies the PR. Like iOS, its
explicit text allowlist excludes private paths, local.properties/gradle.properties,
signing material, generated/build output and evidence binaries. Changed private
or generated paths, symlinks, invalid UTF-8, NULs, credential markers and local
home paths fail closed. No worktree file bytes enter the snapshot.

The ZIP includes `review-gpt-pr-context/` with the exact context, PR description,
text-only full PR diff, changed-file list, omitted-path list and SHA-256 file
manifest. Archive member names and bytes are read back and compared to the
snapshot. The runner writes `<response-file>.package.json` containing its
archive path, SHA-256, head and context digest beside the tool's normal turn/model
capture metadata. The launcher uploads a disposable byte-identical copy so its
cleanup cannot delete the retained archive. After a successful waited run, the
receipt also binds the completed user/assistant turn capture and response hashes
(and model sidecar when present). Keep the receipt, capture sidecars and retained
ZIP with the response. Validation rebuilds the current snapshot and verifies
those hashes, archive members and bytes before accepting the response. Excluded binary pixels require the
parent's separately recorded visual inspection; the review must not claim it
saw them. The runner rejects dirty or unpushed work.

Pin a signed-in managed browser with `REVIEW_GPT_BROWSER_LANE=hercules`
(port 9444); eragon, phlebas and mountain remain supported. The override checks
the existing listener's profile and port before use. No browser auth tokens are
read or copied. An unavailable login/model/upload is a tooling failure, not a
review result. Report it and use the shared GUI handoff when human action is
needed.

The response must echo the attested context digest and checked head exactly
once. Its final three non-empty lines must be a structured finding count, a
matching `PASS` or `FINDINGS` outcome, and `ANDROID_REVIEW_COMPLETE`.
`review:validate` rebuilds the context from the current GitHub PR and the exact
Git objects, then rejects a moved local head, moved remote head, changed base,
changed PR body, wrong repository/PR, changed prompt/tool version, malformed
finding count, or incomplete response.

Any PR-specific commit requires a fresh response for the new head. A moved
base also requires a fresh response because the reviewed comparison changed.
Merge readiness requires a validated `PASS` with zero accepted findings, all
three hosted checks green on that head, required device evidence recorded, and a
conflict-free PR.

For a production-path PR, recorded device evidence means the PR body includes
the required exact-head emulator evidence and names every physical-device-only
gap. Pixel, Samsung, provider, and signed-candidate exercises remain explicit
Play release gates; merging code does not claim those gates passed. The review
prompt reports a device-proof finding only when the candidate falsely claims
proof, weakens a gate, omits the required gap, or exposes a source-level defect.

## Review control changes

A PR that changes the ReviewGPT prompt, fixed PR runner, validator,
contract tests, tool pin, hosted review workflow, Android CI workflow, Android
visual-proof workflow, visual-proof checker or tests, `scripts/verify.sh`, or
this policy cannot use its changed copy of ReviewGPT to certify itself. Use a
fresh independent local review against the fixed checklist from the base
revision. Keep review control changes separate from product behavior so later
product PRs inherit a trusted gate.

Both ordinary and exact-PR review require the guarded ZIP and PR identity.
The standalone packager repeats the control-plane gate, so a direct launcher
invocation cannot certify protected changes.
