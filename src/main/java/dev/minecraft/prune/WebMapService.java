package dev.minecraft.prune;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Pushes keep/prune region markers to BlueMap and/or Dynmap after a scan.
 *
 * <p>Both integrations are entirely reflection-based with no compile-time
 * dependency. All methods fail silently when the target plugin is absent or
 * incompatible; the caller never needs to handle exceptions from this class.
 *
 * <p>BlueMap: targets BlueMap API v2 (BlueMap plugin 3.x+).
 * Dynmap:  targets dynmap 3.x DynmapAPI / MarkerAPI.
 */
final class WebMapService {

    static record MarkerInputs(Set<String> keepNames, Set<String> pruneNames) {}

    /** Marker-set ID used on both BlueMap and Dynmap. */
    private static final String MARKER_SET_ID    = "worldprune";
    private static final String MARKER_SET_LABEL = "WorldPrune";

    /** Blocks per region edge (512). */
    private static final int REGION_BLOCKS = 512;

    // Marker colours
    private static final int KEEP_FILL_ARGB  = 0x2800AA00; // 16 % opacity, green
    private static final int KEEP_LINE_ARGB  = 0xFF00CC00; // solid green
    private static final int PRUNE_FILL_ARGB = 0x28AA0000; // 16 % opacity, red
    private static final int PRUNE_LINE_ARGB = 0xFFCC0000; // solid red

    private final Logger logger;

    WebMapService(Logger logger) {
        this.logger = logger;
    }

    // ── Status ────────────────────────────────────────────────────────────────

    boolean isBlueMapAvailable() {
        return Bukkit.getServer() != null
                && Bukkit.getPluginManager().getPlugin("BlueMap") != null;
    }

    boolean isDynmapAvailable() {
        return Bukkit.getServer() != null
                && Bukkit.getPluginManager().getPlugin("dynmap") != null;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Reads keep / prune region sets from a plan's report directory and pushes
     * markers to every available web-map plugin. Errors are logged at FINE
     * level and never propagate to the caller.
     *
     * @param world     Bukkit world the plan covers
     * @param planDir   top-level plan report directory (world files one level deeper)
     */
    MarkerInputs readMarkerInputs(World world, Path planDir) throws IOException {
        Path worldDir = planDir.resolve(world.getName());
        Set<String> keepNames  = readNames(worldDir, "kept-existing-regions.txt");
        Set<String> pruneNames = readNames(worldDir, "prune-candidate-regions.txt");
        return new MarkerInputs(Set.copyOf(keepNames), Set.copyOf(pruneNames));
    }

    void updateMarkers(World world, Path planDir) {
        try {
            updateMarkers(world, readMarkerInputs(world, planDir));
        } catch (IOException e) {
            logger.fine("[WebMapService] Could not read plan files: " + e.getMessage());
        }
    }

    void updateMarkers(World world, MarkerInputs markerInputs) {
        Set<String> keepNames = markerInputs.keepNames();
        Set<String> pruneNames = markerInputs.pruneNames();

        if (isBlueMapAvailable()) {
            try {
                boolean applied = updateBlueMap(world, keepNames, pruneNames);
                if (applied) {
                    logger.info("[WorldPrune] BlueMap markers updated for " + world.getName()
                            + " (keep=" + keepNames.size() + " prune=" + pruneNames.size() + ")");
                } else {
                    logger.warning("[WorldPrune] BlueMap markers skipped for " + world.getName()
                            + " (API not ready or world not found)");
                }
            } catch (Exception e) {
                Throwable root = rootCause(e);
                logger.warning("[WorldPrune] BlueMap marker update failed for "
                        + world.getName() + ": " + root.getClass().getSimpleName()
                        + ": " + root.getMessage());
            }
        }

        if (isDynmapAvailable()) {
            try {
                boolean applied = updateDynmap(world, keepNames, pruneNames);
                if (applied) {
                    logger.info("[WorldPrune] Dynmap markers updated for " + world.getName()
                            + " (keep=" + keepNames.size() + " prune=" + pruneNames.size() + ")");
                } else {
                    logger.warning("[WorldPrune] Dynmap markers skipped for " + world.getName()
                            + " (API not ready or world not registered)");
                }
            } catch (Exception e) {
                Throwable root = rootCause(e);
                logger.warning("[WorldPrune] Dynmap marker update failed for "
                        + world.getName() + ": " + root.getClass().getSimpleName()
                        + ": " + root.getMessage());
            }
        }
    }

    // ── BlueMap (API v2 / BlueMap plugin 3.x+) ───────────────────────────────

    @SuppressWarnings("unchecked")
    private boolean updateBlueMap(World world, Set<String> keepNames, Set<String> pruneNames)
            throws Exception {

        // BlueMapAPI.getInstance() → Optional<BlueMapAPI>
        Class<?> bmaClass = Class.forName("de.bluecolored.bluemap.api.BlueMapAPI");
        Optional<?> optApi = (Optional<?>) bmaClass.getMethod("getInstance").invoke(null);
        if (!optApi.isPresent()) return false;
        Object api = optApi.get();

        Object blueWorld = resolveBlueMapWorld(api, world);
        if (blueWorld == null) return false;

        Collection<?> maps = resolveBlueMapMaps(blueWorld);
        if (maps.isEmpty()) return false;

        // Build a single MarkerSet containing all keep/prune markers
        Object markerSet = buildBlueMapMarkerSet(keepNames, pruneNames);

        for (Object map : maps) {
            Map<String, Object> markerSets = (Map<String, Object>) map.getClass()
                    .getMethod("getMarkerSets").invoke(map);
            markerSets.put(MARKER_SET_ID, markerSet);
        }
        return true;
    }

    private Object resolveBlueMapWorld(Object api, World world) throws Exception {
        // BlueMap 5.x exposes getWorld(Object)
        try {
            Method getWorldObj = api.getClass().getMethod("getWorld", Object.class);
            Object result = getWorldObj.invoke(api, world.getName());
            if (result instanceof Optional<?> opt && opt.isPresent()) {
                return opt.get();
            }
        } catch (NoSuchMethodException ignored) {
            // Fall through to other lookup variants.
        }

        // Most BlueMap versions support lookup by string world id/name.
        try {
            Method getWorld = api.getClass().getMethod("getWorld", String.class);
            Object result = getWorld.invoke(api, world.getName());
            if (result instanceof Optional<?> opt && opt.isPresent()) {
                return opt.get();
            }
        } catch (NoSuchMethodException ignored) {
            // Fall through to list-based lookup.
        }

        Method getWorlds = findMethod(api.getClass(), "getWorlds");
        if (getWorlds == null) return null;

        Object worldsObj = getWorlds.invoke(api);
        if (!(worldsObj instanceof Iterable<?> worlds)) return null;

        Set<String> candidates = blueMapWorldNameCandidates(world.getName());
        for (Object blueWorld : worlds) {
            if (blueMapWorldMatches(blueWorld, candidates)) {
                return blueWorld;
            }
        }
        return null;
    }

    static Set<String> blueMapWorldNameCandidates(String bukkitWorldName) {
        Set<String> out = new HashSet<>();
        out.add(bukkitWorldName);

        // Common naming differences between Bukkit and BlueMap dimensions.
        if (bukkitWorldName.endsWith("_nether")) {
            String base = bukkitWorldName.substring(0, bukkitWorldName.length() - "_nether".length());
            out.add(base + "/nether");
            out.add(base + "-nether");
            out.add(base + " nether");
        } else if (bukkitWorldName.endsWith("_the_end")) {
            String base = bukkitWorldName.substring(0, bukkitWorldName.length() - "_the_end".length());
            out.add(base + "/the_end");
            out.add(base + "/end");
            out.add(base + "-the_end");
            out.add(base + "-end");
            out.add(base + " the end");
        }
        return out;
    }

    private boolean blueMapWorldMatches(Object blueWorld, Set<String> candidates) {
        for (String methodName : new String[]{"getName", "getId", "getSaveFolder"}) {
            Method m = findMethod(blueWorld.getClass(), methodName);
            if (m == null) continue;
            try {
                Object value = m.invoke(blueWorld);
                if (value == null) continue;

                String full = String.valueOf(value);
                if (matchesAny(full, candidates)) {
                    return true;
                }

                // For path-like values, also compare the final path segment.
                String normalized = full.replace('\\', '/');
                int slash = normalized.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < normalized.length()) {
                    if (matchesAny(normalized.substring(slash + 1), candidates)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
                // Try next candidate accessor.
            }
        }
        return false;
    }

    private boolean matchesAny(String value, Set<String> candidates) {
        for (String candidate : candidates) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private Collection<?> resolveBlueMapMaps(Object blueWorld) throws Exception {
        Method getMaps = findMethod(blueWorld.getClass(), "getMaps");
        if (getMaps == null) return List.of();

        Object mapsObj = getMaps.invoke(blueWorld);
        if (mapsObj instanceof Collection<?> maps) return maps;
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Object buildBlueMapMarkerSet(Set<String> keepNames, Set<String> pruneNames)
            throws Exception {

        Class<?> msClass       = Class.forName("de.bluecolored.bluemap.api.markers.MarkerSet");
        Class<?> smClass       = Class.forName("de.bluecolored.bluemap.api.markers.ShapeMarker");
        Class<?> shapeClass    = Class.forName("de.bluecolored.bluemap.api.math.Shape");
        Class<?> colorClass    = Class.forName("de.bluecolored.bluemap.api.math.Color");

        // MarkerSet.builder().label("WorldPrune").build()
        Object msBuilder = msClass.getMethod("builder").invoke(null);
        msBuilder = msBuilder.getClass().getMethod("label", String.class).invoke(msBuilder, MARKER_SET_LABEL);
        Object markerSet = msBuilder.getClass().getMethod("build").invoke(msBuilder);

        // markerSet.getMarkers() → Map<String, Marker>
        Map<String, Object> markers = (Map<String, Object>) markerSet.getClass()
                .getMethod("getMarkers").invoke(markerSet);

        // Build keep markers (green)
        for (String name : keepNames) {
            int[] rc = parseRegionCoords(name);
            if (rc == null) continue;
            Object marker = buildBlueMapShapeMarker(smClass, shapeClass, colorClass,
                    name, rc, KEEP_FILL_ARGB, KEEP_LINE_ARGB);
            if (marker != null) markers.put("worldprune-keep-" + name, marker);
        }
        // Build prune markers (red)
        for (String name : pruneNames) {
            int[] rc = parseRegionCoords(name);
            if (rc == null) continue;
            Object marker = buildBlueMapShapeMarker(smClass, shapeClass, colorClass,
                    name, rc, PRUNE_FILL_ARGB, PRUNE_LINE_ARGB);
            if (marker != null) markers.put("worldprune-prune-" + name, marker);
        }
        return markerSet;
    }

    private Object buildBlueMapShapeMarker(Class<?> smClass, Class<?> shapeClass,
            Class<?> colorClass, String regionName, int[] rc,
            int fillArgb, int lineArgb) throws Exception {

        // Region block bounds
        double x1 = (double) rc[0] * REGION_BLOCKS;
        double z1 = (double) rc[1] * REGION_BLOCKS;
        double x2 = x1 + REGION_BLOCKS;
        double z2 = z1 + REGION_BLOCKS;

        // Shape.createRect(double x1, double z1, double x2, double z2)
        Object shape = shapeClass.getMethod("createRect",
                double.class, double.class, double.class, double.class)
                .invoke(null, x1, z1, x2, z2);

        // Color from ARGB int: Color(int rgba) — BlueMap Color(int r, int g, int b, int a)
        Object fill = blueMapColor(colorClass, fillArgb);
        Object line = blueMapColor(colorClass, lineArgb);

        // ShapeMarker builder signature differs across BlueMap versions.
        Object smBuilder = smClass.getMethod("builder").invoke(null);
        smBuilder = smBuilder.getClass().getMethod("label", String.class)
                .invoke(smBuilder, regionName);

        // Newer BlueMap (5.x): shape(Shape, y)
        // Older variants may expose shape(Shape, minY, maxY)
        try {
            smBuilder = smBuilder.getClass().getMethod("shape", shapeClass, float.class)
                    .invoke(smBuilder, shape, 64f);
        } catch (NoSuchMethodException oneY) {
            smBuilder = smBuilder.getClass().getMethod("shape", shapeClass, float.class, float.class)
                    .invoke(smBuilder, shape, 0f, 255f);
        }

        // Make markers visible even when terrain occludes the marker plane.
        Method depthTestEnabled = findMethod(smBuilder.getClass(), "depthTestEnabled");
        if (depthTestEnabled != null) {
            try {
                smBuilder = depthTestEnabled.invoke(smBuilder, false);
            } catch (Exception ignored) {
                // No-op; visibility will rely on default depth-test behaviour.
            }
        }

        smBuilder = smBuilder.getClass().getMethod("fillColor", colorClass)
                .invoke(smBuilder, fill);
        smBuilder = smBuilder.getClass().getMethod("lineColor", colorClass)
                .invoke(smBuilder, line);
        return smBuilder.getClass().getMethod("build").invoke(smBuilder);
    }

    /** Creates a BlueMap {@code Color} from a packed ARGB int (0xAARRGGBB). */
    private Object blueMapColor(Class<?> colorClass, int argb) throws Exception {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>>  8) & 0xFF;
        int b =  argb         & 0xFF;

        // Constructor fallbacks across BlueMap API variants.
        for (Class<?>[] sig : Arrays.asList(
                new Class<?>[]{int.class, int.class, int.class, int.class},
            new Class<?>[]{int.class, int.class, int.class, float.class},
                new Class<?>[]{float.class, float.class, float.class, float.class},
                new Class<?>[]{double.class, double.class, double.class, double.class},
                new Class<?>[]{int.class},
                new Class<?>[]{long.class}
        )) {
            try {
                if (sig.length == 4 && sig[0] == int.class && sig[3] == int.class) {
                    return colorClass.getConstructor(sig).newInstance(r, g, b, a);
                }
                if (sig.length == 4 && sig[0] == int.class && sig[3] == float.class) {
                    return colorClass.getConstructor(sig).newInstance(r, g, b, a / 255f);
                }
                if (sig.length == 4 && sig[0] == float.class) {
                    return colorClass.getConstructor(sig)
                            .newInstance(r / 255f, g / 255f, b / 255f, a / 255f);
                }
                if (sig.length == 4 && sig[0] == double.class) {
                    return colorClass.getConstructor(sig)
                            .newInstance(r / 255d, g / 255d, b / 255d, a / 255d);
                }
                if (sig.length == 1 && sig[0] == int.class) {
                    return colorClass.getConstructor(sig).newInstance(argb);
                }
                if (sig.length == 1 && sig[0] == long.class) {
                    return colorClass.getConstructor(sig).newInstance((long) argb & 0xFFFFFFFFL);
                }
            } catch (ReflectiveOperationException ignored) {
                // Keep trying known constructor signatures.
            }
        }

        // Static-factory fallbacks used by some versions.
        for (String methodName : new String[]{"fromRGBA", "fromARGB", "fromColor"}) {
            Method m = findMethod(colorClass, methodName);
            if (m == null) continue;
            Class<?>[] p = m.getParameterTypes();
            try {
                if (p.length == 4 && p[0] == int.class && p[1] == int.class
                        && p[2] == int.class && p[3] == int.class) {
                    return m.invoke(null, r, g, b, a);
                }
                if (p.length == 4 && p[0] == float.class && p[1] == float.class
                        && p[2] == float.class && p[3] == float.class) {
                    return m.invoke(null, r / 255f, g / 255f, b / 255f, a / 255f);
                }
                if (p.length == 1 && p[0] == int.class) {
                    return m.invoke(null, argb);
                }
            } catch (ReflectiveOperationException ignored) {
                // Try the next factory method.
            }
        }

        throw new NoSuchMethodException("No compatible BlueMap Color constructor/factory found");
    }

    // ── Dynmap (3.x DynmapAPI + MarkerAPI) ───────────────────────────────────

    @SuppressWarnings("unchecked")
    private boolean updateDynmap(World world, Set<String> keepNames, Set<String> pruneNames)
            throws Exception {

        Object dynmap = Bukkit.getPluginManager().getPlugin("dynmap");
        if (dynmap == null) return false;

        // dynmap.getMarkerAPI() → MarkerAPI (null if Dynmap not fully initialized)
        Method getMarkerAPI = findMethod(dynmap.getClass(), "getMarkerAPI");
        if (getMarkerAPI == null) return false;
        Object markerAPI;
        try {
            markerAPI = getMarkerAPI.invoke(dynmap);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Dynmap plugin loaded but core not yet initialized (this.core == null)
            return false;
        }
        if (markerAPI == null) return false;

        String setId = MARKER_SET_ID + "." + world.getName();

        // Get existing marker set or create a new one
        Method getSet = findMethod(markerAPI.getClass(), "getMarkerSet");
        Object markerSet = getSet != null ? getSet.invoke(markerAPI, setId) : null;

        if (markerSet == null) {
            // createMarkerSet(id, label, iconIds, persistent)
            Method createSet = markerAPI.getClass().getMethod(
                    "createMarkerSet", String.class, String.class, Set.class, boolean.class);
            markerSet = createSet.invoke(markerAPI, setId,
                    MARKER_SET_LABEL + ": " + world.getName(), null, false);
        }
        if (markerSet == null) return false;

        // Clear existing area markers
        Method getAreaMarkers = findMethod(markerSet.getClass(), "getAreaMarkers");
        if (getAreaMarkers != null) {
            Set<?> existing = (Set<?>) getAreaMarkers.invoke(markerSet);
            if (existing != null) {
                Method deleteMarker = null;
                for (Object m : existing) {
                    if (deleteMarker == null) {
                        deleteMarker = findMethod(m.getClass(), "deleteMarker");
                    }
                    if (deleteMarker != null) deleteMarker.invoke(m);
                }
            }
        }

        // Signature: createAreaMarker(id, label, markup, world, x[], z[], persistent)
        Method createArea = markerSet.getClass().getMethod("createAreaMarker",
                String.class, String.class, boolean.class, String.class,
                double[].class, double[].class, boolean.class);
        try {
            createArea.setAccessible(true);
        } catch (Exception ignored) {
            // Best effort for non-public implementation classes.
        }

        // setFillStyle(opacity, color) and setLineStyle(weight, opacity, color)
        String worldName = world.getName();

        int keepApplied = addDynmapRegionMarkers(markerSet, createArea, keepNames,  "keep",  worldName, 0x00AA00);
        int pruneApplied = addDynmapRegionMarkers(markerSet, createArea, pruneNames, "prune", worldName, 0xAA0000);
        return keepApplied + pruneApplied >= 0;
    }

    private int addDynmapRegionMarkers(Object markerSet, Method createArea,
            Set<String> regionNames, String prefix, String worldName,
            int lineColor) throws Exception {

        int applied = 0;

        for (String name : regionNames) {
            int[] rc = parseRegionCoords(name);
            if (rc == null) continue;

            double minX = (double) rc[0] * REGION_BLOCKS;
            double minZ = (double) rc[1] * REGION_BLOCKS;
            double maxX = minX + REGION_BLOCKS;
            double maxZ = minZ + REGION_BLOCKS;

            double[] xs = {minX, maxX, maxX, minX};
            double[] zs = {minZ, minZ, maxZ, maxZ};

            Object marker;
            try {
                marker = createArea.invoke(markerSet,
                        "worldprune-" + prefix + "-" + name,
                        name, false, worldName, xs, zs, false);
            } catch (java.lang.reflect.InvocationTargetException ignored) {
                // World not registered in Dynmap yet (e.g. server just started); skip marker
                continue;
            }
            if (marker == null) continue;
            applied++;

            // setFillStyle(double opacity, int color)
            try {
                Method setFillStyle = marker.getClass().getMethod("setFillStyle", double.class, int.class);
                try {
                    setFillStyle.setAccessible(true);
                } catch (Exception ignored) {
                    // Best effort for non-public implementation classes.
                }
                setFillStyle.invoke(marker, 0.16, lineColor);
            } catch (Exception ignored) {}

            // setLineStyle(int weight, double opacity, int color)
            try {
                Method setLineStyle = marker.getClass().getMethod("setLineStyle", int.class, double.class, int.class);
                try {
                    setLineStyle.setAccessible(true);
                } catch (Exception ignored) {
                    // Best effort for non-public implementation classes.
                }
                setLineStyle.invoke(marker, 1, 0.8, lineColor);
            } catch (Exception ignored) {}
        }

        return applied;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns the root (deepest) cause of an exception. */
    private static Throwable rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause;
    }

    /** Finds a public method by name on a class or any of its super-types. */
    private static Method findMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name)) {
                try {
                    m.setAccessible(true);
                } catch (Exception ignored) {
                    // Best effort for non-public implementation classes.
                }
                return m;
            }
        }
        return null;
    }

    /** Parses region coords from "r.rx.rz.mca"; returns {rx, rz} or null. */
    static int[] parseRegionCoords(String fileName) {
        if (fileName == null) return null;
        // Strip .mca suffix
        String name = fileName.endsWith(".mca") ? fileName.substring(0, fileName.length() - 4) : fileName;
        String[] parts = name.split("\\.");
        if (parts.length != 3 || !"r".equals(parts[0])) return null;
        try {
            return new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Set<String> readNames(Path dir, String... fileNames) throws IOException {
        Set<String> out = new HashSet<>();
        for (String fileName : fileNames) {
            Path f = dir.resolve(fileName);
            if (Files.isRegularFile(f)) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) out.add(trimmed);
                }
                return out; // use the first file that exists
            }
        }
        return out;
    }
}
