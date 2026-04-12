package dev.minecraft.prune;

import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class PurgeService {

    private final WorldPrunePlugin plugin;

    public PurgeService(WorldPrunePlugin plugin) {
        this.plugin = plugin;
    }

    public record ApplyDirInfo(String applyId, boolean isRestored, boolean hasManifest, int fileCount, long sizeBytes, String purgeToken) {}

    // ─── List ─────────────────────────────────────────────────────────────────

    /**
     * Lists all apply directories under a world's quarantine folder,
     * sorted newest-first.
     */
    public List<ApplyDirInfo> listQuarantine(World world) throws IOException {
        Path quarantineRoot = world.getWorldFolder().toPath().resolve("quarantine");
        if (!Files.isDirectory(quarantineRoot)) return List.of();

        List<ApplyDirInfo> result = new ArrayList<>();
        for (Path dir : Files.list(quarantineRoot)
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().startsWith("apply-"))
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                .toList()) {

            String applyId = dir.getFileName().toString();
            boolean isRestored = Files.exists(dir.resolve("apply-manifest.restored.json"));
            boolean hasManifest = isRestored || Files.exists(dir.resolve("apply-manifest.json"));

            int fileCount;
            long sizeBytes;
            try (Stream<Path> walk = Files.walk(dir)) {
                List<Path> allFiles = walk.filter(Files::isRegularFile).toList();
                fileCount = (int) allFiles.stream()
                        .filter(p -> p.getFileName().toString().endsWith(".mca"))
                        .count();
                sizeBytes = allFiles.stream()
                        .mapToLong(p -> {
                            try { return Files.size(p); } catch (IOException ignored) { return 0L; }
                        })
                        .sum();
            }

            // Include all dirs (incomplete/orphaned shown with fileCount but no token action)
            result.add(new ApplyDirInfo(applyId, isRestored, hasManifest, fileCount, sizeBytes, tokenForApply(applyId)));
        }
        return result;
    }

    // ─── Purge ────────────────────────────────────────────────────────────────

    /**
     * Permanently deletes a quarantine apply directory.
     * Validates the purge token before proceeding.
     */
    public void purge(World world, String applyId, String confirmToken,
                      Consumer<String> progress) throws IOException {
        String expected = tokenForApply(applyId);
        if (!confirmToken.equalsIgnoreCase(expected)) {
            throw new IOException("Invalid purge token for " + applyId
                    + ". Run /prune purge " + world.getName() + " to see the correct token.");
        }

        Path applyDir = world.getWorldFolder().toPath()
                .resolve("quarantine").resolve(applyId);

        if (!Files.isDirectory(applyDir)) {
            throw new IOException("Quarantine directory not found: " + applyId);
        }

        // Count and size what we're about to delete
        List<Path> toDelete;
        try (Stream<Path> walk = Files.walk(applyDir)) {
            toDelete = walk.sorted(Comparator.reverseOrder()).toList();
        }

        List<Path> regularFiles = toDelete.stream().filter(Files::isRegularFile).toList();
        long fileCount = regularFiles.size();
        long sizeBytes = regularFiles.stream()
                .mapToLong(p -> { try { return Files.size(p); } catch (IOException ignored) { return 0L; } })
                .sum();

        for (Path p : toDelete) {
            Files.deleteIfExists(p);
        }

        progress.accept("§c✗ Permanently deleted §f" + applyId
                + " §c(" + fileCount + " files, " + formatSize(sizeBytes) + "). This cannot be undone.");

        plugin.getLogger().info("[Purge] Permanently deleted " + applyId
                + " — files=" + fileCount + " world=" + world.getName());
    }

    // ─── Token ────────────────────────────────────────────────────────────────

    /** Format a byte count as a human-readable string (GiB / MiB / KiB / B). */
    static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.2f GiB", bytes / 1024.0 / 1024.0 / 1024.0);
        } else if (bytes >= 1024L * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1024.0 / 1024.0);
        } else if (bytes >= 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        } else {
            return bytes + " B";
        }
    }

    /** Derive a short deterministic confirm token from an apply ID. */
    static String tokenForApply(String applyId) {
        return String.format("%06X", Math.abs(applyId.hashCode()) % 0x1000000);
    }
}
