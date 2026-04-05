package dev.minecraft.prune;

import org.bukkit.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ApplyService {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String[] SUBDIRS = {"region", "entities", "poi"};

    private final WorldPrunePlugin plugin;
    private final PlanStore planStore;

    public ApplyService(WorldPrunePlugin plugin, PlanStore planStore) {
        this.plugin = plugin;
        this.planStore = planStore;
    }

    /**
     * Applies a plan: moves prune-candidate region files into a quarantine folder.
     * Acquires a world-level lock file to prevent concurrent applies.
     * Writes an apply-manifest.json so the operation can be fully reversed.
     * Idempotent: files already quarantined are skipped.
     *
     * @return the applyId (e.g. "apply-20260404-182301")
     */
    public String applyPlan(World world, String planId, String confirmToken,
                            boolean quarantineOnly, Consumer<String> progress) throws IOException {
        // Validate token
        PlanStore.PlanMetadata meta = planStore.loadPlanMetadata(planId);
        if (meta == null) throw new IOException("Plan not found: " + planId);
        if (!confirmToken.equalsIgnoreCase(meta.confirmToken)) {
            throw new IOException("Invalid confirm token. Run /prune apply without --confirm to see the current token.");
        }

        // Acquire world lock
        Path worldFolder = world.getWorldFolder().toPath();
        Path quarantineRoot = worldFolder.resolve("quarantine");
        Files.createDirectories(quarantineRoot);
        Path lockFile = quarantineRoot.resolve(".apply-lock");

        if (Files.exists(lockFile)) {
            String holder = Files.readString(lockFile, StandardCharsets.UTF_8).trim();
            throw new IOException("Apply already in progress for '" + world.getName() + "' (lock held by: " + holder + ")");
        }

        String applyId = "apply-" + TS.format(LocalDateTime.now());
        Files.writeString(lockFile, applyId + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        try {
            return doApply(world, planId, applyId, quarantineRoot, progress);
        } finally {
            Files.deleteIfExists(lockFile);
        }
    }

    /**
     * Returns the applyId recorded in the lock file if a stale lock exists,
     * or null if no lock is present.
     */
    public String getStaleLockInfo(World world) throws IOException {
        Path lockFile = world.getWorldFolder().toPath()
                .resolve("quarantine").resolve(".apply-lock");
        if (!Files.exists(lockFile)) return null;
        return Files.readString(lockFile, StandardCharsets.UTF_8).trim();
    }

    /**
     * Deletes a stale lock file. Safe to call when no apply is running.
     * The caller is responsible for verifying the lock is actually stale.
     */
    public void clearStaleLock(World world) throws IOException {
        Path lockFile = world.getWorldFolder().toPath()
                .resolve("quarantine").resolve(".apply-lock");
        Files.deleteIfExists(lockFile);
        plugin.getLogger().warning("[Apply] Stale lock cleared for world '" + world.getName() + "'.");
    }

    private String doApply(World world, String planId, String applyId,
                           Path quarantineRoot, Consumer<String> progress) throws IOException {
        List<String> candidates = loadCandidates(planId, world.getName());
        if (candidates.isEmpty()) {
            progress.accept("§eNo prune candidates found in plan " + planId + " — nothing to do.");
            return applyId;
        }

        Path destRoot = quarantineRoot.resolve(applyId);
        Files.createDirectories(destRoot);

        Path worldFolder = world.getWorldFolder().toPath();
        List<String> movedPaths = new ArrayList<>();
        int moved = 0, skipped = 0;

        for (String regionFile : candidates) {
            for (String subdir : SUBDIRS) {
                Path src = worldFolder.resolve(subdir).resolve(regionFile);
                Path dest = destRoot.resolve(subdir).resolve(regionFile);

                if (!Files.isRegularFile(src)) continue;

                if (Files.exists(dest)) {
                    // Already quarantined on a previous (interrupted) run — idempotent skip
                    skipped++;
                    continue;
                }

                Files.createDirectories(dest.getParent());
                Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE);
                movedPaths.add(subdir + "/" + regionFile);
                moved++;
            }
        }

        writeManifest(destRoot, applyId, planId, world.getName(), movedPaths);
        writeApplyReport(applyId, planId, world.getName(), moved, skipped);

        progress.accept("§a✓ Applied: §f" + moved + " §7files quarantined, " + skipped + " skipped.");
        progress.accept("§7Quarantine: §o" + destRoot);
        progress.accept("§7To undo: §f/prune restore " + world.getName());

        plugin.getLogger().info("[Apply] " + applyId + " — moved=" + moved + " skipped=" + skipped
                + " plan=" + planId + " world=" + world.getName());
        return applyId;
    }

    // ─────────────────── Candidate file resolution ───────────────────

    /**
     * Loads the prune-candidate list from the plan report directory.
     * Tries multiple possible filenames in order to support all plan sources.
     */
    List<String> loadCandidates(String planId, String worldName) throws IOException {
        // Plan report dir may be <planId>/ or <planId>/<worldName>/
        Path planDir = planStore.getPlanReportDir(planId).toPath();

        for (Path base : List.of(planDir.resolve(worldName), planDir)) {
            for (String name : List.of(
                    "prune-candidate-regions.txt",
                    "prune-candidate-regions-heuristic-entity-aware.txt",
                    "prune-candidate-regions-heuristic-size.txt")) {
                Path file = base.resolve(name);
                if (Files.isRegularFile(file)) {
                    return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                            .map(String::trim)
                            .filter(l -> !l.isBlank() && l.endsWith(".mca"))
                            .toList();
                }
            }
        }
        return List.of();
    }

    // ─────────────────── Manifest ────────────────────────────────────

    private void writeManifest(Path destRoot, String applyId, String planId, String worldName,
                               List<String> movedPaths) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"applyId\": \"").append(applyId).append("\",\n");
        sb.append("  \"planId\": \"").append(planId).append("\",\n");
        sb.append("  \"worldName\": \"").append(worldName).append("\",\n");
        sb.append("  \"timestampMs\": ").append(System.currentTimeMillis()).append(",\n");
        sb.append("  \"moves\": [\n");
        for (int i = 0; i < movedPaths.size(); i++) {
            sb.append("    \"").append(movedPaths.get(i)).append("\"");
            if (i < movedPaths.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}\n");
        Files.writeString(destRoot.resolve("apply-manifest.json"), sb.toString(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void writeApplyReport(String applyId, String planId, String worldName,
                                  int moved, int skipped) throws IOException {
        Path reportDir = dataRoot().resolve("reports").resolve(applyId);
        Files.createDirectories(reportDir);
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("applyId", applyId);
        fields.put("planId", planId);
        fields.put("world", worldName);
        fields.put("moved", moved);
        fields.put("skipped", skipped);
        fields.put("timestampMs", System.currentTimeMillis());
        Files.writeString(reportDir.resolve("apply-report.json"), JsonUtil.toJson(fields),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private Path dataRoot() {
        String configured = plugin.getConfig().getString("storage.dataRoot", "");
        if (configured == null || configured.isBlank()) return plugin.getDataFolder().toPath();
        return Path.of(configured);
    }
}
