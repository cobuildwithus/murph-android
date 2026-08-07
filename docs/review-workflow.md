# Android review workflow

The repository has three independent hosted checks:

- **Android CI** runs the full unit-test, Debug/Release lint, and Debug/Release
  assembly surface on every pull-request head and on `main`.
- **Review Tooling** installs the lockfile-pinned `@cobuild/review-gpt` package
  without lifecycle scripts, verifies the local packaging/response contract,
  and lists the registered presets on the same heads.
- **Android Visual Proof** runs the trusted default-branch workflow revision of
  the screenshot verifier against the candidate's exact Git objects and
  rendered PR body.
  Candidate code is inspected as data only in the privileged workflow.

These checks complement local verification. They do not replace physical
Health Connect, connected health apps, Contacts, or OEM-device proof.

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
fixed `android-pr-review` preset without pass-through prompt, prompt-file, or
additional-preset arguments. Its attestation binds that fixed preset; the
pinned tool version owns its injected capture/model protocol.

The package wrapper reads the live canonical GitHub PR and fails closed unless
local `HEAD` equals the pushed head. It packages only a clean tracked snapshot
plus:

- canonical repository, PR URL/number, base ref/SHA, and head ref/SHA;
- SHA-256 of the current PR body;
- the fixed prompt id/version and prompt SHA-256;
- the pinned ReviewGPT package version;
- the PR description, changed-file list, and full base-to-head patch.

The response must echo the packaged context digest and checked head exactly
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

## Review control changes

A PR that changes the ReviewGPT prompt, fixed PR runner, packager, validator,
contract tests, tool pin, hosted review workflow, Android CI workflow, Android
visual-proof workflow, visual-proof checker or tests, `scripts/verify.sh`, or
this policy cannot use its changed copy of ReviewGPT to certify itself. Use a
fresh independent local review against the fixed checklist from the base
revision. Keep review control changes separate from product behavior so later
product PRs inherit a trusted gate.
