#!/usr/bin/env bash
# run-gates.sh - one-shot test gate script
# Target environment: Git Bash on Windows (also works in any bash with a JDK).
# Runs, in order: static boundary checks -> JVM unit tests -> connected device check -> instrumentation tests.
# Any failure exits with a non-zero code. Use --unit-only to run only the JVM unit tests.

set -u

UNIT_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --unit-only|-u) UNIT_ONLY=1 ;;
        *)
            echo "Unknown argument: $arg"
            echo "Usage: $0 [--unit-only|-u]"
            exit 2
            ;;
    esac
done

fail() {
    echo ""
    echo "GATE FAILED: $1" >&2
    exit 1
}

# --- Step 1: locate repo root (parent of this script's directory) and pick gradlew ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT" || fail "Cannot cd to repo root '$REPO_ROOT'."

# Git Bash on Windows executes .bat directly; fall back to the sh wrapper elsewhere.
if [ -f "$REPO_ROOT/gradlew.bat" ] && [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]]; then
    GRADLEW="./gradlew.bat"
elif [ -f "$REPO_ROOT/gradlew" ]; then
    GRADLEW="./gradlew"
else
    fail "Neither gradlew.bat (Windows) nor gradlew found in '$REPO_ROOT'."
fi
echo "Repo root: $REPO_ROOT"
echo "Gradle wrapper: $GRADLEW"

# --- Step 2: record current commit ---
COMMIT="$(git rev-parse --short HEAD 2>/dev/null)" ||
    fail "Unable to resolve current git commit (git rev-parse failed)."
[ -n "$COMMIT" ] || fail "Unable to resolve current git commit (empty output)."
echo "Commit: $COMMIT"


echo ""
echo "== Step 0: static boundary checks =="
# Realm is fully removed — no exemption, main + test sources alike.
REALM_VIOLATIONS="$(grep -rlE '^import (io\.realm|com\.amperfy\.data\.realm|com\.amperfy\.data\.local\.realm)' app/src --include='*.kt' || true)"
if [ -n "$REALM_VIOLATIONS" ]; then
    printf '%s\n' "$REALM_VIOLATIONS"
    fail "Realm import found (Realm has been fully removed)."
fi
echo "Realm ban:      OK"
ROOM_VIOLATIONS="$(grep -rlE '^import androidx\.room' app/src/main/java --include='*.kt' | grep -vE '/data/local/db/' || true)"
if [ -n "$ROOM_VIOLATIONS" ]; then
    printf '%s\n' "$ROOM_VIOLATIONS"
    fail "androidx.room import outside data/local/db/ (Room boundary gate)."
fi
echo "Room boundary:  OK"
# Icon system: every icon must go through AmperfyIcons; Material icon imports are not allowed
# anywhere (no exemptions).
ICON_VIOLATIONS="$(grep -rlE '^import androidx\.compose\.material\.icons' app/src/main/java --include='*.kt' || true)"
if [ -n "$ICON_VIOLATIONS" ]; then
    printf '%s\n' "$ICON_VIOLATIONS"
    fail "androidx.compose.material.icons import found (icon gate: use AmperfyIcons)."
fi
echo "Icon boundary:  OK"

# --- Step 3: JVM unit tests ---
echo ""
echo "== Step 1/3: JVM unit tests (testDebugUnitTest) =="
"$GRADLEW" testDebugUnitTest ||
    fail "testDebugUnitTest failed. Report: app/build/reports/tests/testDebugUnitTest/"

if [ "$UNIT_ONLY" -eq 1 ]; then
    echo ""
    echo "UnitOnly mode: skipping connected tests."
    echo "NOTE: --unit-only does NOT constitute a full gate pass."
    exit 0
fi

# --- Step 4: connected device check ---
echo ""
echo "== Step 2/3: connected device check (adb devices) =="
command -v adb >/dev/null 2>&1 || fail "adb not found in PATH."
ADB_OUTPUT="$(adb devices)" || fail "adb devices returned a non-zero exit code."
# Count lines whose second column is exactly "device" (skips header and offline/unauthorized).
DEVICE_COUNT="$(printf '%s\n' "$ADB_OUTPUT" | awk 'NR>1 && $2=="device"' | wc -l | tr -d '[:space:]')"
if [ "$DEVICE_COUNT" -eq 0 ]; then
    printf '%s\n' "$ADB_OUTPUT"
    fail "No connected device/emulator. Connected tests require one."
fi
echo "Connected devices: $DEVICE_COUNT"

# --- Step 5: instrumentation tests ---
echo ""
echo "== Step 3/3: instrumentation tests (connectedDebugAndroidTest) =="
"$GRADLEW" connectedDebugAndroidTest ||
    fail "connectedDebugAndroidTest failed. Report: app/build/reports/androidTests/connected/"

# --- Step 4: Room schema validation ---
# Room exportSchema=true writes app/schemas/<db>/<version>.json at build time; that JSON is the
# migration baseline and MUST be committed. This gate hard-fails only when the file is missing or
# empty (exportSchema silently disabled, or the artifact was never committed).
#
# Ruling: a schema JSON *changed* by this build does NOT fail the gate. This repo's workflow is
# "gate first, commit second" — the JSON the build just regenerated is by definition uncommitted at
# gate time, so treating "dirty app/schemas" as failure would make every legitimate schema-changing
# batch impossible to pass. A missing/empty file is the real hard error; a changed file only warns
# so the operator remembers to include the regenerated JSON in this batch's commit.
#
# Placed after the connected/instrumentation step: --unit-only exits before reaching here, so it
# skips this step naturally (no explicit UNIT_ONLY guard needed).
echo ""
echo "== Step 4: Room schema validation =="
SCHEMA_FILE="app/schemas/com.amperfy.data.local.db.AmperfyDatabase/1.json"
[ -s "$SCHEMA_FILE" ] || fail "Room schema JSON missing or empty: $SCHEMA_FILE (exportSchema output must be committed)."
if [ -n "$(git status --porcelain -- app/schemas)" ]; then
    echo "NOTICE: app/schemas changed by this build — include the regenerated JSON in this batch's commit."
fi
echo "Room schema:    OK ($SCHEMA_FILE)"

echo ""
echo "ALL GATES PASSED"
