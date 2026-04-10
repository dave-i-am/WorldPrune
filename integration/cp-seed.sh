#!/usr/bin/env bash
# integration/cp-seed.sh — Seeds the CoreProtect SQLite DB with block activity for
# region r.0.0 and creates dummy .mca fixture files for r.0.0 and r.1.0.
#
# CoreProtect itself is declared in docker-compose.yml via MODRINTH_PROJECTS
# and is downloaded by itzg on container first-start — no manual deploy needed.
#
# Must be run AFTER the server is running with WorldPrune deployed.
#
# Environment:
#   MINECRAFT_CONTAINER   (default: paper-test-server)
#   MINECRAFT_WORLD       (default: world)

set -euo pipefail

CONTAINER="${MINECRAFT_CONTAINER:-paper-test-server}"
WORLD="${MINECRAFT_WORLD:-world}"

echo "════════════════════════════════════════════════════"
echo " CoreProtect E2E Setup  (container: $CONTAINER)"
echo " World: $WORLD"
echo "════════════════════════════════════════════════════"

# ── 1. Wait for CoreProtect DB to exist ──────────────────────────────────────
# CoreProtect is brought in via MODRINTH_PROJECTS on container first-start.
# Poll until the DB file appears (created by CP on its first enable).

echo ""
echo "▶ Waiting for CoreProtect DB to initialise..."
DB_PATH="/data/plugins/CoreProtect/database.db"
MAX_WAIT=120
waited=0
while ! docker exec "$CONTAINER" test -f "$DB_PATH" 2>/dev/null; do
    if [[ $waited -ge $MAX_WAIT ]]; then
        echo "  ERROR: DB not found at $DB_PATH after ${MAX_WAIT}s"
        echo "  Check server logs:"
        echo "    docker logs $CONTAINER | grep -i coreprotect"
        exit 1
    fi
    sleep 3
    waited=$((waited + 3))
done
echo "    DB found (waited ${waited}s)"

# ── 2. Create dummy .mca files (no entity signals) ───────────────────────────
#
#   Region r.0.0 → blocks [0..511] × [0..511]   ← will be rescued by CP
#   Region r.1.0 → blocks [512..1023] × [0..511] ← no CP activity, stays pruned
#
#   Files live in both region/ and entities/ so that:
#     • PlanService finds them via region/ (existingRegionNames)
#     • HeuristicService's entity-aware pass finds them via entities/
#   An 8192-byte zero file has no chunk header entries → entity-aware returns
#   "empty-header" → prune candidate, which is what we want before CP rescues r.0.0.

echo ""
echo "▶ Creating dummy region files for r.0.0 and r.1.0 (no entity signals)..."
docker exec "$CONTAINER" python3 -c "
zeros = b'\\x00' * 8192
world = '${WORLD}'
for d in ('region', 'entities'):
    import os; os.makedirs(f'/data/{world}/{d}', exist_ok=True)
    for f in ('r.0.0.mca', 'r.1.0.mca'):
        open(f'/data/{world}/{d}/{f}', 'wb').write(zeros)
        print(f'  created /data/{world}/{d}/{f}')
"

# ── 3. Seed CoreProtect DB with block activity at region r.0.0 ───────────────
#
#   Inserts one block-placement event at (256, 64, 256), which lies inside the
#   X/Z bounds queried for region r.0.0 from CoreProtect's co_block table
#   (using SQL BETWEEN min/max filters). Region r.1.0 gets no activity,
#   ensuring it stays as a prune candidate.

echo ""
echo "▶ Seeding CoreProtect SQLite DB with activity for region r.0.0..."
docker exec -i "$CONTAINER" python3 << 'PYEOF'
import sqlite3, time, os, sys

DB = '/data/plugins/CoreProtect/database.db'
if not os.path.exists(DB):
    print(f"ERROR: DB not found at {DB}")
    print("Make sure CoreProtect loaded successfully (check server log).")
    sys.exit(1)

conn = sqlite3.connect(DB)
c    = conn.cursor()

# ── Schema diagnostics ─────────────────────────────────────────────────────────
try:
    cols = [row[1] for row in c.execute("PRAGMA table_info(co_block)").fetchall()]
    print(f"  co_block columns: {cols}")
except Exception as e:
    print(f"  WARNING: could not read schema: {e}")

# ── Insert world and test user ─────────────────────────────────────────────────
c.execute("INSERT OR IGNORE INTO co_world (world) VALUES ('world')")
c.execute("INSERT OR IGNORE INTO co_user  (user, uuid) "
          "VALUES ('e2e_test_player', '00000000-0000-0000-0000-000000000001')")
conn.commit()

wid = c.execute("SELECT id FROM co_world WHERE world='world'").fetchone()
uid = c.execute("SELECT id FROM co_user  WHERE user='e2e_test_player'").fetchone()
if not wid or not uid:
    print("ERROR: world/user rows missing after insert")
    sys.exit(1)
wid, uid = wid[0], uid[0]

# ── Insert block placement at (256, 64, 256) — 1 day ago ──────────────────────
t = int(time.time()) - 86400
c.execute(
    "INSERT INTO co_block (time, user, wid, x, y, z, type, action, rolled_back) "
    "VALUES (?, ?, ?, 256, 64, 256, 1, 1, 0)",
    (t, uid, wid)
)
conn.commit()
conn.close()

print(f"  Inserted: x=256, y=64, z=256  wid={wid}  time={t}  (1 day ago)")
print("  r.0.0.mca (blocks 0-511 × 0-511)   → has CP activity → will be rescued by WorldPrune")
print("  r.1.0.mca (blocks 512-1023 × 0-511) → no CP activity  → stays as prune candidate")
PYEOF

echo ""
echo "════════════════════════════════════════════════════"
echo " Setup complete ✓  Run: ./gradlew integrationTestCP"
echo "════════════════════════════════════════════════════"
