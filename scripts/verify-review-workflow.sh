#!/usr/bin/env bash
set -euo pipefail

root_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$root_dir"

bash -n scripts/review-gpt.config.sh
bash -n scripts/repo-tools.config.sh

node <<'NODE'
const pkg = require("./package.json");
if (pkg.scripts?.["review:gpt"] !== "cobuild-review-gpt --config scripts/review-gpt.config.sh") {
  throw new Error("review:gpt must use the repository config");
}
if (pkg.devDependencies?.["@cobuild/review-gpt"] !== "0.5.114") {
  throw new Error("@cobuild/review-gpt must stay exactly pinned");
}
NODE

grep -Fq 'model="gpt-5.6-sol"' scripts/review-gpt.config.sh
grep -Fq 'app_connector="current"' scripts/review-gpt.config.sh
grep -Fq 'browser_lanes=(eragon phlebas mountain)' scripts/review-gpt.config.sh
grep -Fq 'review_gpt_register_dir_preset "android-review"' scripts/review-gpt.config.sh
grep -Fq 'ANDROID_REVIEW_COMPLETE' scripts/chatgpt-review-presets/android-deep-review.md
pnpm review:gpt --list-presets | grep -Fq 'android-review'

source scripts/repo-tools.config.sh
review_bundle_dir="$(mktemp -d "${TMPDIR:-/tmp}/murph-android-review-verify.XXXXXX")"
trap 'rm -rf -- "$review_bundle_dir"' EXIT
review_gpt_package_dir="$(dirname "$(node -p 'require.resolve("@cobuild/review-gpt/package.json")')")"
review_packager="$review_gpt_package_dir/node_modules/.bin/cobuild-package-audit-context"
"$review_packager" \
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
