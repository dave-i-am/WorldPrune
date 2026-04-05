package dev.minecraft.prune;

import org.bukkit.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RestoreService {
    private static final Pattern MOVE_LINE = Pattern.compile("\"([^\"]+)\"");

    private final WorldPrunePlugin plugin;

    public RestoreService(WorldPrunePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Restores files from quarantine back to their original world locations.
     *
     * @param world   the world to restore
     * @param applyId specific apply ID to restore, or null to restore the latest
     */
    public void restore(World world, String applyId, Consumer<String> progress) throws IOException {
        Path worldFolder = world.getWorldFolder().toPath();
        Path quarantineRoot = worldFolder.resolve("quarantine");

        Path applyDir = applyId != null
                ? quarantineRoot.resolve(applyId)
                : findLatestApplyDir(quarantineRoot);

        if (applyDir == null || !Files.isDirectory(applyDir)) {
            throw new IOException(applyId != null
                    ? "Quarantine not found: " + applyId
                    : "No quarantine directory found for world '" + world.getName() + "'");
        }

        Path manifestFile = applyDir.resolve("apply-manifest.json");
        if (!Files.isRegularFile(manifestFile)) {
            throw new IOException("apply-manifest.json missing in " + applyDir
                    + " — cannot restore safely.");
        }

        List<String> movedPaths = readManifestMoves(manifestFile);
        if (movedPaths.isEmpty()) {
            progress.accept("§eManifest has no entries — nothing to restore.");
            return;
        }

        int restored = 0, skipped = 0;
        for (String relativePath : movedPaths) {
            Path src = applyDir.resolve(relativePath);
            Path dest = worldFolder.resolve(relativePath);

            if (!Files.isRegularFile(src)) {
                // Already moved back or never written
                skipped++;
                continue;
            }

            if (Files.exists(dest)) {
                // Destination already exists — do not overwrite active file
                skipped++;
                plugin.getLogger().warning("[Restore] Skipping " + relativePath
                        + " — destination already exists.");
                continue;
            }

            Files.createDirectories(dest.getParent());
            Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE);
            restored++;
        }

        // Rename manifest to mark as restored (keeps it as an audit trail)
        Files.move(manifestFile, applyDir.resolve("apply-manifest.restored.json"),
                StandardCopyOption.REPLACE_EXISTING);

        progress.accept("§a✓ Restored: §f" + restored + " §7files returned, " + skipped + " skipped.");

        plugin.getLogger().info("[Restore] " + applyDir.getFileName()
                + " — restored=" + restored + " skipped=" + skipped
                + " world=" + world.getName());
    }

    /**
     * Find the most recent non-restored apply directory under quarantine/.
     * Relies on the applyId timestamp format "apply-yyyyMMdd-HHmmss".
     */
    Path findLatestApplyDir(Path quarantineRoot) throws IOException {
        if (!Files.isDirectory(quarantineRoot)) return null;

        return Files.list(quarantineRoot)
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().startsWith("apply-"))
                .filter(p -> Files.exists(p.resolve("apply-manifest.json")))
                .max(Comparator.comparing(p -> p.getFileName().toString()))
                .orElse(null);
    }

    /**
     * Parse the "moves" array from a hand-written apply-manifest.json.
     * Extracts quoted strings from the moves array without requiring a JSON library.
     */
    List<String> readManifestMoves(Path manifestFile) throws IOException {
        String json = Files.readString(manifestFile, StandardCharsets.UTF_8);

        // Find the moves array
        int movesIdx = json.indexOf("\"moves\"");
        if (movesIdx < 0) return List.of();

        int arrayStart = json.indexOf('[', movesIdx);
        int arrayEnd   = json.indexOf(']', arrayStart);
        if (arrayStart < 0 || arrayEnd < 0) return List.of();

        String movesSection = json.substring(arrayStart, arrayEnd);
        Matcher m = MOVE_LINE.matcher(movesSection);
        List<String> result = new ArrayList<>();
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }
}
