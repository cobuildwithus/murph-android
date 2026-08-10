#!/usr/bin/env bash
set -euo pipefail
umask 077
export GIT_NO_REPLACE_OBJECTS=1
export LC_ALL=C

if [[ "$#" -ne 2 || -z "$1" || -z "$2" || "$1" == -* || "$2" == -* ]]; then
  echo "Usage: scripts/review-pr.sh <pr-url-or-number> <response-file>" >&2
  exit 64
fi

root_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$root_dir"

fail() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

for required_command in gh git node; do
  command -v "$required_command" >/dev/null 2>&1 \
    || fail "$required_command is required for exact-head PR review"
done

[[ -z "$(git status --porcelain --untracked-files=all)" ]] \
  || fail "exact-head PR review requires a clean worktree"

context_root="$(mktemp -d "${TMPDIR:-/tmp}/murph-android-review.XXXXXX")"
pr_metadata="$context_root/pr-metadata.json"
context_dir="$context_root/context"
invocation_file="$context_root/invocation.md"
cleanup() {
  rm -rf -- "$context_root"
}
trap cleanup EXIT

gh pr view "$1" \
  --json baseRefName,baseRefOid,body,headRefName,headRefOid,number,url \
  > "$pr_metadata"
canonical_repository="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"

read_metadata_field() {
  node -e '
    const fs = require("node:fs");
    const value = JSON.parse(fs.readFileSync(process.argv[1], "utf8"))[process.argv[2]];
    if (typeof value !== "string") process.exit(1);
    process.stdout.write(value);
  ' "$pr_metadata" "$1"
}

base_oid="$(read_metadata_field baseRefOid)" \
  || fail "could not read the PR base head"
head_oid="$(read_metadata_field headRefOid)" \
  || fail "could not read the PR head"
[[ "$base_oid" =~ ^[0-9a-f]{40}$ && "$head_oid" =~ ^[0-9a-f]{40}$ ]] \
  || fail "PR base and head must be full lowercase Git SHAs"
[[ "$(git rev-parse --verify HEAD)" == "$head_oid" ]] \
  || fail "local HEAD must match the exact pushed PR head"
git cat-file -e "$base_oid^{commit}" >/dev/null 2>&1 \
  || fail "PR base head is unavailable locally; fetch the base and retry"
git cat-file -e "$head_oid^{commit}" >/dev/null 2>&1 \
  || fail "PR head is unavailable locally"
git merge-base "$base_oid" "$head_oid" >/dev/null 2>&1 \
  || fail "PR base and head have no available merge base"
git diff --quiet "$base_oid...$head_oid" \
  && fail "the PR has no changed files"

review_control_paths=(
  ".github/workflows/android-ci.yml"
  ".github/workflows/review-tooling.yml"
  "docs/review-workflow.md"
  "package.json"
  "pnpm-lock.yaml"
  "scripts/chatgpt-review-presets/android-deep-review.md"
  "scripts/package-review-context.sh"
  "scripts/repo-tools.config.sh"
  "scripts/review-gpt-contract.mjs"
  "scripts/review-gpt-contract.test.mjs"
  "scripts/review-gpt.config.sh"
  "scripts/review-pr.sh"
  "scripts/validate-review-gpt-response.sh"
  "scripts/verify.sh"
  "scripts/verify-review-workflow.sh"
)
git diff --quiet "$base_oid...$head_oid" -- "${review_control_paths[@]}" \
  || fail "PRs changing the review control plane require independent local review"

review_tool_version="$(
  node -e '
    const pkg = require("./package.json");
    const version = pkg.devDependencies?.["@cobuild/review-gpt"];
    if (typeof version !== "string" || version.length === 0) process.exit(1);
    process.stdout.write(version);
  '
)" || fail "could not resolve the pinned ReviewGPT version"
installed_review_tool_version="$(
  node -e '
    const pkg = require("@cobuild/review-gpt/package.json");
    if (typeof pkg.version !== "string" || pkg.version.length === 0) process.exit(1);
    process.stdout.write(pkg.version);
  '
)" || fail "could not resolve the installed ReviewGPT version"
[[ "$installed_review_tool_version" == "$review_tool_version" ]] \
  || fail "installed ReviewGPT must exactly match the declared pin"

node scripts/review-gpt-contract.mjs create \
  "$pr_metadata" \
  "$canonical_repository" \
  "scripts/chatgpt-review-presets/android-deep-review.md" \
  "$installed_review_tool_version" \
  "$context_dir" >/dev/null
node scripts/review-gpt-contract.mjs invocation \
  "$context_dir/review-context.json" \
  "$invocation_file"

pnpm review:gpt android-pr-review \
  --no-zip \
  --connector github \
  --prompt-file "$invocation_file" \
  --wait \
  --wait-timeout 120m \
  --response-marker ANDROID_REVIEW_COMPLETE \
  --response-file "$2"

[[ "$(git rev-parse --verify HEAD)" == "$head_oid" ]] \
  || fail "local HEAD moved during exact-head PR review"
git diff --quiet HEAD -- \
  || fail "tracked files changed during exact-head PR review"
