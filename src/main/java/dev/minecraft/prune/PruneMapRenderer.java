package dev.minecraft.prune;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a keep/prune region grid onto a 128×128 Minecraft map canvas.
 *
 * Colors:
 *   green  (#22c55e) — exists on disk, kept
 *   red    (#ef4444) — exists on disk, prune candidate
 *   blue   (#3b82f6) — keep zone from claims/margin, no file yet
 *
 * Bottom 10 rows reserved for a coloured legend bar (■■■ swatches).
 */
final class PruneMapRenderer extends MapRenderer {

    static final int S_KEEP  = 0;
    static final int S_PRUNE = 1;
    static final int S_ZONE  = 2;

    // Nearest map-palette approximations for our brand colours
    @SuppressWarnings("deprecation")
    private static final byte COL_BG    = MapPalette.matchColor(new Color( 15,  23,  42));
    @SuppressWarnings("deprecation")
    private static final byte COL_KEEP  = MapPalette.matchColor(new Color( 34, 197,  94));
    @SuppressWarnings("deprecation")
    private static final byte COL_PRUNE = MapPalette.matchColor(new Color(220,  38,  38));
    @SuppressWarnings("deprecation")
    private static final byte COL_ZONE  = MapPalette.matchColor(new Color( 96, 165, 250));

    private final List<int[]> regions; // each element: {rx, rz, status}
    private boolean rendered = false;

    /**
     * @param regions list of {rx, rz, status} — status is one of S_KEEP / S_PRUNE / S_ZONE
     */
    PruneMapRenderer(List<int[]> regions) {
        super(false); // not context-sensitive — same pixels for every player
        this.regions = regions;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (rendered) return;
        rendered = true;

        // Background
        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 128; y++) {
                canvas.setPixel(x, y, COL_BG);
            }
        }

        if (regions.isEmpty()) return;

        // ── Bounds ──────────────────────────────────────────────────────────
        int minRx = Integer.MAX_VALUE, maxRx = Integer.MIN_VALUE;
        int minRz = Integer.MAX_VALUE, maxRz = Integer.MIN_VALUE;
        for (int[] r : regions) {
            if (r[0] < minRx) minRx = r[0];
            if (r[0] > maxRx) maxRx = r[0];
            if (r[1] < minRz) minRz = r[1];
            if (r[1] > maxRz) maxRz = r[1];
        }
        int spanX = maxRx - minRx + 1;
        int spanZ = maxRz - minRz + 1;

        // Reserve bottom 10px for swatch legend (y = 118..127)
        int drawH = 118;
        int cellPx = Math.max(1, Math.min(drawH / Math.max(spanX, spanZ), 12));

        int gridW = spanX * cellPx;
        int gridH = spanZ * cellPx;
        int offX  = (128 - gridW) / 2;
        int offY  = (drawH - gridH) / 2;

        // Fast lookup: packed long key → status
        Map<Long, Integer> lookup = new HashMap<>(regions.size() * 2);
        for (int[] r : regions) {
            lookup.put(pack(r[0], r[1]), r[2]);
        }

        // ── Draw cells ──────────────────────────────────────────────────────
        for (int rz = minRz; rz <= maxRz; rz++) {
            for (int rx = minRx; rx <= maxRx; rx++) {
                Integer status = lookup.get(pack(rx, rz));
                if (status == null) continue;

                byte col = switch (status) {
                    case S_KEEP  -> COL_KEEP;
                    case S_PRUNE -> COL_PRUNE;
                    default      -> COL_ZONE;
                };

                int px = offX + (rx - minRx) * cellPx;
                int pz = offY + (rz - minRz) * cellPx;

                for (int dx = 0; dx < cellPx; dx++) {
                    for (int dz = 0; dz < cellPx; dz++) {
                        canvas.setPixel(clamp(px + dx), clamp(pz + dz), col);
                    }
                }
            }
        }

        // ── Legend swatches ─────────────────────────────────────────────────
        // Row y=118..127, three 8×8 squares: keep | prune | zone
        fillRect(canvas,  4, 119, 8, 8, COL_KEEP);
        fillRect(canvas, 16, 119, 8, 8, COL_PRUNE);
        fillRect(canvas, 28, 119, 8, 8, COL_ZONE);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static long pack(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(127, v));
    }

    private static void fillRect(MapCanvas c, int x, int y, int w, int h, byte col) {
        for (int dx = 0; dx < w; dx++) {
            for (int dy = 0; dy < h; dy++) {
                int px = x + dx, py = y + dy;
                if (px >= 0 && px < 128 && py >= 0 && py < 128) {
                    c.setPixel(px, py, col);
                }
            }
        }
    }
}
