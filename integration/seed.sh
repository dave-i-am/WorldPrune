#!/usr/bin/env bash
# integration/seed.sh — Seeds the test world with all fixture files needed for the
# full integration suite (standard + CoreProtect tests).
#
#   r.100.100 / r.101.100  — far-from-spawn blank regions (always prune candidates)
#   r.0.0 / r.1.0          — near-spawn CP fixtures; r.0.0 gets CP activity seeded
#
# Also waits for the CoreProtect SQLite DB and seeds it with one block-placement
# event at (256, 64, 256) so that r.0.0 is rescued by WorldPrune's CP pass.
#
# Idempotent: only writes files/rows that do not already exist.
#
# Environment:
#   MINECRAFT_CONTAINER  (default: paper-test-server)
#   MINECRAFT_WORLD      (default: world)

set -euo pipefail

CONTAINER="${MINECRAFT_CONTAINER:-paper-test-server}"
WORLD="${MINECRAFT_WORLD:-world}"

# ── 1. Far-from-spawn prune candidates ───────────────────────────────────────
# r.100.100 / r.101.100 are 51 km from spawn — no claims, no entity signals.
echo "▶ Seeding far-from-spawn prune-candidate regions..."
docker exec "$CONTAINER" python3 -c "
import os
zeros = b'\\x00' * 8192
world = '${WORLD}'
for d in ('region', 'entities'):
    os.makedirs(f'/data/{world}/{d}', exist_ok=True)
    for f in ('r.100.100.mca', 'r.101.100.mca'):
        path = f'/data/{world}/{d}/{f}'
        if not os.path.exists(path):
            open(path, 'wb').write(zeros)
            print(f'  created {path}')
        else:
            print(f'  exists  {path}')
"

# ── 2. CoreProtect fixture regions ───────────────────────────────────────────
# r.0.0 / r.1.0 are blank files; CP activity will be seeded only into r.0.0
# so that WorldPrune rescues it while r.1.0 stays as a prune candidate.
echo "▶ Seeding CoreProtect fixture regions (r.0.0, r.1.0)..."
docker exec "$CONTAINER" python3 -c "
import os
zeros = b'\\x00' * 8192
world = '${WORLD}'
for d in ('region', 'entities'):
    os.makedirs(f'/data/{world}/{d}', exist_ok=True)
    for f in ('r.0.0.mca', 'r.1.0.mca'):
        path = f'/data/{world}/{d}/{f}'
        open(path, 'wb').write(zeros)
        print(f'  created {path}')
"

# ── 3. Wait for CoreProtect DB ───────────────────────────────────────────────
echo "▶ Waiting for CoreProtect DB..."
DB_PATH="/data/plugins/CoreProtect/database.db"
MAX_WAIT=120
waited=0
while ! docker exec "$CONTAINER" test -f "$DB_PATH" 2>/dev/null; do
    if [[ $waited -ge $MAX_WAIT ]]; then
        echo "  ERROR: DB not found at $DB_PATH after ${MAX_WAIT}s"
        docker logs "$CONTAINER" | grep -i coreprotect | tail -20
        exit 1
    fi
    sleep 3
    waited=$((waited + 3))
done
echo "    DB found (waited ${waited}s)"

# ── 4. Seed CP DB with activity for r.0.0 ────────────────────────────────────
echo "▶ Seeding CoreProtect DB (activity at x=256,y=64,z=256 → region r.0.0)..."
docker exec -i "$CONTAINER" python3 << 'PYEOF'
import sqlite3, time, os, sys

DB = '/data/plugins/CoreProtect/database.db'
conn = sqlite3.connect(DB)
c    = conn.cursor()

c.execute("INSERT OR IGNORE INTO co_world (world) VALUES ('world')")
c.execute("INSERT OR IGNORE INTO co_user  (user, uuid) VALUES ('seed_player', '00000000-0000-0000-0000-000000000001')")
conn.commit()

wid = c.execute("SELECT id FROM co_world WHERE world='world'").fetchone()[0]
uid = c.execute("SELECT id FROM co_user  WHERE user='seed_player'").fetchone()[0]

# Only insert if not already seeded (idempotent)
existing = c.execute("SELECT COUNT(*) FROM co_block WHERE x=256 AND z=256 AND wid=?", (wid,)).fetchone()[0]
if existing == 0:
    t = int(time.time()) - 86400   # 1 day ago
    c.execute(
        "INSERT INTO co_block (time, user, wid, x, y, z, type, action, rolled_back) "
        "VALUES (?, ?, ?, 256, 64, 256, 1, 1, 0)",
        (t, uid, wid)
    )
    conn.commit()
    print(f"  Inserted block event at (256,64,256) wid={wid}")
else:
    print(f"  Block event already present — skipping")

conn.close()
print("  r.0.0.mca → has CP activity → will be rescued by WorldPrune")
print("  r.1.0.mca → no CP activity  → stays as prune candidate")
PYEOF

echo "▶ Seed complete."
