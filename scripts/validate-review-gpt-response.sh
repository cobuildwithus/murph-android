#!/usr/bin/env bash
set -euo pipefail
umask 077
export GIT_NO_REPLACE_OBJECTS=1
export LC_ALL=C

if [[ "$#" -ne 3 ]]; then
  echo "Usage: scripts/validate-review-gpt-response.sh <response-file> <pr-number> <checked-commit>" >&2
  exit 64
fi

root_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$root_dir"

response_file="$1"
pr_number="$2"
checked_commit="$3"
[[ -f "$response_file" ]] || {
  echo "Error: ReviewGPT response file does not exist." >&2
  exit 1
}
[[ "$pr_number" =~ ^[1-9][0-9]*$ ]] || {
  echo "Error: PR number must be a positive integer." >&2
  exit 1
}
[[ "$checked_commit" =~ ^[0-9a-f]{40}$ ]] || {
  echo "Error: checked commit must be a full lowercase 40-character Git SHA." >&2
  exit 1
}
for required_command in gh git node; do
  command -v "$required_command" >/dev/null 2>&1 || {
    echo "Error: $required_command is required to validate the current PR head." >&2
    exit 1
  }
done
[[ -z "$(git status --porcelain --untracked-files=no)" ]] || {
  echo "Error: response validation requires a clean tracked worktree." >&2
  exit 1
}

pr_metadata="$(mktemp)"
prompt_file="$(mktemp)"
package_file="$(mktemp)"
expected_context_parent="$(mktemp -d)"
expected_context_dir="$expected_context_parent/context"
cleanup() {
  rm -f -- "$pr_metadata" "$prompt_file" "$package_file"
  rm -rf -- "$expected_context_parent"
}
trap cleanup EXIT

gh pr view "$pr_number" \
  --json baseRefName,baseRefOid,body,headRefName,headRefOid,number,url \
  > "$pr_metadata"
canonical_repository="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
remote_head="$(
  node -e '
    const fs = require("node:fs");
    const value = JSON.parse(fs.readFileSync(process.argv[1], "utf8")).headRefOid;
    if (typeof value !== "string") process.exit(1);
    process.stdout.write(value);
  ' "$pr_metadata"
)" || {
  echo "Error: could not resolve the current remote PR head." >&2
  exit 1
}
local_head="$(git rev-parse --verify HEAD)"
[[ "$local_head" == "$checked_commit" && "$remote_head" == "$checked_commit" ]] || {
  echo "Error: ReviewGPT response is stale because the local or remote PR head moved." >&2
  exit 1
}

git show "$checked_commit:scripts/chatgpt-review-presets/android-deep-review.md" \
  > "$prompt_file" || {
  echo "Error: checked head does not contain the Android review prompt." >&2
  exit 1
}
git show "$checked_commit:package.json" > "$package_file" || {
  echo "Error: checked head does not contain package.json." >&2
  exit 1
}
review_tool_version="$(
  node -e '
    const fs = require("node:fs");
    const pkg = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    const version = pkg.devDependencies?.["@cobuild/review-gpt"];
    if (typeof version !== "string" || version.length === 0) process.exit(1);
    process.stdout.write(version);
  ' "$package_file"
)" || {
  echo "Error: checked head does not pin @cobuild/review-gpt." >&2
  exit 1
}
installed_review_tool_version="$(
  node -e '
    const pkg = require("@cobuild/review-gpt/package.json");
    if (typeof pkg.version !== "string" || pkg.version.length === 0) process.exit(1);
    process.stdout.write(pkg.version);
  '
)" || {
  echo "Error: could not resolve the installed @cobuild/review-gpt version." >&2
  exit 1
}
[[ "$installed_review_tool_version" == "$review_tool_version" ]] || {
  echo "Error: installed @cobuild/review-gpt does not match the checked-head pin." >&2
  exit 1
}

node scripts/review-gpt-contract.mjs create \
  "$pr_metadata" \
  "$canonical_repository" \
  "$prompt_file" \
  "$installed_review_tool_version" \
  "$expected_context_dir" >/dev/null

node scripts/review-gpt-contract.mjs validate \
  "$response_file" \
  "$expected_context_dir/review-context.json"
