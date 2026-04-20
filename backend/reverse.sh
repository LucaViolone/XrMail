#!/usr/bin/env bash
# Forward emulator/device localhost:PORT → host localhost:PORT (needed for OAuth redirect to http://localhost:8081).
# Does not require `adb` on your PATH — uses the Android SDK under ANDROID_HOME / default Mac paths.
#
# Usage (run from repo root):
#   ./backend/reverse.sh              # port 8081, first online device
#   ./backend/reverse.sh 8081         # custom port
#   ./backend/reverse.sh 8081 emulator-5554

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# Run from repo root so relative paths (if any) behave like start.sh.
cd "$SCRIPT_DIR/.."

find_sdk() {
    for dir in \
        "${ANDROID_HOME:-}" \
        "${ANDROID_SDK_ROOT:-}" \
        "$HOME/Library/Android/sdk" \
        "$HOME/Android/Sdk" \
        "/usr/local/share/android-sdk"; do
        if [[ -n "$dir" ]] && [[ -d "$dir/platform-tools" ]]; then
            export ANDROID_HOME="$dir"
            return
        fi
    done
    echo "Android SDK not found. Install Android Studio or set ANDROID_HOME." >&2
    exit 1
}

find_sdk
ADB="$ANDROID_HOME/platform-tools/adb"
[[ -x "$ADB" ]] || { echo "Missing adb at $ADB" >&2; exit 1; }

PORT="${1:-8081}"
TARGET="${2:-}"

if [[ -z "$TARGET" ]]; then
    TARGET=$("$ADB" devices 2>/dev/null | grep -v "List" | grep -E "[[:space:]]device$" | grep -v offline | head -1 | awk '{print $1}' || true)
fi

if [[ -z "$TARGET" ]]; then
    echo "No device in 'adb devices'. Boot an emulator or plug in a device." >&2
    "$ADB" devices
    exit 1
fi

echo "Device: $TARGET  →  reverse tcp:$PORT (emulator localhost → your Mac)"
"$ADB" -s "$TARGET" reverse "tcp:$PORT" "tcp:$PORT"
"$ADB" -s "$TARGET" reverse --list
