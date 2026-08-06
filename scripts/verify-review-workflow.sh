#!/usr/bin/env bash
set -euo pipefail
umask 077

root_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$root_dir"

bash -n scripts/review-gpt.config.sh
bash -n scripts/repo-tools.config.sh
bash -n scripts/package-review-context.sh
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
if (pkg.devDependencies?.["@cobuild/review-gpt"] !== "0.5.114") {
  throw new Error("@cobuild/review-gpt must stay exactly pinned");
}
NODE

installed_review_version="$(node -p 'require("@cobuild/review-gpt/package.json").version')"
[[ "$installed_review_version" == "0.5.114" ]]
if bash scripts/review-pr.sh 1 output-packages/review.md --prompt bypass >/dev/null 2>&1; then
  echo "Fixed PR review runner accepted pass-through arguments." >&2
  exit 1
fi

grep -Fq 'model="gpt-5.6-sol"' scripts/review-gpt.config.sh
grep -Fq 'app_connector="current"' scripts/review-gpt.config.sh
grep -Fq 'browser_lanes=(eragon phlebas mountain)' scripts/review-gpt.config.sh
grep -Fq 'package_script="scripts/package-review-context.sh"' scripts/review-gpt.config.sh
grep -Fq 'fixed-android-pr-review-v1' scripts/package-review-context.sh
grep -Fq 'scripts/verify.sh' scripts/package-review-context.sh
grep -Fq 'review_gpt_register_dir_preset "android-review"' scripts/review-gpt.config.sh
grep -Fq 'review_gpt_register_dir_preset "android-pr-review"' scripts/review-gpt.config.sh
grep -Fq 'REVIEW_CONTEXT_SHA256:' scripts/chatgpt-review-presets/android-deep-review.md
grep -Fq 'REVIEW_FINDINGS:' scripts/chatgpt-review-presets/android-deep-review.md
grep -Fq 'ANDROID_REVIEW_COMPLETE' scripts/chatgpt-review-presets/android-deep-review.md
review_presets="$(pnpm review:gpt --list-presets)"
grep -Fq 'android-review' <<<"$review_presets"
grep -Fq 'android-pr-review' <<<"$review_presets"

grep -Fq 'pull_request:' .github/workflows/android-ci.yml
grep -Fq './scripts/verify.sh' .github/workflows/android-ci.yml
grep -Fq 'pull_request:' .github/workflows/review-tooling.yml
grep -Fq 'pnpm install --frozen-lockfile --ignore-scripts' .github/workflows/review-tooling.yml
grep -Fq 'pnpm review:verify' .github/workflows/review-tooling.yml

review_bundle_dir="$(mktemp -d "${TMPDIR:-/tmp}/murph-android-review-verify.XXXXXX")"
trap 'rm -rf -- "$review_bundle_dir"' EXIT
scripts/package-review-context.sh \
  --zip \
  --out-dir "$review_bundle_dir" \
  --name verify \
  --with-tests \
  --with-docs >/dev/null

review_bundle_zips=("$review_bundle_dir"/verify-*.zip)
if [[ "${#review_bundle_zips[@]}" -ne 1 || ! -f "${review_bundle_zips[0]}" ]]; then
  printf '%s\n' "Review package verification failed: expected exactly one ZIP." >&2
  exit 1
fi

review_manifest="$(unzip -Z1 "${review_bundle_zips[0]}")"
required_review_paths=(
  "AGENTS.md"
  "app/build.gradle.kts"
  "app/src/main/AndroidManifest.xml"
  "app/src/main/java/ai/withmurph/companion/app/AppSession.kt"
  "app/src/main/java/ai/withmurph/companion/auth/PrivyAuthService.kt"
  "app/src/main/java/ai/withmurph/companion/health/JunctionHealthSyncService.kt"
  "app/src/main/java/ai/withmurph/companion/ui/login/LoginScreen.kt"
  "app/src/test/java/ai/withmurph/companion/app/AppSessionTest.kt"
  "gradle/libs.versions.toml"
  "gradle/wrapper/gradle-wrapper.jar"
  "scripts/review-gpt-contract.mjs"
  "scripts/review-pr.sh"
  "scripts/validate-review-gpt-response.sh"
)
for required_path in "${required_review_paths[@]}"; do
  if ! grep -Fqx "$required_path" <<<"$review_manifest"; then
    printf '%s\n' "Review package verification failed: missing $required_path." >&2
    exit 1
  fi
done

review_file_count="$(wc -l <<<"$review_manifest" | tr -d '[:space:]')"
if [[ "$review_file_count" -lt 45 ]]; then
  printf '%s\n' "Review package verification failed: only $review_file_count files were included." >&2
  exit 1
fi

printf '%s\n' "ReviewGPT workflow verified."
