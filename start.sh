#!/usr/bin/env bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# XR Mail — Laptop Development Launcher
#
# Builds the project and runs it on the best available target.
# No SDK command-line tools required — just Android Studio + the SDK.
#
# Usage:
#   ./start.sh              Build + run on best available target
#   ./start.sh --build-only Just build the APK
#   ./start.sh --clean      Clean build caches then build + run
#   ./start.sh --logs       Tail logcat after launch
# ─────────────────────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

APP_PACKAGE="com.xremail.app"
ACTIVITY=".MainActivity"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m'

info()  { echo -e "${CYAN}▸${NC} $1"; }
ok()    { echo -e "${GREEN}✓${NC} $1"; }
warn()  { echo -e "${YELLOW}⚠${NC} $1"; }
fail()  { echo -e "${RED}✗${NC} $1"; exit 1; }

# ── Locate Android SDK ──────────────────────────────────────────────────────
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
    fail "Android SDK not found. Set ANDROID_HOME or install Android Studio."
}

find_sdk
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ok "Android SDK: $ANDROID_HOME"

# ── JAVA_HOME — prefer Android Studio's bundled JDK ─────────────────────────
if [[ -z "${JAVA_HOME:-}" ]]; then
    STUDIO_JDK="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    if [[ -d "$STUDIO_JDK" ]]; then
        export JAVA_HOME="$STUDIO_JDK"
        info "Using Android Studio JDK: $JAVA_HOME"
    fi
fi

# ── Parse args ───────────────────────────────────────────────────────────────
BUILD_ONLY=false
CLEAN=false
TAIL_LOGS=false

for arg in "$@"; do
    case "$arg" in
        --build-only) BUILD_ONLY=true ;;
        --clean)      CLEAN=true ;;
        --logs)       TAIL_LOGS=true ;;
        --help|-h)
            echo "Usage: ./start.sh [--build-only] [--clean] [--logs]"
            echo ""
            echo "  --build-only   Just compile the APK, skip install/launch"
            echo "  --clean        Wipe build caches before building"
            echo "  --logs         Tail logcat after launching the app"
            exit 0
            ;;
    esac
done

# ── Build ────────────────────────────────────────────────────────────────────
info "Building XR Mail..."

GRADLE="./gradlew"
[[ -x "$GRADLE" ]] || fail "gradlew not found or not executable."

BUILD_ARGS=("assembleDebug" "-q")
$CLEAN && BUILD_ARGS=("clean" "${BUILD_ARGS[@]}")

if ! $GRADLE "${BUILD_ARGS[@]}"; then
    echo ""
    warn "Build failed. Running again with full output:"
    echo ""
    $GRADLE assembleDebug --stacktrace 2>&1 | tail -40
    exit 1
fi

APK="app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || fail "APK not found at $APK"
ok "Built: $APK ($(du -h "$APK" | awk '{print $1}'))"

$BUILD_ONLY && { ok "Done (build-only)."; exit 0; }

# ── Find a target device/emulator ───────────────────────────────────────────
# macOS ships Bash 3.2 — use >/dev/null 2>&1 (not &>) and keep logic POSIX-safe.
wait_for_device() {
    local timeout=300 elapsed=0
    info "Waiting for emulator to boot (XR first boot can take a few minutes)..."
    while (( elapsed < timeout )); do
        # Any non-offline device in "device" state (emulator or USB)
        if "$ADB" devices 2>/dev/null | grep -v "List of devices" | grep -v "offline" | grep -E "[[:space:]]device$" -q; then
            local booted
            booted=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
            [[ "$booted" == "1" ]] && return 0
        fi
        sleep 3
        elapsed=$((elapsed + 3))
        printf "\r  ${DIM}%ds / %ds${NC}" "$elapsed" "$timeout"
    done
    echo ""
    return 1
}

TARGET=""

# ── Pick a target ───────────────────────────────────────────────────────────
# Order of preference:
#   1. A physical device that's online (`device` state) — always wins.
#   2. A physical device that's connected but not yet online (`offline` /
#      `unauthorized` / `no permissions`) — try to recover it before
#      giving up. We do NOT fall through to the emulator in this case,
#      because the user obviously wants to deploy to the headset they
#      just plugged in.
#   3. A running emulator that's online.
#   4. No devices at all → start an emulator from disk.
#
# Why this matters: previously the script ran
#   `adb devices | grep -E "[[:space:]]device$" | grep -v offline`
# which silently dropped a plugged-in-but-suspended Galaxy XR headset
# from consideration and then booted an XR emulator over USB while the
# real headset sat there ignored. Builds went to the wrong place and
# the user couldn't tell from the script output.
list_devices() {
    # Strips the "List of devices attached" header and any blank lines,
    # leaving rows like:
    #   R3GYB0AR2XD	device usb:336592896X transport_id:5
    #   emulator-5554	device product:sdk_gphone64_arm64 ...
    #   R3GYB0AR2XD	offline
    # Always exits 0 even when adb has nothing to print.
    "$ADB" devices -l 2>/dev/null \
        | grep -vE "^(List of devices|\\s*$)" \
        || true
}

physical_device_serial() {
    # First column of the first row whose serial does NOT start with
    # `emulator-`. Empty string if none.
    list_devices | awk '$1 !~ /^emulator-/ {print $1; exit}'
}

physical_device_state() {
    # Second column for the first physical row (device / offline /
    # unauthorized / authorizing / no permissions). Empty when no
    # physical row exists.
    list_devices | awk '$1 !~ /^emulator-/ {print $2; exit}'
}

emulator_online_serial() {
    list_devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1; exit}'
}

PHYSICAL=$(physical_device_serial)
PHYSICAL_STATE=$(physical_device_state)

if [[ -n "$PHYSICAL" ]]; then
    case "$PHYSICAL_STATE" in
        device)
            TARGET="$PHYSICAL"
            ok "Using physical device: $TARGET"
            ;;
        offline|authorizing)
            warn "Physical device $PHYSICAL is connected but $PHYSICAL_STATE."
            info "Attempting to wake it (adb reconnect + 10s poll)..."
            "$ADB" reconnect >/dev/null 2>&1 || true
            for i in {1..10}; do
                sleep 1
                STATE=$(physical_device_state)
                if [[ "$STATE" == "device" ]]; then
                    TARGET="$PHYSICAL"
                    ok "Physical device came online: $TARGET"
                    break
                fi
            done
            if [[ -z "$TARGET" ]]; then
                fail "Physical device $PHYSICAL is still $PHYSICAL_STATE after 10s.\n\n\
  Wake the headset (put it on, or hold the power button briefly) and re-run\n\
  ./start.sh. If you wanted to deploy to the emulator instead, unplug the\n\
  headset first."
            fi
            ;;
        unauthorized)
            fail "Physical device $PHYSICAL is connected but UNAUTHORIZED.\n\n\
  Put the headset on and accept the 'Allow USB debugging?' prompt that's\n\
  showing in your view, then re-run ./start.sh.\n\n\
  We did NOT fall back to the emulator on purpose — the headset is\n\
  clearly the intended target."
            ;;
        "no permissions"*)
            fail "Physical device $PHYSICAL is connected but adb has no permissions.\n\n\
  Try: \`adb kill-server && sudo adb start-server\` (macOS USB perms)\n\
  Or unplug and replug the headset cable, then re-run ./start.sh."
            ;;
        *)
            warn "Physical device $PHYSICAL is in unrecognized state: $PHYSICAL_STATE"
            fail "Refusing to fall back to the emulator while the headset is plugged in.\n\
  Unplug the headset if you want to deploy to the emulator instead."
            ;;
    esac
elif [[ -n "$(emulator_online_serial)" ]]; then
    TARGET="$(emulator_online_serial)"
    ok "Using running emulator: $TARGET"
else
    # No physical device, no running emulator — start one from disk.
    AVDS=$("$EMULATOR" -list-avds 2>/dev/null || true)

    if [[ -z "$AVDS" ]]; then
        echo ""
        fail "No devices connected and no emulators found.\n\n\
  Create one in Android Studio:\n\
    Device Manager → + Create Virtual Device → pick any device → Next → Finish\n\n\
  For the full XR experience:\n\
    Device Manager → + Create Virtual Device → XR (left sidebar) → XR Device"
    fi

    # Prefer an AVD with "xr" or "XR" in the name
    XR_AVD=$(echo "$AVDS" | grep -i "xr" | head -1 || true)
    if [[ -n "$XR_AVD" ]]; then
        AVD_TO_START="$XR_AVD"
        info "Starting XR emulator: $AVD_TO_START"
    else
        AVD_TO_START=$(echo "$AVDS" | head -1)
        warn "No XR emulator found. Starting '$AVD_TO_START' (2D fallback)."
        echo -e "  ${DIM}Spatial panels won't render — the app runs as a flat window.${NC}"
        echo -e "  ${DIM}To get the XR emulator: Android Studio → Device Manager →${NC}"
        echo -e "  ${DIM}+ Create Virtual Device → XR (left sidebar) → XR Device${NC}"
        echo ""
    fi

    "$EMULATOR" -avd "$AVD_TO_START" -gpu auto >/dev/null 2>&1 &
    if ! wait_for_device; then
        fail "Emulator timed out. Try starting it from Android Studio first."
    fi
    echo ""

    TARGET="$(emulator_online_serial)"
    ok "Emulator ready: $TARGET"
fi

# ── Install + launch ─────────────────────────────────────────────────────────
info "Installing..."
"$ADB" -s "$TARGET" install -r -t "$APK" >/dev/null 2>&1 || \
    fail "Install failed. Is the emulator fully booted?"
ok "Installed on $TARGET"

info "Launching..."
"$ADB" -s "$TARGET" shell am start -n "$APP_PACKAGE/$APP_PACKAGE$ACTIVITY" >/dev/null 2>&1 || \
    fail "Launch failed."

echo ""
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}  XR Mail is running${NC}"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
echo -e "  ${CYAN}Logs:${NC}    adb logcat --pid=\$(adb shell pidof $APP_PACKAGE) 2>/dev/null"
echo -e "  ${CYAN}Stop:${NC}    adb shell am force-stop $APP_PACKAGE"
echo -e "  ${CYAN}Rebuild:${NC} ./start.sh"
echo ""

if $TAIL_LOGS; then
    info "Tailing logs (Ctrl+C to stop)..."
    sleep 1
    "$ADB" -s "$TARGET" logcat --pid="$("$ADB" -s "$TARGET" shell pidof "$APP_PACKAGE" 2>/dev/null || echo 0)" 2>/dev/null || \
        "$ADB" -s "$TARGET" logcat "*:S" "AndroidRuntime:E" "ActivityManager:I"
fi
