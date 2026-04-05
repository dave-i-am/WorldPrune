package dev.minecraft.prune;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PlanStore {
    private final Path reportsRoot;

    public PlanStore(Path reportsRoot) {
        this.reportsRoot = reportsRoot;
    }

    static class PlanMetadata {
        String planId;
        String worldName;
        String source;
        String mode;
        long timestampMs;
        int marginChunks;
        int keepCount;
        int pruneCount;
        double reclaimableGiB;
        String confirmToken;

        PlanMetadata(String planId, String worldName, String source, String mode, long timestampMs, int marginChunks, int keepCount, int pruneCount, double reclaimableGiB, String confirmToken) {
            this.planId = planId;
            this.worldName = worldName;
            this.source = source;
            this.mode = mode;
            this.timestampMs = timestampMs;
            this.marginChunks = marginChunks;
            this.keepCount = keepCount;
            this.pruneCount = pruneCount;
            this.reclaimableGiB = reclaimableGiB;
            this.confirmToken = confirmToken;
        }

        static PlanMetadata fromCsv(String line) {
            String[] parts = line.split("\\|", -1);
            if (parts.length < 9) return null;
            try {
                String token = parts.length >= 10 ? parts[9] : tokenForPlan(parts[0]);
                return new PlanMetadata(
                        parts[0], parts[1], parts[2], parts[3],
                        Long.parseLong(parts[4]),
                        Integer.parseInt(parts[5]),
                        Integer.parseInt(parts[6]),
                        Integer.parseInt(parts[7]),
                        Double.parseDouble(parts[8]),
                        token
                );
            } catch (Exception e) {
                return null;
            }
        }

        String toCsv() {
            return "%s|%s|%s|%s|%d|%d|%d|%d|%.3f|%s".formatted(
                    planId, worldName, source, mode, timestampMs, marginChunks, keepCount, pruneCount, reclaimableGiB, confirmToken
            );
        }
    }

    /** Derive a short deterministic confirm token from a plan ID. */
    static String tokenForPlan(String planId) {
        return String.format("%06X", Math.abs(planId.hashCode()) % 0x1000000);
    }

    public void savePlanMetadata(String planId, String worldName, String source, String mode, int marginChunks, int keepCount, int pruneCount, double reclaimableGiB, String confirmToken) throws IOException {
        Path planDir = reportsRoot.resolve(planId);
        Files.createDirectories(planDir);

        PlanMetadata meta = new PlanMetadata(planId, worldName, source, mode, System.currentTimeMillis(), marginChunks, keepCount, pruneCount, reclaimableGiB, confirmToken);
        Path metaFile = planDir.resolve("plan.meta");
        Files.writeString(metaFile, meta.toCsv() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        // Append to global index
        Path indexFile = reportsRoot.resolve("plans.index");
        Files.writeString(indexFile, meta.toCsv() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public PlanMetadata loadPlanMetadata(String planId) throws IOException {
        Path metaFile = reportsRoot.resolve(planId).resolve("plan.meta");
        if (!Files.isRegularFile(metaFile)) return null;
        String line = Files.readString(metaFile, StandardCharsets.UTF_8).trim();
        return PlanMetadata.fromCsv(line);
    }

    public PlanMetadata getLatestPlan(String worldName) throws IOException {
        List<PlanMetadata> plans = listPlans(worldName);
        return plans.isEmpty() ? null : plans.get(0);
    }

    public List<PlanMetadata> listPlans(String worldNameFilter) throws IOException {
        Path indexFile = reportsRoot.resolve("plans.index");
        if (!Files.isRegularFile(indexFile)) return List.of();

        List<PlanMetadata> all = new ArrayList<>();
        for (String line : Files.readAllLines(indexFile, StandardCharsets.UTF_8)) {
            PlanMetadata meta = PlanMetadata.fromCsv(line.trim());
            if (meta != null && (worldNameFilter == null || meta.worldName.equalsIgnoreCase(worldNameFilter))) {
                all.add(meta);
            }
        }
        all.sort(Comparator.comparingLong((PlanMetadata m) -> m.timestampMs).reversed());
        return all;
    }

    public File getPlanReportDir(String planId) {
        return reportsRoot.resolve(planId).toFile();
    }
}
