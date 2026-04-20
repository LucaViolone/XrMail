#!/usr/bin/env bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# XR Mail — Laptop Development Launcher
#
# One command to spin up the full local stack:
#   1. Builds the Android app
#   2. Connects to the first device/emulator (boots an XR AVD if needed)
#   3. Starts the Ktor backend on :8081 in the background (for Google OAuth)
#   4. Wires `adb reverse tcp:8081 tcp:8081` so the device's Chrome Custom
#      Tab can reach the backend at http://localhost:8081/auth/callback
#   5. Installs + launches the app
#
# Usage:
#   ./start.sh              Build + run everything
#   ./start.sh --build-only Just compile the APK
#   ./start.sh --clean      Wipe build caches, then build + run
#   ./start.sh --logs       Tail logcat after launch (Ctrl+C to stop)
#   ./start.sh --no-backend Skip starting the Ktor backend
#   ./start.sh --stop       Stop the backend JVM and exit
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
START_BACKEND=true
STOP_ONLY=false

for arg in "$@"; do
    case "$arg" in
        --build-only) BUILD_ONLY=true ;;
        --clean)      CLEAN=true ;;
        --logs)       TAIL_LOGS=true ;;
        --no-backend) START_BACKEND=false ;;
        --stop)       STOP_ONLY=true ;;
        --help|-h)
            echo "Usage: ./start.sh [--build-only] [--clean] [--logs] [--no-backend] [--stop]"
            echo ""
            echo "  --build-only   Just compile the APK, skip install/launch"
            echo "  --clean        Wipe build caches before building"
            echo "  --logs         Tail logcat after launching the app"
            echo "  --no-backend   Skip starting the Ktor backend (e.g. you run it yourself)"
            echo "  --stop         Stop the Ktor backend JVM and exit (no build)"
            exit 0
            ;;
    esac
done

# ── Backend helpers (used by --stop and the main backend block below) ───────
BACKEND_PORT="${XRMAIL_BACKEND_PORT:-8081}"
BACKEND_LOG="$SCRIPT_DIR/backend/backend.log"
BACKEND_PID_FILE="$SCRIPT_DIR/backend/backend.pid"

stop_backend() {
    local stopped=false
    if [[ -f "$BACKEND_PID_FILE" ]]; then
        local pid
        pid=$(cat "$BACKEND_PID_FILE" 2>/dev/null || true)
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
            stopped=true
        fi
        rm -f "$BACKEND_PID_FILE"
    fi
    local extra
    extra=$(pgrep -f "com\.xremail\.backend\.ApplicationKt" 2>/dev/null || true)
    if [[ -n "$extra" ]]; then
        kill $extra 2>/dev/null || true
        stopped=true
    fi
    $stopped && ok "Backend stopped." || info "No XrMail backend process was running."
}

if $STOP_ONLY; then
    stop_backend
    exit 0
fi

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

# Check for anything already running
RUNNING=$("$ADB" devices 2>/dev/null | grep -E "[[:space:]]device$" | grep -v offline | head -1 | awk '{print $1}' || true)
if [[ -n "$RUNNING" ]]; then
    TARGET="$RUNNING"
    ok "Using connected device: $TARGET"
else
    # Nothing running — try to start an emulator
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

    TARGET=$("$ADB" devices | grep -E "[[:space:]]device$" | grep -v offline | head -1 | awk '{print $1}')
    ok "Emulator ready: $TARGET"
fi

# ── Start Ktor backend (needed for real sign-in; OAuth callbacks land on it) ─
#
# We consider a TCP listener on BACKEND_PORT as "backend already running"
# ONLY if it came from our own ApplicationKt JVM. A foreign process on the
# port is left alone — we warn and skip so we don't silently talk to a
# stranger's server.
backend_already_running() {
    if command -v lsof >/dev/null 2>&1 && lsof -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
        local pids
        pids=$(lsof -tiTCP:"$BACKEND_PORT" -sTCP:LISTEN 2>/dev/null || true)
        for p in $pids; do
            if ps -p "$p" -o command= 2>/dev/null | grep -q "com\.xremail\.backend\.ApplicationKt"; then
                return 0
            fi
        done
        warn "Port ${BACKEND_PORT} is in use by another process (pids: $pids). Skipping backend start."
        warn "Stop that process or set XRMAIL_BACKEND_PORT to use a different port."
        return 0
    fi
    return 1
}

wait_for_backend() {
    local elapsed=0
    # nc is universally available on macOS; curl is the fallback.
    until (command -v nc >/dev/null 2>&1 && nc -z localhost "$BACKEND_PORT" >/dev/null 2>&1) \
        || curl -sfI "http://localhost:${BACKEND_PORT}/" >/dev/null 2>&1; do
        sleep 1
        elapsed=$((elapsed + 1))
        if (( elapsed >= 60 )); then
            warn "Backend did not bind :${BACKEND_PORT} within ${elapsed}s — continuing anyway."
            warn "Tail backend/backend.log. Common causes: missing env vars, port conflict, or a slow first compile."
            return 1
        fi
        printf "\r  ${DIM}waiting for backend... %ds${NC}" "$elapsed"
    done
    (( elapsed > 0 )) && echo ""
    return 0
}

if ! $START_BACKEND; then
    info "Skipping backend start (--no-backend). Assuming you run it yourself on :${BACKEND_PORT}."
elif [[ ! -f "$SCRIPT_DIR/backend/.env" ]]; then
    warn "backend/.env missing — copy backend/env.example, fill in GOOGLE_CLIENT_ID/SECRET, and rerun."
    warn "Skipping backend start. Tap 'Use mock data' on the sign-in screen to proceed."
elif backend_already_running; then
    ok "Ktor backend already listening on :${BACKEND_PORT}"
else
    info "Starting Ktor backend on :${BACKEND_PORT} (logs: backend/backend.log)..."
    # Fully detach the Gradle/JVM from start.sh's stdio and process tree.
    #
    #   </dev/null >LOG 2>&1   : break fd 0/1/2 inheritance so we don't hold
    #                            open the pipe if start.sh was run through
    #                            `| tee` / `| tail` (terminal would never
    #                            return until the backend dies).
    #   &                      : background the job
    #   disown                 : remove it from start.sh's job table so the
    #                            shell doesn't wait on it at exit.
    #
    # We run this at the top level of start.sh (not inside a ( ) subshell)
    # because a subshell would hold its own fds to the pipeline and become
    # the thing that blocks the terminal.
    nohup "$GRADLE" :backend:run --console=plain -q </dev/null >"$BACKEND_LOG" 2>&1 &
    BACKEND_PID=$!
    echo "$BACKEND_PID" > "$BACKEND_PID_FILE"
    disown "$BACKEND_PID" 2>/dev/null || true
    if wait_for_backend; then
        ok "Backend up on http://localhost:${BACKEND_PORT}"
    fi
fi

# Google OAuth uses http://localhost:PORT/auth/callback → forward device localhost to host Ktor.
# This is the ONLY way the Chrome Custom Tab on a real Galaxy XR can reach
# our Ktor server (10.0.2.2 is emulator-only and will never resolve on device).
if "$ADB" -s "$TARGET" reverse "tcp:${BACKEND_PORT}" "tcp:${BACKEND_PORT}"; then
    ok "adb reverse tcp:${BACKEND_PORT} (device localhost → host; required for OAuth)"
else
    warn "adb reverse failed — run: ./backend/reverse.sh ${BACKEND_PORT} $TARGET"
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
echo -e "  ${CYAN}Backend:${NC}     http://localhost:${BACKEND_PORT}  ${DIM}(device → localhost via adb reverse)${NC}"
echo -e "  ${CYAN}Backend log:${NC} tail -f backend/backend.log"
echo -e "  ${CYAN}App logs:${NC}    adb logcat --pid=\$(adb shell pidof $APP_PACKAGE) 2>/dev/null"
echo -e "  ${CYAN}Stop app:${NC}    adb shell am force-stop $APP_PACKAGE"
echo -e "  ${CYAN}Stop backend:${NC} ./start.sh --stop"
echo -e "  ${CYAN}Rebuild:${NC}     ./start.sh"
echo ""

if $TAIL_LOGS; then
    info "Tailing logs (Ctrl+C to stop)..."
    sleep 1
    "$ADB" -s "$TARGET" logcat --pid="$("$ADB" -s "$TARGET" shell pidof "$APP_PACKAGE" 2>/dev/null || echo 0)" 2>/dev/null || \
        "$ADB" -s "$TARGET" logcat "*:S" "AndroidRuntime:E" "ActivityManager:I"
fi
