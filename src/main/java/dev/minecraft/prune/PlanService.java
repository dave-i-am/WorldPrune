package dev.minecraft.prune;

import org.bukkit.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class PlanService {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final WorldPrunePlugin plugin;
    private final ClaimBoundsProvider claimBoundsProvider;
    private final PlanStore planStore;
    private final HeuristicService heuristicService;

    public PlanService(WorldPrunePlugin plugin, PlanStore planStore, HeuristicService heuristicService) {
        this.plugin = plugin;
        this.claimBoundsProvider = new ClaimBoundsProvider();
        this.planStore = planStore;
        this.heuristicService = heuristicService;
    }

    public PlanResult generatePlan(World world, int marginChunks) throws IOException {
        String planId = "plan-" + TS.format(LocalDateTime.now());
        Path reportsRoot = dataRoot().resolve("reports");
        Path reportDir = reportsRoot.resolve(planId).resolve(world.getName());
        Files.createDirectories(reportDir);

        Path claimDir = resolvePathOrDefault(
                plugin.getConfig().getString("claims.path", "plugins/GriefPreventionData/ClaimData"),
                plugin.getServer().getWorldContainer().toPath().resolve("plugins/GriefPreventionData/ClaimData")
        );

        ClaimBoundsProvider.ClaimLoadResult claimLoad = claimBoundsProvider.load(world, claimDir);
        List<Rect> claimBlocks = claimLoad.claims();
        String claimSource = claimLoad.source();
        List<Rect> manualBlocks = loadManualKeepBlocks(world);

        Set<String> keepRegions = toKeepRegions(claimBlocks, marginChunks, manualBlocks);
        List<Path> existingRegionFiles = listExistingMca(world.getWorldFolder().toPath().resolve("region"));
        Set<String> existingRegionNames = existingRegionFiles.stream()
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toCollection(HashSet::new));

        Set<String> keptExisting = new HashSet<>(existingRegionNames);
        keptExisting.retainAll(keepRegions);

        Set<String> pruneCandidates = new HashSet<>(existingRegionNames);
        pruneCandidates.removeAll(keepRegions);

        List<String> keepSorted = keepRegions.stream().sorted().toList();
        List<String> candidateSorted = pruneCandidates.stream().sorted().toList();
        List<String> keptExistingSorted = keptExisting.stream().sorted().toList();

        write(reportDir.resolve("keep-regions-from-claims-and-manual.txt"), String.join("\n", keepSorted) + "\n");
        write(reportDir.resolve("prune-candidate-regions.txt"), String.join("\n", candidateSorted) + "\n");
        write(reportDir.resolve("kept-existing-regions.txt"), String.join("\n", keptExistingSorted) + "\n");

        double reclaimableGiB = estimateReclaimableGiB(world, pruneCandidates);
        Map<String, Object> summaryFields = new LinkedHashMap<>();
        summaryFields.put("world", world.getName());
        summaryFields.put("claimSource", claimSource);
        summaryFields.put("marginChunks", marginChunks);
        summaryFields.put("claimRectangles", claimBlocks.size());
        summaryFields.put("manualRectangles", manualBlocks.size());
        summaryFields.put("keepRegions", keepRegions.size());
        summaryFields.put("existingRegionFiles", existingRegionNames.size());
        summaryFields.put("pruneCandidates", pruneCandidates.size());
        summaryFields.put("reclaimableGiBEstimate", reclaimableGiB);
        write(reportDir.resolve("summary.json"), JsonUtil.toJson(summaryFields));

        // Save metadata to artifact store
        String confirmToken = PlanStore.tokenForPlan(planId);
        planStore.savePlanMetadata(planId, world.getName(), claimSource, "claims", marginChunks, keepRegions.size(), pruneCandidates.size(), reclaimableGiB, confirmToken);

        return new PlanResult(planId, world.getName(), reportDir.toFile(), claimSource, confirmToken);
    }

    public PlanResult generateCombinedPlan(World world, int marginChunks, HeuristicMode heuristicMode) throws IOException {
        String planId = "plan-combined-" + TS.format(LocalDateTime.now());
        Path reportsRoot = dataRoot().resolve("reports");
        Path reportDir = reportsRoot.resolve(planId).resolve(world.getName());
        Files.createDirectories(reportDir);

        // --- Claims side ---
        Path claimDir = resolvePathOrDefault(
                plugin.getConfig().getString("claims.path", "plugins/GriefPreventionData/ClaimData"),
                plugin.getServer().getWorldContainer().toPath().resolve("plugins/GriefPreventionData/ClaimData")
        );
        ClaimBoundsProvider.ClaimLoadResult claimLoad = claimBoundsProvider.load(world, claimDir);
        List<Rect> claimBlocks = claimLoad.claims();
        String claimSource = claimLoad.source();
        List<Rect> manualBlocks = loadManualKeepBlocks(world);
        Set<String> claimKeep = toKeepRegions(claimBlocks, marginChunks, manualBlocks);

        // --- Heuristic side ---
        Set<String> heuristicKeep = heuristicService.computeKeepRegions(world, heuristicMode);

        // Union: keep anything either source says to keep
        Set<String> keepRegions = new HashSet<>(claimKeep);
        keepRegions.addAll(heuristicKeep);

        List<Path> existingRegionFiles = listExistingMca(world.getWorldFolder().toPath().resolve("region"));
        Set<String> existingRegionNames = existingRegionFiles.stream()
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toCollection(HashSet::new));

        Set<String> keptExisting = new HashSet<>(existingRegionNames);
        keptExisting.retainAll(keepRegions);

        Set<String> pruneCandidates = new HashSet<>(existingRegionNames);
        pruneCandidates.removeAll(keepRegions);

        write(reportDir.resolve("keep-regions-combined.txt"), String.join("\n", keepRegions.stream().sorted().toList()) + "\n");
        write(reportDir.resolve("prune-candidate-regions.txt"), String.join("\n", pruneCandidates.stream().sorted().toList()) + "\n");
        write(reportDir.resolve("kept-existing-regions.txt"), String.join("\n", keptExisting.stream().sorted().toList()) + "\n");

        double reclaimableGiB = estimateReclaimableGiB(world, pruneCandidates);
        Map<String, Object> summaryFields = new LinkedHashMap<>();
        summaryFields.put("source", "combined");
        summaryFields.put("claimSource", claimSource);
        summaryFields.put("heuristicMode", heuristicMode.cli());
        summaryFields.put("world", world.getName());
        summaryFields.put("marginChunks", marginChunks);
        summaryFields.put("claimRectangles", claimBlocks.size());
        summaryFields.put("manualRectangles", manualBlocks.size());
        summaryFields.put("claimKeepRegions", claimKeep.size());
        summaryFields.put("heuristicKeepRegions", heuristicKeep.size());
        summaryFields.put("combinedKeepRegions", keepRegions.size());
        summaryFields.put("existingRegionFiles", existingRegionNames.size());
        summaryFields.put("pruneCandidates", pruneCandidates.size());
        summaryFields.put("reclaimableGiBEstimate", reclaimableGiB);
        write(reportDir.resolve("summary.json"), JsonUtil.toJson(summaryFields));

        String confirmToken = PlanStore.tokenForPlan(planId);
        planStore.savePlanMetadata(planId, world.getName(), "combined", heuristicMode.cli(), marginChunks, keepRegions.size(), pruneCandidates.size(), reclaimableGiB, confirmToken);
        return new PlanResult(planId, world.getName(), reportDir.toFile(), "combined", confirmToken);
    }

    private Path dataRoot() {
        String configured = plugin.getConfig().getString("storage.dataRoot", "");
        if (configured == null || configured.isBlank()) {
            return plugin.getDataFolder().toPath();
        }
        return Path.of(configured);
    }

    private Path resolvePathOrDefault(String configured, Path fallback) {
        if (configured == null || configured.isBlank()) return fallback;
        Path path = Path.of(configured);
        if (path.isAbsolute()) return path;
        return plugin.getServer().getWorldContainer().toPath().resolve(configured);
    }

    private List<Rect> loadManualKeepBlocks(World world) {
        String configured = plugin.getConfig().getString("manualKeep.path", "");
        if (configured == null || configured.isBlank()) {
            return List.of();
        }

        Path path = Path.of(configured);
        if (!path.isAbsolute()) {
            path = plugin.getDataFolder().toPath().resolve(configured);
        }

        if (!Files.isRegularFile(path)) return List.of();

        String worldName = world.getName().toLowerCase(Locale.ROOT);
        List<Rect> out = new ArrayList<>();

        try {
            for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split(";");
                if (parts.length < 5) continue;
                if (!parts[0].trim().equalsIgnoreCase(worldName)) continue;

                int x1 = Integer.parseInt(parts[1].trim());
                int z1 = Integer.parseInt(parts[2].trim());
                int x2 = Integer.parseInt(parts[3].trim());
                int z2 = Integer.parseInt(parts[4].trim());
                out.add(new Rect(x1, z1, x2, z2));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse manual keep file: " + e.getMessage());
        }

        return out;
    }

    private Set<String> toKeepRegions(List<Rect> claimBlocks, int marginChunks, List<Rect> manualBlocks) {
        Set<String> keep = new HashSet<>();

        for (Rect claim : claimBlocks) {
            int minChunkX = floorDiv(claim.minX(), 16) - marginChunks;
            int maxChunkX = floorDiv(claim.maxX(), 16) + marginChunks;
            int minChunkZ = floorDiv(claim.minZ(), 16) - marginChunks;
            int maxChunkZ = floorDiv(claim.maxZ(), 16) + marginChunks;

            addRegionsFromChunkBounds(keep, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
        }

        for (Rect manual : manualBlocks) {
            int minChunkX = floorDiv(manual.minX(), 16);
            int maxChunkX = floorDiv(manual.maxX(), 16);
            int minChunkZ = floorDiv(manual.minZ(), 16);
            int maxChunkZ = floorDiv(manual.maxZ(), 16);

            addRegionsFromChunkBounds(keep, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
        }

        return keep;
    }

    private void addRegionsFromChunkBounds(Set<String> keep, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        int minRegionX = floorDiv(minChunkX, 32);
        int maxRegionX = floorDiv(maxChunkX, 32);
        int minRegionZ = floorDiv(minChunkZ, 32);
        int maxRegionZ = floorDiv(maxChunkZ, 32);

        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                keep.add("r." + rx + "." + rz + ".mca");
            }
        }
    }

    private List<Path> listExistingMca(Path regionDir) throws IOException {
        if (!Files.isDirectory(regionDir)) return List.of();
        try (Stream<Path> s = Files.list(regionDir)) {
            return s
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".mca"))
                    .toList();
        }
    }

    private double estimateReclaimableGiB(World world, Set<String> pruneCandidates) {
        long bytes = 0;
        bytes += sumCandidateBytes(world.getWorldFolder().toPath().resolve("region"), pruneCandidates);
        bytes += sumCandidateBytes(world.getWorldFolder().toPath().resolve("entities"), pruneCandidates);
        bytes += sumCandidateBytes(world.getWorldFolder().toPath().resolve("poi"), pruneCandidates);
        return bytes / 1024.0 / 1024.0 / 1024.0;
    }

    private long sumCandidateBytes(Path dir, Set<String> pruneCandidates) {
        if (!Files.isDirectory(dir)) return 0;
        long total = 0;
        for (String fileName : pruneCandidates) {
            Path path = dir.resolve(fileName);
            try {
                if (Files.isRegularFile(path)) {
                    total += Files.size(path);
                }
            } catch (IOException ignored) {
            }
        }
        return total;
    }

    private void write(Path path, String text) throws IOException {
        Files.writeString(path, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private int floorDiv(int a, int b) {
        return Math.floorDiv(a, b);
    }
}
