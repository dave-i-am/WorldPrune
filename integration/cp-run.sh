#!/usr/bin/env bash
# integration/cp-run.sh — CoreProtect integration tests for WorldPrune.
#
# Verifies that WorldPrune's CoreProtect rescue pass correctly keeps regions
# that have player activity in the CP log and prunes regions with no activity.
#
# Prerequisites:
#   1. Paper server running with WorldPrune AND CoreProtect installed.
#   2. integration/cp-seed.sh has run successfully (dummy .mca files + CP DB seeded).
#
# Environment:
#   MINECRAFT_CONTAINER   (default: paper-test-server)
#   MINECRAFT_WORLD       (default: world)

set -uo pipefail

CONTAINER="${MINECRAFT_CONTAINER:-paper-test-server}"
WORLD="${MINECRAFT_WORLD:-world}"
PLUGIN_DATA="/data/plugins/WorldPrune"

PASS=0
FAIL=0
ERRORS=()

# ── Colours ───────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

# ── Helpers ───────────────────────────────────────────────────────────────────

rcon()  { docker exec "$CONTAINER" rcon-cli "$@" 2>&1; }
dexec() { docker exec "$CONTAINER" "$@" 2>&1; }

pass() { echo -e "  ${GREEN}✓${RESET} $1"; ((PASS++)); }

fail() {
    echo -e "  ${RED}✗${RESET} $1"
    ERRORS+=("$1")
    ((FAIL++))
}

assert_contains() {
    local desc="$1" needle="$2" haystack="$3"
    if echo "$haystack" | grep -qF -- "$needle"; then
        pass "$desc"
    else
        fail "$desc — expected: $(echo "$needle" | head -c 80)"
        echo -e "       ${YELLOW}output was:${RESET} $(echo "$haystack" | head -c 300)"
    fi
}

assert_not_contains() {
    local desc="$1" needle="$2" haystack="$3"
    if echo "$haystack" | grep -qF -- "$needle"; then
        fail "$desc — unexpected: $needle"
        echo -e "       ${YELLOW}output was:${RESET} $(echo "$haystack" | head -c 300)"
    else
        pass "$desc"
    fi
}

section() { echo -e "\n${CYAN}${BOLD}▶ $1${RESET}"; }

# ── Preflight ─────────────────────────────────────────────────────────────────

section "Preflight"
if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
    echo -e "${RED}Container '$CONTAINER' is not running. Aborting.${RESET}"
    exit 1
fi
pass "Container '$CONTAINER' is running"

# ── 1. /prune status — verify CoreProtect is active ──────────────────────────

section "prune status — CoreProtect integration"
STATUS=$(rcon "prune status")
assert_contains "status shows CoreProtect line"  "CoreProtect:"  "$STATUS"
assert_contains "CoreProtect is reported active" "active"        "$STATUS"

if ! echo "$STATUS" | grep -qF "active"; then
    echo -e "\n  ${RED}CoreProtect is not active — rescue-pass tests cannot proceed.${RESET}"
    echo -e "  ${YELLOW}Run integration/cp-seed.sh first to deploy and initialise CoreProtect.${RESET}"
    echo -e "\n${BOLD}Results: ${GREEN}$PASS passed${RESET}, ${RED}$FAIL failed${RESET}"
    exit 1
fi

# ── 2. Scan — generate a combined plan ────────────────────────────────────────

section "prune scan (CoreProtect rescue enabled)"
SCAN=$(rcon "prune scan $WORLD")
assert_contains "scan start acknowledged" "Scanning" "$SCAN"
assert_contains "scan names the world"    "$WORLD"   "$SCAN"

# Fix permissions after any region/entity file moves
docker exec "$CONTAINER" bash -c "chown -R minecraft:minecraft /data/$WORLD/region /data/$WORLD/entities 2>/dev/null || true"

echo "    (waiting 10 s for async scan...)"
sleep 10

PLANS_OUT=$(rcon "prune plans $WORLD")
PLAN_ID=$(echo "$PLANS_OUT" | grep -oE 'plan-combined-[0-9]+-[0-9]+' | head -1)
assert_contains "scan produced a combined plan"       "plan-combined-" "$PLANS_OUT"
assert_contains "plan list shows world name"          "$WORLD"         "$PLANS_OUT"

if [[ -z "$PLAN_ID" ]]; then
    fail "Could not extract combined plan ID — aborting remaining tests"
    echo -e "\n${BOLD}Results: ${GREEN}$PASS passed${RESET}, ${RED}$FAIL failed${RESET}"
    exit 1
fi
echo "    Plan ID: $PLAN_ID"

REPORT_DIR="${PLUGIN_DATA}/reports/${PLAN_ID}/${WORLD}"

# ── 3. Verify rescue: r.0.0 kept, r.1.0 pruned ───────────────────────────────

section "CoreProtect rescue-pass outcome"
KEEP_FILE="${REPORT_DIR}/keep-regions-combined.txt"
PRUNE_FILE="${REPORT_DIR}/prune-candidate-regions.txt"

KEEP_CONTENT=$(dexec  cat "$KEEP_FILE"  2>/dev/null || echo "FILE_NOT_FOUND")
PRUNE_CONTENT=$(dexec cat "$PRUNE_FILE" 2>/dev/null || echo "FILE_NOT_FOUND")

assert_contains     "keep-regions file is readable"       "r."        "$KEEP_CONTENT"
assert_contains     "r.0.0 rescued into keep set"         "r.0.0.mca" "$KEEP_CONTENT"
assert_not_contains "r.0.0 must NOT be a prune candidate" "r.0.0.mca" "$PRUNE_CONTENT"

assert_contains     "prune-candidates file is readable"       "r."        "$PRUNE_CONTENT"
assert_contains     "r.1.0 remains a prune candidate"         "r.1.0.mca" "$PRUNE_CONTENT"
assert_not_contains "r.1.0 must NOT appear in keep set"       "r.1.0.mca" "$KEEP_CONTENT"

# ── 4. Verify coreprotectRescued in summary.json ─────────────────────────────

section "summary.json — coreprotectRescued field"
SUMMARY_FILE="${REPORT_DIR}/summary.json"
SUMMARY=$(dexec cat "$SUMMARY_FILE" 2>/dev/null || echo "FILE_NOT_FOUND")

assert_contains     "summary.json is readable"           "source"             "$SUMMARY"
assert_contains     "coreprotectRescued key present"     "coreprotectRescued" "$SUMMARY"
assert_not_contains "rescued count is not zero"          '"coreprotectRescued": 0' "$SUMMARY"

# ── 5. Plan show reflects the rescue ─────────────────────────────────────────

section "prune plan <id>"
PLAN_SHOW=$(rcon "prune plan $PLAN_ID")
assert_contains "plan show lists plan ID"  "$PLAN_ID" "$PLAN_SHOW"
assert_contains "plan show has Keep count" "Keep:"    "$PLAN_SHOW"

# ── Summary ───────────────────────────────────────────────────────────────────

echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo -e "${BOLD}CP E2E Results: ${GREEN}$PASS passed${RESET}  ${RED}$FAIL failed${RESET}"
if [[ $FAIL -gt 0 ]]; then
    echo -e "${RED}Failed assertions:${RESET}"
    for err in "${ERRORS[@]}"; do
        echo -e "  ${RED}•${RESET} $err"
    done
fi
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"

[[ $FAIL -eq 0 ]]
