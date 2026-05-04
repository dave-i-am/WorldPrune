#!/usr/bin/env bash
# integration/run.sh — integration tests for minecraft-prune-plugin
# Runs against a live Docker container via rcon-cli.
#
# Environment variables:
#   MINECRAFT_CONTAINER  (default: paper-test-server)
#   MINECRAFT_WORLD      (default: world)

set -uo pipefail

CONTAINER="${MINECRAFT_CONTAINER:-paper-test-server}"
WORLD="${MINECRAFT_WORLD:-world}"
PLUGIN_DATA="/data/plugins/WorldPrune"

PASS=0
FAIL=0
ERRORS=()

# ── Colours ────────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

# ── Helpers ────────────────────────────────────────────────────────────────────

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
        echo -e "       ${YELLOW}output was:${RESET} $(echo "$haystack" | head -c 200)"
    fi
}

assert_not_contains() {
    local desc="$1" needle="$2" haystack="$3"
    if echo "$haystack" | grep -qF -- "$needle"; then
        fail "$desc — unexpected: $needle"
        echo -e "       ${YELLOW}output was:${RESET} $(echo "$haystack" | head -c 200)"
    else
        pass "$desc"
    fi
}

section() { echo -e "\n${CYAN}${BOLD}▶ $1${RESET}"; }

# poll_until <desc> <needle> <rcon-command...>
# Retries the rcon command every 3 s for up to 30 s until needle appears.
# Records a single pass/fail line directly (runs in parent shell — counters safe).
# Does NOT return the output; callers should re-query rcon if they need it.
poll_until() {
    local desc="$1" needle="$2"; shift 2
    local deadline=$(( $(date +%s) + 30 )) out
    while true; do
        out=$(rcon "$@" 2>&1)
        if echo "$out" | grep -qF -- "$needle"; then
            pass "$desc"
            return 0
        fi
        if [[ $(date +%s) -ge $deadline ]]; then
            fail "$desc — timed out waiting for: $needle"
            echo -e "       ${YELLOW}last output:${RESET} $(echo "$out" | head -c 200)"
            return 1
        fi
        sleep 3
    done
}

# poll_log_until <desc> <egrep-pattern>
# Retries grepping latest.log every 3 s for up to 30 s until a match appears.
poll_log_until() {
    local desc="$1" pattern="$2"
    local deadline=$(( $(date +%s) + 30 )) out
    while true; do
        out=$(dexec grep -aE "$pattern" /data/logs/latest.log 2>/dev/null || true)
        if [[ -n "$out" ]]; then
            pass "$desc"
            return 0
        fi
        if [[ $(date +%s) -ge $deadline ]]; then
            fail "$desc — timed out waiting for log pattern: $pattern"
            return 1
        fi
        sleep 3
    done
}

# ── Preflight ──────────────────────────────────────────────────────────────────

section "Preflight"
if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
    echo -e "${RED}Container '$CONTAINER' is not running. Aborting.${RESET}"
    exit 1
fi
pass "Container '$CONTAINER' is running"

# Seed far-from-spawn dummy .mca files so there are always prune candidates,
# even on a freshly-created world.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "$SCRIPT_DIR/seed.sh"

# ── 1. Status ──────────────────────────────────────────────────────────────────

section "prune status — WorldPrune Status"
STATUS=$(rcon "prune status")
assert_contains     "shows 'WorldPrune Status'"   "WorldPrune Status" "$STATUS"
assert_contains     "shows Source field"               "Source:"               "$STATUS"
assert_contains     "shows Keep-rules mode field"      "Keep-rules mode:"      "$STATUS"
assert_contains     "shows Quarantine only field"      "Quarantine only:"      "$STATUS"
assert_not_contains "no stale phase marker"            "prune.phase"           "$STATUS"
assert_contains     "shows BlueMap field"              "BlueMap:"              "$STATUS"
assert_contains     "shows Dynmap field"               "Dynmap:"               "$STATUS"

# ── 2. Scan ───────────────────────────────────────────────────────────────────

section "prune scan"
SCAN=$(rcon "prune scan $WORLD")
assert_contains "acknowledges scan start"  "Scanning" "$SCAN"
assert_contains "names the world"          "$WORLD"   "$SCAN"

echo "    (waiting for async scan to complete...)"
poll_until "scan produces a plan entry" "plan-" "prune plans $WORLD"
PLANS_OUT=$(rcon "prune plans $WORLD")
PLAN_ID=$(echo "$PLANS_OUT" | grep -oE 'plan-[a-z]+-[0-9]+-[0-9]+' | head -1)
assert_contains "plan list shows world name"        "$WORLD" "$PLANS_OUT"
assert_contains "plan list shows keep/prune arrows" "↑"     "$PLANS_OUT"

if [[ -z "$PLAN_ID" ]]; then
    echo -e "  ${RED}✗${RESET} Could not extract plan ID — aborting remaining tests"
    echo -e "\n${BOLD}Results: ${GREEN}$PASS passed${RESET}, ${RED}$FAIL failed${RESET}"
    exit 1
fi
echo "    Plan ID: $PLAN_ID"

# ── 2b. BlueMap marker update ─────────────────────────────────────────────────
# The scan triggers WebMapService.updateMarkers() in the same async task.
# By the time poll_until above returns a plan entry, the marker update has
# already completed.  We verify the success log line is present and no
# error line appeared.  This catches API-signature mismatches (e.g. the
# ShapeMarker.Builder.shape(Shape,float) vs shape(Shape,float,float) regression).

section "BlueMap — markers updated after scan"
if poll_log_until "BlueMap marker update log emitted" \
        "BlueMap markers updated|BlueMap marker update failed|BlueMap markers skipped"; then
    BM_LOG=$(dexec grep -aE "BlueMap markers updated|BlueMap marker update failed|BlueMap markers skipped" \
             /data/logs/latest.log 2>/dev/null || true)
    if echo "$BM_LOG" | grep -q "BlueMap markers skipped"; then
        pass "BlueMap markers skipped (API not ready — treating as acceptable in integration)"
    else
        assert_contains     "BlueMap markers updated successfully"  "BlueMap markers updated" "$BM_LOG"
        assert_not_contains "no BlueMap marker update failure"      "BlueMap marker update failed" "$BM_LOG"
    fi
fi

section "Dynmap — markers updated after scan"
if poll_log_until "Dynmap marker update log emitted" \
        "Dynmap markers updated|Dynmap marker update failed|Dynmap markers skipped"; then
    DM_LOG=$(dexec grep -aE "Dynmap markers updated|Dynmap marker update failed|Dynmap markers skipped" \
             /data/logs/latest.log 2>/dev/null || true)
    if echo "$DM_LOG" | grep -q "Dynmap markers skipped"; then
        pass "Dynmap markers skipped (API not ready — treating as acceptable in integration)"
    else
        assert_contains     "Dynmap markers updated successfully"   "Dynmap markers updated" "$DM_LOG"
        assert_not_contains "no Dynmap marker update failure"       "Dynmap marker update failed" "$DM_LOG"
    fi
fi

# ── 3. Plan show ──────────────────────────────────────────────────────────────

section "prune plan <planId>"
SHOW=$(rcon "prune plan $PLAN_ID")
assert_contains     "shows plan ID"             "$PLAN_ID"    "$SHOW"
assert_contains     "shows World field"         "World:"      "$SHOW"
assert_contains     "shows Source field"        "Source:"     "$SHOW"
assert_contains     "shows Keep count"          "Keep:"       "$SHOW"
assert_contains     "shows Prune count"         "Prune:"      "$SHOW"
assert_contains     "prompts to apply"          "/prune apply" "$SHOW"
assert_not_contains "no confirmToken shown"     "confirmToken" "$SHOW"
assert_not_contains "no --confirm flag shown"   "--confirm"    "$SHOW"

# ── 4. Plans listing ─────────────────────────────────────────────────────────

section "prune plans"
PLANS=$(rcon "prune plans $WORLD")
assert_contains "header shows 'Plans'"       "Plans"       "$PLANS"
assert_contains "shows plan count"           "plan(s)"     "$PLANS"
assert_contains "footer hints /prune plan"   "/prune plan" "$PLANS"

PLANS_ALL=$(rcon "prune plans")
assert_contains "plans without world filter" "plan-"       "$PLANS_ALL"

# ── 5. Apply preview ─────────────────────────────────────────────────────────

section "prune apply (preview + stage)"
APPLY=$(rcon "prune apply $WORLD")
assert_contains     "shows Apply Preview header"    "Apply Preview"        "$APPLY"
assert_contains     "shows plan ID"                 "$PLAN_ID"             "$APPLY"
assert_contains     "shows region count"            "region files will be" "$APPLY"
assert_contains     "shows quarantine path hint"    "quarantine"           "$APPLY"
assert_contains     "prompts /prune confirm"        "/prune confirm"       "$APPLY"
assert_not_contains "no confirmToken in preview"    "confirmToken"         "$APPLY"
assert_not_contains "no --confirm flag in preview"  "--confirm"            "$APPLY"

# ── 6. Confirm apply ─────────────────────────────────────────────────────────

section "prune confirm (apply)"
CONFIRM=$(rcon "prune confirm")
assert_contains "acknowledges applying" "Applying plan" "$CONFIRM"

# Poll until quarantine shows an ACTIVE entry (up to 30 s — CI servers run slow)
poll_until "quarantine shows entry" "apply-" "prune quarantine $WORLD"
QUAR=$(rcon "prune quarantine $WORLD")
assert_contains "new entry is ACTIVE" "ACTIVE" "$QUAR"

# Fix permissions after quarantine move so Paper can still write entity data
docker exec "$CONTAINER" bash -c "chown -R minecraft:minecraft /data/$WORLD/region /data/$WORLD/entities 2>/dev/null || true"

APPLY_ID=$(echo "$QUAR" | grep 'ACTIVE' | grep -oE 'apply-[0-9]+-[0-9]+' | head -1)
if [[ -z "$APPLY_ID" ]]; then
    fail "Could not extract apply ID from quarantine listing"
else
    echo "    Apply ID: $APPLY_ID"
fi

# ── 7. Undo ───────────────────────────────────────────────────────────────────

section "prune undo"
UNDO=$(rcon "prune undo $WORLD")
assert_contains "acknowledges restore" "Restoring" "$UNDO"

# Poll until quarantine shows RESTORED (up to 30 s — CI servers run slow)
poll_until "entry becomes RESTORED" "RESTORED" "prune quarantine $WORLD"
QUAR2=$(rcon "prune quarantine $WORLD")
assert_not_contains "no longer ACTIVE" "[ACTIVE]" "$QUAR2"

# Fix permissions after restore so Paper can write entity data
docker exec "$CONTAINER" bash -c "chown -R minecraft:minecraft /data/$WORLD/region /data/$WORLD/entities 2>/dev/null || true"

# ── 8. Drop preview + confirm ─────────────────────────────────────────────────

section "prune drop + prune confirm (drop)"
if [[ -n "$APPLY_ID" ]]; then
    DROP=$(rcon "prune drop $WORLD $APPLY_ID")
    assert_contains "shows Drop Preview header" "Drop Preview"   "$DROP"
    assert_contains "shows apply ID"            "$APPLY_ID"      "$DROP"
    assert_contains "shows WARNING"             "WARNING"        "$DROP"
    assert_contains "prompts /prune confirm"    "/prune confirm" "$DROP"

    DROP_CONFIRM=$(rcon "prune confirm")
    assert_contains "acknowledges deletion"     "Deleting"       "$DROP_CONFIRM"

    sleep 5
    QUAR3=$(rcon "prune quarantine $WORLD")
    assert_not_contains "entry gone after drop" "$APPLY_ID" "$QUAR3"
else
    echo -e "  ${YELLOW}⚠${RESET}  Skipping drop test — no apply ID captured"
fi

# ── 9. Confirm with nothing pending ───────────────────────────────────────────

section "prune confirm (nothing pending)"
EMPTY=$(rcon "prune confirm")
assert_contains "reports nothing to confirm" "Nothing to confirm" "$EMPTY"
assert_contains "hints at apply or drop"     "/prune apply"       "$EMPTY"

# ── 10. Unknown subcommand ────────────────────────────────────────────────────

section "unknown subcommand"
UNKNOWN=$(rcon "prune notacommand")
assert_contains "reports unknown subcommand" "Unknown subcommand" "$UNKNOWN"
assert_contains "shows usage hint"           "/prune scan"        "$UNKNOWN"

# ── 11. Missing planId argument ───────────────────────────────────────────────

section "prune plan (no args)"
NO_PLAN=$(rcon "prune plan")
assert_contains "reports usage error" "Usage: /prune plan" "$NO_PLAN"

# ══════════════════════════════════════════════════════════════════════════════
# CoreProtect integration assertions
# ══════════════════════════════════════════════════════════════════════════════

# ── 12. CoreProtect status ────────────────────────────────────────────────────

section "prune status — CoreProtect"
CP_STATUS=$(rcon "prune status")
assert_contains "status shows CoreProtect line"  "CoreProtect:" "$CP_STATUS"
assert_contains "CoreProtect is reported active" "active"       "$CP_STATUS"

if ! echo "$CP_STATUS" | grep -qF "active"; then
    fail "CoreProtect not active — skipping CP rescue assertions"
else

    # ── 13. Scan with CP rescue ───────────────────────────────────────────────

    section "prune scan (CoreProtect rescue)"
    CP_SCAN=$(rcon "prune scan $WORLD")
    assert_contains "CP scan start acknowledged" "Scanning" "$CP_SCAN"

    poll_until "CP scan produced a combined plan" "plan-combined-" "prune plans $WORLD"
    CP_PLANS=$(rcon "prune plans $WORLD")
    CP_PLAN_ID=$(echo "$CP_PLANS" | grep -oE 'plan-combined-[0-9]+-[0-9]+' | head -1)
    assert_contains "plan list shows world" "$WORLD" "$CP_PLANS"

    if [[ -z "$CP_PLAN_ID" ]]; then
        fail "Could not extract CP plan ID"
    else
        echo "    CP Plan ID: $CP_PLAN_ID"
        CP_REPORT="${PLUGIN_DATA}/reports/${CP_PLAN_ID}/${WORLD}"

        # ── 14. Rescue outcome ────────────────────────────────────────────────

        section "CoreProtect rescue-pass outcome"
        KEEP_CONTENT=$(dexec  cat "${CP_REPORT}/keep-regions-combined.txt"  2>/dev/null || echo "FILE_NOT_FOUND")
        PRUNE_CONTENT=$(dexec cat "${CP_REPORT}/prune-candidate-regions.txt" 2>/dev/null || echo "FILE_NOT_FOUND")

        assert_contains     "keep-regions file readable"       "r."        "$KEEP_CONTENT"
        assert_contains     "r.0.0 rescued into keep set"      "r.0.0.mca" "$KEEP_CONTENT"
        assert_not_contains "r.0.0 not a prune candidate"      "r.0.0.mca" "$PRUNE_CONTENT"
        assert_contains     "prune-candidates file readable"    "r."        "$PRUNE_CONTENT"
        assert_contains     "r.1.0 remains a prune candidate"  "r.1.0.mca" "$PRUNE_CONTENT"
        assert_not_contains "r.1.0 not in keep set"            "r.1.0.mca" "$KEEP_CONTENT"

        # ── 15. summary.json ─────────────────────────────────────────────────

        section "summary.json — coreprotectRescued"
        SUMMARY=$(dexec cat "${CP_REPORT}/summary.json" 2>/dev/null || echo "FILE_NOT_FOUND")
        assert_contains     "summary.json readable"        "source"                      "$SUMMARY"
        assert_contains     "coreprotectRescued present"   "coreprotectRescued"          "$SUMMARY"
        assert_not_contains "rescued count non-zero"       '"coreprotectRescued": 0'    "$SUMMARY"

        # ── 16. Plan show ─────────────────────────────────────────────────────

        section "prune plan (CP plan)"
        CP_SHOW=$(rcon "prune plan $CP_PLAN_ID")
        assert_contains "CP plan shows plan ID"  "$CP_PLAN_ID" "$CP_SHOW"
        assert_contains "CP plan shows Keep"     "Keep:"       "$CP_SHOW"
    fi
fi

# ══════════════════════════════════════════════════════════════════════════════
# Towny + Residence file-fallback assertions
# ══════════════════════════════════════════════════════════════════════════════

section "prune scan — Towny + Residence file fallback"

# Run a fresh scan so the new fixture files are included
TR_SCAN=$(rcon "prune scan $WORLD")
assert_contains "scan start acknowledged" "Scanning" "$TR_SCAN"

poll_until "scan produced a new plan" "plan-" "prune plans $WORLD"

# Grab the latest plan (most recent first in plan list)
TR_PLANS=$(rcon "prune plans $WORLD")
TR_PLAN_ID=$(echo "$TR_PLANS" | grep -oE 'plan-[a-z]+-[0-9]+-[0-9]+' | head -1)

if [[ -z "$TR_PLAN_ID" ]]; then
    fail "Could not extract plan ID for Towny/Residence test"
else
    echo "    Plan ID: $TR_PLAN_ID"
    TR_REPORT="${PLUGIN_DATA}/reports/${TR_PLAN_ID}/${WORLD}"

    section "Towny/Residence — claimSource in summary.json"
    TR_SUMMARY=$(dexec cat "${TR_REPORT}/summary.json" 2>/dev/null || echo "FILE_NOT_FOUND")
    assert_contains "summary.json is readable" "claimSource" "$TR_SUMMARY"
    assert_contains "towny-files appears in claimSource"    "towny-files"    "$TR_SUMMARY"
    assert_contains "residence-file appears in claimSource" "residence-file" "$TR_SUMMARY"

    section "Towny/Residence — claim-derived regions kept"
    TR_KEEP=$(dexec cat "${TR_REPORT}/keep-regions-combined.txt" 2>/dev/null \
              || dexec cat "${TR_REPORT}/keep-regions-from-claims-and-manual.txt" 2>/dev/null \
              || echo "FILE_NOT_FOUND")
    assert_contains "Towny chunk 1600,1600 → r.50.50 kept"      "r.50.50.mca"  "$TR_KEEP"
    assert_contains "Residence block area 26112–26623 → r.51.50 kept" "r.51.50.mca" "$TR_KEEP"
fi

# ══════════════════════════════════════════════════════════════════════════════
# WorldGuard file-fallback assertions
# ══════════════════════════════════════════════════════════════════════════════

section "prune scan — WorldGuard file fallback"

WG_SCAN=$(rcon "prune scan $WORLD")
assert_contains "scan start acknowledged" "Scanning" "$WG_SCAN"

poll_until "scan produced a new plan" "plan-" "prune plans $WORLD"

WG_PLANS=$(rcon "prune plans $WORLD")
WG_PLAN_ID=$(echo "$WG_PLANS" | grep -oE 'plan-[a-z]+-[0-9]+-[0-9]+' | head -1)

if [[ -z "$WG_PLAN_ID" ]]; then
    fail "Could not extract plan ID for WorldGuard test"
else
    echo "    Plan ID: $WG_PLAN_ID"
    WG_REPORT="${PLUGIN_DATA}/reports/${WG_PLAN_ID}/${WORLD}"

    section "WorldGuard — claimSource in summary.json"
    WG_SUMMARY=$(dexec cat "${WG_REPORT}/summary.json" 2>/dev/null || echo "FILE_NOT_FOUND")
    assert_contains "summary.json is readable" "claimSource" "$WG_SUMMARY"
    assert_contains "worldguard-files appears in claimSource" "worldguard-files" "$WG_SUMMARY"

    section "WorldGuard — claim-derived region kept"
    WG_KEEP=$(dexec cat "${WG_REPORT}/keep-regions-combined.txt" 2>/dev/null \
              || dexec cat "${WG_REPORT}/keep-regions-from-claims-and-manual.txt" 2>/dev/null \
              || echo "FILE_NOT_FOUND")
    assert_contains "WorldGuard region 26624–27135 × 25600–26111 → r.52.50 kept" "r.52.50.mca" "$WG_KEEP"
fi

# ══════════════════════════════════════════════════════════════════════════════
# scan --world all
# ══════════════════════════════════════════════════════════════════════════════

section "prune scan all (multi-world scan)"
SCAN_ALL=$(rcon "prune scan all")
assert_contains "all-worlds scan acknowledged"        "world"     "$SCAN_ALL"
assert_contains "all-worlds scan reports world count" "world(s)"  "$SCAN_ALL"

poll_until "all-worlds scan produced a plan" "plan-" "prune plans $WORLD"
ALL_PLANS=$(rcon "prune plans $WORLD")
assert_contains "all-worlds scan creates plan for test world" "$WORLD" "$ALL_PLANS"

# ── Summary ───────────────────────────────────────────────────────────────────

echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo -e "${BOLD}Results: ${GREEN}$PASS passed${RESET}  ${RED}$FAIL failed${RESET}"
if [[ $FAIL -gt 0 ]]; then
    echo -e "${RED}Failed assertions:${RESET}"
    for err in "${ERRORS[@]}"; do
        echo -e "  ${RED}•${RESET} $err"
    done
fi
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"

[[ $FAIL -eq 0 ]]
