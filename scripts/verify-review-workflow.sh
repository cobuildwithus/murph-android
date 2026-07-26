#!/usr/bin/env bash
set -euo pipefail

root_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$root_dir"

bash -n scripts/review-gpt.config.sh

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

printf '%s\n' "ReviewGPT workflow verified."
