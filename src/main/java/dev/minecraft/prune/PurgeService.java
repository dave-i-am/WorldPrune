package dev.minecraft.prune;

import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public final class PurgeService {

    private final WorldPrunePlugin plugin;

    public PurgeService(WorldPrunePlugin plugin) {
        this.plugin = plugin;
    }

    public record ApplyDirInfo(String applyId, boolean isRestored, boolean hasManifest, int fileCount, String purgeToken) {}

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

            int fileCount = (int) Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".mca"))
                    .count();

            // Include all dirs (incomplete/orphaned shown with fileCount but no token action)
            result.add(new ApplyDirInfo(applyId, isRestored, hasManifest, fileCount, tokenForApply(applyId)));
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

        // Count what we're about to delete
        List<Path> toDelete = Files.walk(applyDir)
                .sorted(Comparator.reverseOrder())  // children before parents
                .toList();

        long fileCount = toDelete.stream().filter(Files::isRegularFile).count();

        for (Path p : toDelete) {
            Files.deleteIfExists(p);
        }

        progress.accept("§c✗ Permanently deleted §f" + applyId
                + " §c(" + fileCount + " files). This cannot be undone.");

        plugin.getLogger().info("[Purge] Permanently deleted " + applyId
                + " — files=" + fileCount + " world=" + world.getName());
    }

    // ─── Token ────────────────────────────────────────────────────────────────

    /** Derive a short deterministic confirm token from an apply ID. */
    static String tokenForApply(String applyId) {
        return String.format("%06X", Math.abs(applyId.hashCode()) % 0x1000000);
    }
}
