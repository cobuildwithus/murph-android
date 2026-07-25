#!/usr/bin/env sh
set -eu

if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle is not installed. Install Gradle 8.11.1 or use Android Studio, then rerun." >&2
  exit 1
fi

gradle wrapper --gradle-version 8.11.1
