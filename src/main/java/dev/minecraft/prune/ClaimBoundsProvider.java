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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class ClaimBoundsProvider {
    private static final Pattern INT_PATTERN = Pattern.compile("-?\\d+");

    record ClaimLoadResult(List<Rect> claims, String source) {}

    ClaimLoadResult load(World world, Path fallbackClaimDir) {
        try {
            List<Rect> apiClaims = loadFromGriefPreventionApi(world);
            if (!apiClaims.isEmpty()) {
                return new ClaimLoadResult(apiClaims, "griefprevention-api");
            }
        } catch (Exception ignored) {
            // fall through to file parsing
        }

        try {
            List<Rect> fileClaims = loadFromClaimFiles(world, fallbackClaimDir);
            return new ClaimLoadResult(fileClaims, "claim-files");
        } catch (Exception e) {
            return new ClaimLoadResult(List.of(), "none");
        }
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
