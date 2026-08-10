# Android review workflow

The repository has three independent hosted checks:

- **Android CI** runs the full unit-test, Debug/Release lint, and Debug/Release
  assembly surface on every pull-request head and on `main`.
- **Review Tooling** installs the lockfile-pinned `@cobuild/review-gpt` package
  without lifecycle scripts, verifies the connector-only response contract,
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
arguments. It disables artifacts explicitly, selects the GitHub connector,
and adds a small runner-generated invocation containing:

- canonical repository, PR URL/number, and base and head commits;
- SHA-256 of the current PR body;
- the fixed prompt id/version and prompt SHA-256;
- the pinned ReviewGPT package version;
- the response context digest and exact checked head.

No repository ZIP or Repomix artifact is generated or uploaded. ReviewGPT reads
the repository and complete PR diff through the connected GitHub app. The
runner fails closed unless the worktree is clean and local `HEAD` equals the
pushed PR head.

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

## Review control changes

A PR that changes the ReviewGPT prompt, fixed PR runner, validator,
contract tests, tool pin, hosted review workflow, Android CI workflow, Android
visual-proof workflow, visual-proof checker or tests, `scripts/verify.sh`, or
this policy cannot use its changed copy of ReviewGPT to certify itself. Use a
fresh independent local review against the fixed checklist from the base
revision. Keep review control changes separate from product behavior so later
product PRs inherit a trusted gate.

Both ordinary and exact-PR review use the connected GitHub repository as their
sole repository-content boundary.
