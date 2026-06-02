#!/usr/bin/env bash
# integration/seed.sh — Seeds the test world with all fixture files needed for the
# integration suite.
#
#   r.100.100 / r.101.100  — far-from-spawn blank regions (always prune candidates)
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
docker exec -u minecraft "$CONTAINER" python3 -c "
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

# ── 2. Towny file-fallback fixtures ──────────────────────────────────────────
# Creates townblock .data files for the integration world so WorldPrune's
# Towny file-fallback path is exercised. We use chunk (1600,1600) → region r.50.50
# which is far from the existing test regions and won't interfere with other assertions.
echo "▶ Seeding Towny townblock fixtures (chunk 1600,1600 → region r.50.50)..."
docker exec -u minecraft "$CONTAINER" python3 -c "
import os
world = '${WORLD}'
towny_dir = f'/data/plugins/Towny/data/townblocks'
os.makedirs(towny_dir, exist_ok=True)
fname = os.path.join(towny_dir, f'{world}_1600_1600.data')
if not os.path.exists(fname):
    open(fname, 'w').close()
    print(f'  created {fname}')
else:
    print(f'  exists  {fname}')
"

# ── 3. Residence file-fallback fixture ────────────────────────────────────────
# Creates a minimal Global.yml with one residence covering block region r.51.50
# (blocks 26112–26623 × 25600–26111 → chunks 1632–1663 × 1600–1631 → region 51,50).
echo "▶ Seeding Residence Global.yml fixture (region r.51.50)..."
docker exec -u minecraft "$CONTAINER" python3 -c "
import os
world = '${WORLD}'
res_dir = '/data/plugins/Residence/Save'
os.makedirs(res_dir, exist_ok=True)
path = os.path.join(res_dir, 'Global.yml')
content = '''Residences:
  integration_test:
    Permissions:
      World: ${WORLD}
    Areas:
      main:
        X1: 26112
        Y1: 64
        Z1: 25600
        X2: 26623
        Y2: 256
        Z2: 26111
'''
with open(path, 'w') as f:
    f.write(content)
print(f'  written {path}')
"

# ── 4. WorldGuard file-fallback fixture ───────────────────────────────────────
# Creates a minimal regions.yml with one cuboid region covering block area
# (26624–27135 × 25600–26111) → chunks (1664–1695 × 1600–1631) → region r.52.50.
# Also seeds r.52.50.mca so it shows as a kept existing region file.
echo "▶ Seeding WorldGuard regions.yml fixture (region r.52.50)..."
docker exec -u minecraft "$CONTAINER" python3 -c "
import os
world = '${WORLD}'

# Seed region file so r.52.50 exists as an existing .mca file
for d in ('region', 'entities'):
    os.makedirs(f'/data/{world}/{d}', exist_ok=True)
    path = f'/data/{world}/{d}/r.52.50.mca'
    if not os.path.exists(path):
        open(path, 'wb').write(b'\x00' * 8192)
        print(f'  created {path}')
    else:
        print(f'  exists  {path}')

# Seed WorldGuard regions.yml
wg_dir = f'/data/plugins/WorldGuard/worlds/{world}'
os.makedirs(wg_dir, exist_ok=True)
regions_path = os.path.join(wg_dir, 'regions.yml')
content = '''regions:
  __global__:
    type: global
    flags: {}
    members:
      uniqueIds: []
    owners:
      uniqueIds: []
  wg_integration_test:
    type: cuboid
    min: {x: 26624, y: 0, z: 25600}
    max: {x: 27135, y: 255, z: 26111}
    priority: 0
    flags: {}
    members:
      uniqueIds: []
    owners:
      uniqueIds: []
'''
with open(regions_path, 'w') as f:
    f.write(content)
print(f'  written {regions_path}')
"

echo "▶ Seed complete."
