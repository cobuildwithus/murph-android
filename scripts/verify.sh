#!/usr/bin/env sh
set -eu

if [ ! -x ./gradlew ]; then
  echo "Gradle wrapper missing. Run ./scripts/generate-wrapper.sh first." >&2
  exit 1
fi

./gradlew --no-daemon test lintDebug assembleDebug
