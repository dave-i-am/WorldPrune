#!/usr/bin/env bash
# integration/seed.sh — Creates dummy far-from-spawn .mca files in the test world so
# that WorldPrune always has at least one prune candidate to work with,
# regardless of how mature the server world is.
#
# Safe to run repeatedly (idempotent — only creates files that don't exist).
#
# Environment:
#   MINECRAFT_CONTAINER  (default: paper-test-server)
#   MINECRAFT_WORLD      (default: world)

set -euo pipefail

CONTAINER="${MINECRAFT_CONTAINER:-paper-test-server}"
WORLD="${MINECRAFT_WORLD:-world}"

echo "▶ Seeding world '$WORLD' with dummy far-from-spawn region files..."

# r.100.100.mca  → blocks [51200..51711] × [51200..51711]
# r.101.100.mca  → blocks [51712..52223] × [51200..51711]
# Both are blank (8192 zero bytes = valid MC region header with no chunks).
# No GriefPrevention claims will cover coords this far from spawn (51 km away),
# and no entity signals → both will be prune candidates.
docker exec "$CONTAINER" python3 -c "
import os
zeros = b'\\x00' * 8192
world = '${WORLD}'
files = ['r.100.100.mca', 'r.101.100.mca']
for d in ('region', 'entities'):
    os.makedirs(f'/data/{world}/{d}', exist_ok=True)
    for f in files:
        path = f'/data/{world}/{d}/{f}'
        if not os.path.exists(path):
            open(path, 'wb').write(zeros)
            print(f'  created {path}')
        else:
            print(f'  exists  {path}')
"
echo "    Done."
