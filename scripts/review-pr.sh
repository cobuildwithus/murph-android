#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ "$#" -ne 2 || -z "$1" || -z "$2" || "$1" == -* || "$2" == -* ]]; then
  echo "Usage: scripts/review-pr.sh <pr-url-or-number> <response-file>" >&2
  exit 64
fi

root_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$root_dir"

REVIEW_GPT_PR_URL="$1" \
REVIEW_GPT_PR_PROMPT_MODE="fixed-android-pr-review-v1" \
  exec pnpm review:gpt android-pr-review \
    --wait \
    --wait-timeout 120m \
    --response-marker ANDROID_REVIEW_COMPLETE \
    --response-file "$2"
