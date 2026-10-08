#!/usr/bin/env bash
set -euo pipefail
umask 077
root_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
exec node "$root_dir/scripts/review-package.mjs" "$@"
