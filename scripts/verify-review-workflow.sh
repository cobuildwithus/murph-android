#!/usr/bin/env bash
set -euo pipefail
umask 077

root_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$root_dir"

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  play_java_bin="$JAVA_HOME/bin"
elif command -v java >/dev/null 2>&1 && java -version >/dev/null 2>&1; then
  play_java_bin="$(dirname "$(command -v java)")"
else
  printf '%s\n' "Review workflow verification requires a working JDK." >&2
  exit 1
fi
export MURPH_JAVA_EXECUTABLE="$play_java_bin/java"
export MURPH_JAR_EXECUTABLE="$play_java_bin/jar"
export MURPH_JARSIGNER_EXECUTABLE="$play_java_bin/jarsigner"
export MURPH_KEYTOOL_EXECUTABLE="$play_java_bin/keytool"

bash -n scripts/review-gpt.config.sh
bash -n scripts/review-pr.sh
bash -n scripts/validate-review-gpt-response.sh
node --check scripts/review-gpt-contract.mjs
node --test scripts/review-gpt-contract.test.mjs

node <<'NODE'
const pkg = require("./package.json");
if (pkg.scripts?.["review:gpt"] !== "cobuild-review-gpt --config scripts/review-gpt.config.sh") {
  throw new Error("review:gpt must use the repository config");
}
if (pkg.scripts?.["review:pr"] !== "bash scripts/review-pr.sh") {
  throw new Error("review:pr must use the fixed exact-head runner");
}
if (pkg.scripts?.["review:validate"] !== "bash scripts/validate-review-gpt-response.sh") {
  throw new Error("review:validate must use the exact-head response validator");
}
if (pkg.devDependencies?.["@cobuild/review-gpt"] !== "0.5.124") {
  throw new Error("@cobuild/review-gpt must stay exactly pinned");
}
NODE

installed_review_version="$(node -p 'require("@cobuild/review-gpt/package.json").version')"
[[ "$installed_review_version" == "0.5.124" ]]
if bash scripts/review-pr.sh 1 output-packages/review.md --prompt bypass >/dev/null 2>&1; then
  echo "Fixed PR review runner accepted pass-through arguments." >&2
  exit 1
fi

grep -Fq 'model="gpt-5.6-sol"' scripts/review-gpt.config.sh
grep -Fq 'app_connector="github"' scripts/review-gpt.config.sh
grep -Fq 'repo_context_url="https://github.com/cobuildwithus/murph-android"' scripts/review-gpt.config.sh
grep -Fq 'attach_artifacts=0' scripts/review-gpt.config.sh
grep -Fq 'browser_lanes=(eragon phlebas mountain)' scripts/review-gpt.config.sh
! grep -Fq 'package_script=' scripts/review-gpt.config.sh
[[ ! -e scripts/package-review-context.sh ]]
[[ ! -e scripts/repo-tools.config.sh ]]

grep -Fq -- '--no-zip' scripts/review-pr.sh
grep -Fq -- '--connector github' scripts/review-pr.sh
grep -Fq 'review-gpt-contract.mjs invocation' scripts/review-pr.sh
grep -Fq 'review_gpt_register_dir_preset "android-review"' scripts/review-gpt.config.sh
grep -Fq 'review_gpt_register_dir_preset "android-pr-review"' scripts/review-gpt.config.sh
grep -Fq 'Use the connected GitHub repository as the sole repository-content source.' scripts/chatgpt-review-presets/android-deep-review.md
grep -Fq 'server-owned initial' scripts/chatgpt-review-presets/android-deep-review.md
grep -Fq 'optional foreground-only Friendly Names' scripts/chatgpt-review-presets/android-deep-review.md
grep -Fq 'For PR merge review, honor the evidence boundary in `AGENTS.md`' scripts/chatgpt-review-presets/android-deep-review.md
grep -Fq 'physical-device-only gap that remains an explicit Play release gate is not by' scripts/chatgpt-review-presets/android-deep-review.md
! grep -Fq 'codebase.zip' scripts/chatgpt-review-presets/android-deep-review.md
grep -Fq 'REVIEW_CONTEXT_SHA256:' scripts/chatgpt-review-presets/android-deep-review.md
grep -Fq 'REVIEW_FINDINGS:' scripts/chatgpt-review-presets/android-deep-review.md
grep -Fq 'ANDROID_REVIEW_COMPLETE' scripts/chatgpt-review-presets/android-deep-review.md

review_presets="$(pnpm review:gpt --list-presets)"
grep -Fq 'android-review' <<<"$review_presets"
grep -Fq 'android-pr-review' <<<"$review_presets"

grep -Fq 'pull_request:' .github/workflows/android-ci.yml
grep -Fq './scripts/verify.sh' .github/workflows/android-ci.yml
grep -Fq 'pull_request:' .github/workflows/android-instrumentation.yml
grep -Fq 'pixel2Api30SyntheticAndroidTest' .github/workflows/android-instrumentation.yml
grep -Fq 'pull_request:' .github/workflows/review-tooling.yml
grep -Fq 'pnpm install --frozen-lockfile --ignore-scripts' .github/workflows/review-tooling.yml
grep -Fq 'pnpm review:verify' .github/workflows/review-tooling.yml

printf '%s\n' "ReviewGPT connector-only workflow verified."
