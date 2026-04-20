#!/usr/bin/env bash
# Stop local XrMail Ktor dev servers so H2 can start cleanly (only affects your machine).
set -euo pipefail
PIDS=$(pgrep -f "com\.xremail\.backend\.ApplicationKt" 2>/dev/null || true)
if [[ -z "$PIDS" ]]; then
  echo "No XrMail backend JVM found (nothing to stop)."
  exit 0
fi
echo "Stopping PIDs: $PIDS"
kill $PIDS 2>/dev/null || true
sleep 1
echo "Done. Run ./gradlew :backend:run again."
