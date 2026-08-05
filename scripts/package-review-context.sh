#!/usr/bin/env bash
set -euo pipefail
umask 077
export GIT_NO_REPLACE_OBJECTS=1
export LC_ALL=C

root_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$root_dir"

source scripts/repo-tools.config.sh
review_gpt_package_dir="$(dirname "$(node -p 'require.resolve("@cobuild/review-gpt/package.json")')")"
review_packager="$review_gpt_package_dir/node_modules/.bin/cobuild-package-audit-context"

review_gpt_pr_ref="${REVIEW_GPT_PR_URL:-${REVIEW_GPT_PR_REF:-}}"
if [[ -z "$review_gpt_pr_ref" ]]; then
  exec "$review_packager" "$@"
fi

fail() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

[[ "${REVIEW_GPT_PR_PROMPT_MODE:-}" == "fixed-android-pr-review-v1" ]] \
  || fail "exact-head PR packaging must run through pnpm review:pr"

for required_command in gh git node; do
  command -v "$required_command" >/dev/null 2>&1 \
    || fail "$required_command is required for exact-head PR packaging"
done

[[ -z "$(git status --porcelain --untracked-files=all)" ]] \
  || fail "exact-head PR packaging requires a clean worktree"

pr_metadata="$(mktemp)"
context_dir="$root_dir/review-gpt-pr-context"
[[ ! -e "$context_dir" ]] \
  || fail "temporary review-gpt-pr-context path already exists"
cleanup() {
  rm -f -- "$pr_metadata"
  rm -rf -- "$context_dir"
}
trap cleanup EXIT

gh pr view "$review_gpt_pr_ref" \
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

review_control_paths=(
  ".github/workflows/android-ci.yml"
  ".github/workflows/review-tooling.yml"
  "docs/review-workflow.md"
  "package.json"
  "pnpm-lock.yaml"
  "scripts/chatgpt-review-presets/android-deep-review.md"
  "scripts/package-review-context.sh"
  "scripts/review-gpt-contract.mjs"
  "scripts/review-gpt-contract.test.mjs"
  "scripts/review-pr.sh"
  "scripts/repo-tools.config.sh"
  "scripts/review-gpt.config.sh"
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

is_sensitive_path() {
  local normalized_path
  normalized_path="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  case "$normalized_path" in
    .env | .env.* | */.env | */.env.* | .npmrc | */.npmrc | .netrc | */.netrc | \
      credentials.json | */credentials.json | credentials.yaml | */credentials.yaml | \
      credentials.yml | */credentials.yml | secrets.json | */secrets.json | \
      secrets.yaml | */secrets.yaml | secrets.yml | */secrets.yml | \
      *.jks | *.keystore | *.key | *.p8 | *.p12 | *.pem | *.pfx)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

: > "$context_dir/changed-files.txt"
while IFS= read -r -d '' changed_path; do
  if printf '%s' "$changed_path" | grep -q '[[:cntrl:]]'; then
    fail "changed paths containing control characters cannot be reviewed safely"
  fi
  is_sensitive_path "$changed_path" \
    && fail "refusing to package a change to a sensitive path: $changed_path"
  for changed_commit in "$base_oid" "$head_oid"; do
    changed_mode="$(
      git ls-tree "$changed_commit" -- ":(literal)$changed_path" \
        | awk 'NR == 1 { print $1 }'
    )"
    [[ "$changed_mode" != "120000" ]] \
      || fail "symlink changes cannot enter a ReviewGPT package: $changed_path"
  done
  printf '%s\n' "$changed_path" >> "$context_dir/changed-files.txt"
done < <(git diff --name-only -z "$base_oid...$head_oid")
[[ -s "$context_dir/changed-files.txt" ]] \
  || fail "the PR has no changed files"

git diff --no-ext-diff --no-textconv --patch "$base_oid...$head_oid" \
  > "$context_dir/pr.diff"
node -e '
  const fs = require("node:fs");
  const metadata = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
  fs.writeFileSync(process.argv[2], `${metadata.body}\n`, { mode: 0o600 });
' "$pr_metadata" "$context_dir/pr-description.md"

export COBUILD_AUDIT_CONTEXT_ALWAYS_PATHS="${COBUILD_AUDIT_CONTEXT_ALWAYS_PATHS}"$'\nreview-gpt-pr-context/changed-files.txt\nreview-gpt-pr-context/pr-description.md\nreview-gpt-pr-context/pr.diff\nreview-gpt-pr-context/review-context.json\nreview-gpt-pr-context/review-context.sha256'
export COBUILD_AUDIT_CONTEXT_INCLUDE_CI_DEFAULT="1"
export COBUILD_AUDIT_CONTEXT_CI_SCAN_SPECS=".github/workflows"

"$review_packager" "$@"
[[ "$(git rev-parse --verify HEAD)" == "$head_oid" ]] \
  || fail "local HEAD moved while the exact-head package was being built"
git diff --quiet HEAD -- \
  || fail "tracked files changed while the exact-head package was being built"
