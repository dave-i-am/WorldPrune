package dev.minecraft.prune;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class ClaimBoundsProvider {
    private static final Pattern INT_PATTERN = Pattern.compile("-?\\d+");

    record ClaimLoadResult(List<Rect> claims, String source) {}

    /** Convenience overload — only GriefPrevention paths; Towny/Residence/WorldGuard not queried. */
    ClaimLoadResult load(World world, Path gpClaimDir) {
        return load(world, gpClaimDir, null, null, null);
    }

    /** Convenience overload — GP/Towny/Residence without WorldGuard. */
    ClaimLoadResult load(World world, Path gpClaimDir, Path townyDir, Path residenceFile) {
        return load(world, gpClaimDir, townyDir, residenceFile, null);
    }

    /**
     * Loads claims from all available sources (GriefPrevention, Towny, Residence,
     * WorldGuard) and merges them. Each plugin's API is tried first; if unavailable or
     * empty, the corresponding file path is used as a fallback. Sources that contribute
     * zero claims are omitted from the returned source string.
     *
     * @param gpClaimDir     GriefPrevention claim-files directory (may be null)
     * @param townyDir       Towny townblocks directory (may be null)
     * @param residenceFile  Residence Global.yml save file path (may be null)
     * @param wgWorldsDir    WorldGuard worlds directory (may be null — API is tried
     *                       first; file fallback reads {@code <wgWorldsDir>/<world>/regions.yml})
     */
    ClaimLoadResult load(World world, Path gpClaimDir, Path townyDir, Path residenceFile, Path wgWorldsDir) {
        List<Rect> allClaims = new ArrayList<>();
        List<String> sources = new ArrayList<>();

        // ── GriefPrevention ──────────────────────────────────────────────────
        boolean gpApiSucceeded = false;
        try {
            List<Rect> apiClaims = loadFromGriefPreventionApi(world);
            if (!apiClaims.isEmpty()) {
                allClaims.addAll(apiClaims);
                sources.add("griefprevention-api");
                gpApiSucceeded = true;
            }
        } catch (Exception ignored) {}

        if (!gpApiSucceeded && gpClaimDir != null) {
            try {
                List<Rect> fileClaims = loadFromClaimFiles(world, gpClaimDir);
                if (!fileClaims.isEmpty()) {
                    allClaims.addAll(fileClaims);
                    sources.add("claim-files");
                }
            } catch (Exception ignored) {}
        }

        // ── Towny ────────────────────────────────────────────────────────────
        boolean townyApiSucceeded = false;
        try {
            List<Rect> townyRects = loadFromTownyApi(world);
            if (!townyRects.isEmpty()) {
                allClaims.addAll(townyRects);
                sources.add("towny-api");
                townyApiSucceeded = true;
            }
        } catch (Exception ignored) {}

        if (!townyApiSucceeded && townyDir != null) {
            try {
                List<Rect> townyRects = loadFromTownyFiles(world, townyDir);
                if (!townyRects.isEmpty()) {
                    allClaims.addAll(townyRects);
                    sources.add("towny-files");
                }
            } catch (Exception ignored) {}
        }

        // ── Residence ────────────────────────────────────────────────────────
        boolean residenceApiSucceeded = false;
        try {
            List<Rect> residenceRects = loadFromResidenceApi(world);
            if (!residenceRects.isEmpty()) {
                allClaims.addAll(residenceRects);
                sources.add("residence-api");
                residenceApiSucceeded = true;
            }
        } catch (Exception ignored) {}

        if (!residenceApiSucceeded && residenceFile != null) {
            try {
                List<Rect> residenceRects = loadFromResidenceFile(world, residenceFile);
                if (!residenceRects.isEmpty()) {
                    allClaims.addAll(residenceRects);
                    sources.add("residence-file");
                }
            } catch (Exception ignored) {}
        }

        // ── WorldGuard ───────────────────────────────────────────────────────
        boolean wgApiSucceeded = false;
        try {
            List<Rect> wgRects = loadFromWorldGuardApi(world);
            if (!wgRects.isEmpty()) {
                allClaims.addAll(wgRects);
                sources.add("worldguard-api");
                wgApiSucceeded = true;
            }
        } catch (Exception ignored) {}

        if (!wgApiSucceeded && wgWorldsDir != null) {
            try {
                List<Rect> wgRects = loadFromWorldGuardFile(world, wgWorldsDir);
                if (!wgRects.isEmpty()) {
                    allClaims.addAll(wgRects);
                    sources.add("worldguard-files");
                }
            } catch (Exception ignored) {}
        }

        String source = sources.isEmpty() ? "none" : String.join("+", sources);
        return new ClaimLoadResult(allClaims, source);
    }

    @SuppressWarnings("unchecked")
    private List<Rect> loadFromGriefPreventionApi(World world) throws Exception {
        if (Bukkit.getPluginManager().getPlugin("GriefPrevention") == null) {
            return List.of();
        }

        Class<?> gpClass = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
        Field instanceField = gpClass.getDeclaredField("instance");
        Object gpInstance = instanceField.get(null);
        if (gpInstance == null) return List.of();

        Field dataStoreField = gpClass.getField("dataStore");
        Object dataStore = dataStoreField.get(gpInstance);
        if (dataStore == null) return List.of();

        Method getClaims = dataStore.getClass().getMethod("getClaims");
        List<Object> claims = (List<Object>) getClaims.invoke(dataStore);

        List<Rect> out = new ArrayList<>();
        for (Object claim : claims) {
            Method getLesser = claim.getClass().getMethod("getLesserBoundaryCorner");
            Method getGreater = claim.getClass().getMethod("getGreaterBoundaryCorner");
            Location lesser = (Location) getLesser.invoke(claim);
            Location greater = (Location) getGreater.invoke(claim);
            if (lesser == null || greater == null) continue;
            if (!world.getUID().equals(lesser.getWorld().getUID())) continue;
            out.add(new Rect(lesser.getBlockX(), lesser.getBlockZ(), greater.getBlockX(), greater.getBlockZ()));
        }
        return out;
    }

    private List<Rect> loadFromClaimFiles(World world, Path claimDir) throws IOException {
        if (!Files.isDirectory(claimDir)) return List.of();

        String worldName = world.getName().toLowerCase(Locale.ROOT);
        List<Rect> out = new ArrayList<>();

        try (Stream<Path> stream = Files.list(claimDir)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();
                    if (lines.size() < 2) return;

                    Optional<PointXZ> c1 = parseCorner(lines.get(0));
                    Optional<PointXZ> c2 = parseCorner(lines.get(1));
                    if (c1.isEmpty() || c2.isEmpty()) return;

                    // Optional world filter if file contains explicit world identifier.
                    String whole = String.join("\n", lines).toLowerCase(Locale.ROOT);
                    if (whole.contains("world:") || whole.contains("level:") || whole.contains("dimension:")) {
                        if (!whole.contains(worldName)) return;
                    }

                    out.add(new Rect(c1.get().x(), c1.get().z(), c2.get().x(), c2.get().z()));
                } catch (Exception ignored) {
                }
            });
        }

        return out;
    }

    // ─────────────────────────── Towny ───────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Rect> loadFromTownyApi(World world) throws Exception {
        if (Bukkit.getPluginManager().getPlugin("Towny") == null) return List.of();

        Class<?> universeClass = Class.forName("com.palmergames.bukkit.towny.TownyUniverse");
        Method getInstance = universeClass.getMethod("getInstance");
        Object universe = getInstance.invoke(null);

        Method getDataSource = universeClass.getMethod("getDataSource");
        Object dataSource = getDataSource.invoke(universe);

        Method getAllTownBlocks = dataSource.getClass().getMethod("getAllTownBlocks");
        Collection<Object> blocks = (Collection<Object>) getAllTownBlocks.invoke(dataSource);

        List<Rect> out = new ArrayList<>();
        for (Object block : blocks) {
            try {
                String blockWorld;
                try {
                    Method getWorldCoord = block.getClass().getMethod("getWorldCoord");
                    Object wc = getWorldCoord.invoke(block);
                    Method getWorldName = wc.getClass().getMethod("getWorldName");
                    blockWorld = (String) getWorldName.invoke(wc);
                } catch (Exception e) {
                    Method getWorld = block.getClass().getMethod("getWorld");
                    Object tw = getWorld.invoke(block);
                    Method getName = tw.getClass().getMethod("getName");
                    blockWorld = (String) getName.invoke(tw);
                }
                if (!blockWorld.equalsIgnoreCase(world.getName())) continue;
                Method getX = block.getClass().getMethod("getX");
                Method getZ = block.getClass().getMethod("getZ");
                int cx = (int) getX.invoke(block);
                int cz = (int) getZ.invoke(block);
                out.add(new Rect(cx * 16, cz * 16, cx * 16 + 15, cz * 16 + 15));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private List<Rect> loadFromTownyFiles(World world, Path townyDir) throws IOException {
        if (!Files.isDirectory(townyDir)) return List.of();

        String prefix = world.getName().toLowerCase(Locale.ROOT) + "_";
        List<Rect> out = new ArrayList<>();

        try (Stream<Path> stream = Files.list(townyDir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.startsWith(prefix) || !name.endsWith(".data")) return;
                String coords = name.substring(prefix.length(), name.length() - 5);
                String[] parts = coords.split("_");
                if (parts.length != 2) return;
                try {
                    int cx = Integer.parseInt(parts[0]);
                    int cz = Integer.parseInt(parts[1]);
                    out.add(new Rect(cx * 16, cz * 16, cx * 16 + 15, cz * 16 + 15));
                } catch (NumberFormatException ignored) {}
            });
        }
        return out;
    }

    // ─────────────────────────── Residence ───────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Rect> loadFromResidenceApi(World world) throws Exception {
        if (Bukkit.getPluginManager().getPlugin("Residence") == null) return List.of();

        Class<?> resClass = Class.forName("com.bekvon.bukkit.residence.Residence");
        Method getInstance = resClass.getMethod("getInstance");
        Object res = getInstance.invoke(null);

        Method getResidenceManager = resClass.getMethod("getResidenceManager");
        Object manager = getResidenceManager.invoke(res);

        Method getResidences = manager.getClass().getMethod("getResidences");
        Map<String, Object> residences = (Map<String, Object>) getResidences.invoke(manager);

        List<Rect> out = new ArrayList<>();
        for (Object residence : residences.values()) {
            try {
                Method getMainArea = residence.getClass().getMethod("getMainArea");
                Object area = getMainArea.invoke(residence);
                if (area == null) continue;
                Method getHighLoc = area.getClass().getMethod("getHighLoc");
                Method getLowLoc  = area.getClass().getMethod("getLowLoc");
                Location high = (Location) getHighLoc.invoke(area);
                Location low  = (Location) getLowLoc.invoke(area);
                if (high == null || low == null) continue;
                if (!world.getUID().equals(high.getWorld().getUID())) continue;
                out.add(new Rect(low.getBlockX(), low.getBlockZ(), high.getBlockX(), high.getBlockZ()));
            } catch (Exception ignored) {}
        }
        return out;
    }

    /**
     * Parses Residence's {@code Global.yml} save file.
     * Looks for {@code World:}, {@code X1:}, {@code Z1:}, {@code X2:}, {@code Z2:} key-value
     * lines and groups them into Rects. Works for main areas and sub-zones alike.
     */
    private List<Rect> loadFromResidenceFile(World world, Path residenceFile) throws IOException {
        if (!Files.isRegularFile(residenceFile)) return List.of();

        String worldLower = world.getName().toLowerCase(Locale.ROOT);
        List<Rect> out = new ArrayList<>();
        String currentWorld = null;
        Integer x1 = null, z1 = null, x2 = null, z2 = null;

        for (String line : Files.readAllLines(residenceFile, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("World:")) {
                // Flush any incomplete coordinate group from the previous area block
                x1 = z1 = x2 = z2 = null;
                currentWorld = trimmed.substring("World:".length()).trim().toLowerCase(Locale.ROOT);
                continue;
            }

            if (trimmed.startsWith("X1:"))      { x1 = parseYamlInt(trimmed); }
            else if (trimmed.startsWith("Z1:")) { z1 = parseYamlInt(trimmed); }
            else if (trimmed.startsWith("X2:")) { x2 = parseYamlInt(trimmed); }
            else if (trimmed.startsWith("Z2:")) { z2 = parseYamlInt(trimmed); }

            if (x1 != null && z1 != null && x2 != null && z2 != null) {
                if (currentWorld == null || currentWorld.equals(worldLower)) {
                    out.add(new Rect(x1, z1, x2, z2));
                }
                x1 = z1 = x2 = z2 = null;
            }
        }
        return out;
    }

    private static int parseYamlInt(String line) {
        int colon = line.indexOf(':');
        return Integer.parseInt(line.substring(colon + 1).trim());
    }

    // ─────────────────────────── WorldGuard ──────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Rect> loadFromWorldGuardApi(World world) throws Exception {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) return List.of();

        Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
        Object wg = wgClass.getMethod("getInstance").invoke(null);
        Object platform = wg.getClass().getMethod("getPlatform").invoke(wg);
        Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);

        // BukkitAdapter.adapt(org.bukkit.World) → WorldEdit World
        Class<?> adapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
        Object weWorld = adapterClass.getMethod("adapt", World.class).invoke(null, world);

        // RegionContainer.get(com.sk89q.worldedit.world.World) → RegionManager
        Class<?> weWorldClass = Class.forName("com.sk89q.worldedit.world.World");
        Object rm = container.getClass().getMethod("get", weWorldClass).invoke(container, weWorld);
        if (rm == null) return List.of();

        Map<String, Object> regions = (Map<String, Object>) rm.getClass().getMethod("getRegions").invoke(rm);

        List<Rect> out = new ArrayList<>();
        for (Map.Entry<String, Object> entry : regions.entrySet()) {
            if ("__global__".equals(entry.getKey())) continue;
            Object region = entry.getValue();
            try {
                Object min = region.getClass().getMethod("getMinimumPoint").invoke(region);
                Object max = region.getClass().getMethod("getMaximumPoint").invoke(region);
                int minX = blockVecCoord(min, "X");
                int minZ = blockVecCoord(min, "Z");
                int maxX = blockVecCoord(max, "X");
                int maxZ = blockVecCoord(max, "Z");
                out.add(new Rect(minX, minZ, maxX, maxZ));
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** Reads the coordinate from a WorldEdit {@code BlockVector3} via reflection.
     *  Tries {@code getX()/getZ()} first (WE 7.x), then {@code x()/z()} (WE 7.3+). */
    private static int blockVecCoord(Object blockVec, String axis) throws Exception {
        try {
            return (Integer) blockVec.getClass().getMethod("get" + axis).invoke(blockVec);
        } catch (NoSuchMethodException e) {
            return (Integer) blockVec.getClass().getMethod(axis.toLowerCase(Locale.ROOT)).invoke(blockVec);
        }
    }

    /**
     * Parses WorldGuard's {@code regions.yml} file for the given world.
     * Handles both inline ({@code min: {x: 1, y: 0, z: 2}}) and block-format
     * min/max coordinate sections. Skips the {@code __global__} region.
     */
    private List<Rect> loadFromWorldGuardFile(World world, Path wgWorldsDir) throws IOException {
        Path regionsFile = wgWorldsDir.resolve(world.getName()).resolve("regions.yml");
        if (!Files.isRegularFile(regionsFile)) return List.of();

        List<Rect> out = new ArrayList<>();

        // Inline pattern: "min: {x: 100, y: 0, z: 200}" (any key order)
        Pattern inlineMinMax = Pattern.compile("(min|max):\\s*\\{(.+)\\}");
        Pattern xPat = Pattern.compile("x:\\s*(-?\\d+)");
        Pattern zPat = Pattern.compile("z:\\s*(-?\\d+)");

        // 0=other, 1=inside min block, 2=inside max block
        int section = 0;
        Integer minX = null, minZ = null, maxX = null, maxZ = null;
        boolean skipping = false; // true while inside __global__ region

        for (String raw : Files.readAllLines(regionsFile, StandardCharsets.UTF_8)) {
            String t = raw.trim();
            if (t.isEmpty()) continue;

            // Detect __global__ region header (2-space-indented key)
            if (t.equals("__global__:")) {
                skipping = true;
                section = 0;
                minX = minZ = maxX = maxZ = null;
                continue;
            }
            // Any other 2-space-indented key ends __global__ skip
            if (skipping && raw.length() > 2 && raw.charAt(0) == ' ' && raw.charAt(1) == ' '
                    && (raw.charAt(2) != ' ') && t.endsWith(":")) {
                skipping = false;
            }
            if (skipping) continue;

            // ── Inline format: min: {x: 100, y: 0, z: 200} ──────────────────
            Matcher inline = inlineMinMax.matcher(t);
            if (inline.find()) {
                String inner = inline.group(2);
                Matcher mx = xPat.matcher(inner);
                Matcher mz = zPat.matcher(inner);
                if (mx.find() && mz.find()) {
                    int x = Integer.parseInt(mx.group(1));
                    int z = Integer.parseInt(mz.group(1));
                    if ("min".equals(inline.group(1))) { minX = x; minZ = z; }
                    else                               { maxX = x; maxZ = z; }
                    section = 0;
                    if (minX != null && minZ != null && maxX != null && maxZ != null) {
                        out.add(new Rect(minX, minZ, maxX, maxZ));
                        minX = minZ = maxX = maxZ = null;
                    }
                }
                continue;
            }

            // ── Exit current min/max block on any non-x/y/z key ──────────────
            if (section != 0 && !t.startsWith("x:") && !t.startsWith("y:") && !t.startsWith("z:")) {
                section = 0;
            }

            // ── Section headers (block format) ────────────────────────────────
            if (t.startsWith("min:")) { section = 1; continue; }
            if (t.startsWith("max:")) { section = 2; continue; }

            // ── Coordinate values within a section ───────────────────────────
            if (section == 1) {
                if (t.startsWith("x:"))      { minX = parseYamlInt(t); }
                else if (t.startsWith("z:")) { minZ = parseYamlInt(t); }
            } else if (section == 2) {
                if (t.startsWith("x:"))      { maxX = parseYamlInt(t); }
                else if (t.startsWith("z:")) { maxZ = parseYamlInt(t); }
            }

            if (minX != null && minZ != null && maxX != null && maxZ != null) {
                out.add(new Rect(minX, minZ, maxX, maxZ));
                minX = minZ = maxX = maxZ = null;
                section = 0;
            }
        }
        // Flush any trailing complete rect
        if (minX != null && minZ != null && maxX != null && maxZ != null) {
            out.add(new Rect(minX, minZ, maxX, maxZ));
        }
        return out;
    }

    private Optional<PointXZ> parseCorner(String line) {
        Matcher m = INT_PATTERN.matcher(line);
        List<Integer> values = new ArrayList<>();
        while (m.find()) {
            values.add(Integer.parseInt(m.group()));
        }

        if (values.size() >= 3) {
            return Optional.of(new PointXZ(values.get(0), values.get(2)));
        }
        if (values.size() >= 2) {
            return Optional.of(new PointXZ(values.get(0), values.get(1)));
        }
        return Optional.empty();
    }

    private record PointXZ(int x, int z) {}
}
