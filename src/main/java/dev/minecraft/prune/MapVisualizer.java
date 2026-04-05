package dev.minecraft.prune;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Creates a {@link Material#FILLED_MAP} ItemStack rendered by {@link PruneMapRenderer}.
 *
 * <p>Reads the flat-text region files written by {@link PlanService} to build three
 * coloured region sets (keep / prune / zone) and paints them onto a fresh
 * {@link MapView}.
 */
final class MapVisualizer {

    private MapVisualizer() {}

    /**
     * Build and hand the player a prune-map item for the given plan.
     *
     * @param player        the recipient (used as fallback world if meta world is unloaded)
     * @param meta          plan metadata
     * @param planReportDir the plan's top-level report dir, e.g. {@code <data>/reports/plan-combined-…}
     *                      (world files are expected one level deeper, under {@code <worldName>/})
     * @return a {@link Material#FILLED_MAP} ItemStack ready to add to the player's inventory
     */
    static ItemStack createMap(Player player, PlanStore.PlanMetadata meta, Path planReportDir)
            throws IOException {

        Path worldDir = planReportDir.resolve(meta.worldName);

        // ── 1. Load region sets ──────────────────────────────────────────────
        Set<String> keptNames      = readNames(worldDir, "kept-existing-regions.txt");
        Set<String> pruneNames     = readNames(worldDir, "prune-candidate-regions.txt");
        // Keep zone = claim/heuristic regions that have no file on disk yet
        Set<String> keepRegionFile = readNames(worldDir,
                "keep-regions-combined.txt",
                "keep-regions-from-claims-and-manual.txt");

        Set<String> existing = new HashSet<>(keptNames);
        existing.addAll(pruneNames);
        keepRegionFile.removeAll(existing); // now: zone-only names

        List<int[]> regions = new ArrayList<>(keptNames.size() + pruneNames.size() + keepRegionFile.size());

        for (String name : keptNames)      addRegion(regions, name, PruneMapRenderer.S_KEEP);
        for (String name : pruneNames)     addRegion(regions, name, PruneMapRenderer.S_PRUNE);
        for (String name : keepRegionFile) addRegion(regions, name, PruneMapRenderer.S_ZONE);

        // ── 2. Create MapView ────────────────────────────────────────────────
        World world = Bukkit.getWorld(meta.worldName);
        if (world == null) world = player.getWorld(); // graceful fallback

        @SuppressWarnings("deprecation")
        MapView view = Bukkit.createMap(world);
        view.getRenderers().forEach(view::removeRenderer);
        view.addRenderer(new PruneMapRenderer(regions));
        view.setUnlimitedTracking(false);
        view.setTrackingPosition(false);

        // ── 3. Build ItemStack ───────────────────────────────────────────────
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta mapMeta = (MapMeta) item.getItemMeta();
        mapMeta.setMapView(view);
        mapMeta.setDisplayName("§ePrune Map §7─ §f" + meta.planId);
        mapMeta.setLore(List.of(
                "§7World: §f" + meta.worldName,
                "§a■ §7keep  §c■ §7prune  §9■ §7zone",
                "§7Keep: §a" + meta.keepCount
                        + "  §7Prune: §c" + meta.pruneCount
                        + "  §7(§f~" + String.format(Locale.ROOT, "%.2f", meta.reclaimableGiB) + " GiB§7)"
        ));
        item.setItemMeta(mapMeta);
        return item;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void addRegion(List<int[]> out, String name, int status) {
        int[] rc = parseRegionName(name);
        if (rc != null) out.add(new int[]{rc[0], rc[1], status});
    }

    /** Parse "r.X.Z.mca" → [x, z], or null if unparseable. */
    static int[] parseRegionName(String name) {
        if (name == null || !name.endsWith(".mca")) return null;
        String[] parts = name.split("\\.");
        if (parts.length != 4 || !"r".equals(parts[0])) return null;
        try {
            return new int[]{ Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Read a file of region names (one per line, blank lines ignored).
     * Returns an empty set if none of the provided filenames exist.
     */
    private static Set<String> readNames(Path dir, String... fileNames) throws IOException {
        for (String fileName : fileNames) {
            Path p = dir.resolve(fileName);
            if (Files.isRegularFile(p)) {
                Set<String> out = new HashSet<>();
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) out.add(trimmed);
                }
                return out;
            }
        }
        return new HashSet<>();
    }
}
