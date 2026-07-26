#!/usr/bin/env bash
set -euo pipefail

root_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$root_dir"

source scripts/repo-tools.config.sh
review_gpt_package_dir="$(dirname "$(node -p 'require.resolve("@cobuild/review-gpt/package.json")')")"
review_packager="$review_gpt_package_dir/node_modules/.bin/cobuild-package-audit-context"
exec "$review_packager" "$@"
