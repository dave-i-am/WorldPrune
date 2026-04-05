package dev.minecraft.prune;

import org.bukkit.World;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public final class HeuristicService {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final WorldPrunePlugin plugin;
    private final PlanStore planStore;

    public HeuristicService(WorldPrunePlugin plugin, PlanStore planStore) {
        this.plugin = plugin;
        this.planStore = planStore;
    }

    public HeuristicResult run(World world, HeuristicMode mode) throws IOException {
        String runId = "heuristic-" + TS.format(LocalDateTime.now());
        Path reportDir = dataRoot()
                .resolve("reports")
                .resolve(runId)
                .resolve(world.getName());
        Files.createDirectories(reportDir);

        Path worldFolder = world.getWorldFolder().toPath();
        Path regionDir = worldFolder.resolve("region");
        Path entitiesDir = worldFolder.resolve("entities");
        Path poiDir = worldFolder.resolve("poi");

        Set<String> keep;
        List<String> details;

        if (mode == HeuristicMode.SIZE) {
            SizeThresholds thresholds = readSizeThresholds();
            keep = evaluateSizeMode(regionDir, entitiesDir, poiDir, thresholds);
            details = List.of(
                    "mode=size",
                    "regionMinBytes=" + thresholds.regionMinBytes,
                    "entitiesMinBytes=" + thresholds.entitiesMinBytes,
                    "poiMinBytes=" + thresholds.poiMinBytes
            );
        } else {
            EntityAwareSettings settings = readEntityAwareSettings();
            EntityAwareEvaluation evaluation = evaluateEntityAwareMode(entitiesDir, settings);
            keep = evaluation.keepRegions();
            details = evaluation.scanLines();
        }

        Set<String> allRegionNames = listAllRegionNames(regionDir, entitiesDir, poiDir);
        Set<String> prune = new HashSet<>(allRegionNames);
        prune.removeAll(keep);

        List<String> keepSorted = keep.stream().sorted().toList();
        List<String> pruneSorted = prune.stream().sorted().toList();
        List<String> detailsSorted = details.stream().sorted(Comparator.naturalOrder()).toList();

        write(reportDir.resolve("keep-regions-heuristic-" + mode.cli() + ".txt"), String.join("\n", keepSorted) + "\n");
        write(reportDir.resolve("prune-candidate-regions-heuristic-" + mode.cli() + ".txt"), String.join("\n", pruneSorted) + "\n");
        write(reportDir.resolve("heuristic-scan-details-" + mode.cli() + ".txt"), String.join("\n", detailsSorted) + "\n");

        double reclaimableGiB = estimateReclaimableGiB(worldFolder, prune);
        Map<String, Object> summaryFields = new LinkedHashMap<>();
        summaryFields.put("heuristicMode", mode.cli());
        summaryFields.put("world", world.getName());
        summaryFields.put("keepRegions", keep.size());
        summaryFields.put("pruneCandidates", prune.size());
        summaryFields.put("reclaimableGiBEstimate", reclaimableGiB);
        write(reportDir.resolve("summary.json"), JsonUtil.toJson(summaryFields));

        // Save metadata to artifact store
        String confirmToken = PlanStore.tokenForPlan(runId);
        planStore.savePlanMetadata(runId, world.getName(), mode.cli(), mode.cli(), 0, keep.size(), prune.size(), reclaimableGiB, confirmToken);

        return new HeuristicResult(runId, world.getName(), mode.cli(), keep.size(), prune.size(), reclaimableGiB, reportDir.toFile(), confirmToken);
    }

    /**
     * Package-private: evaluate entity-aware mode against an explicit entities
     * directory with caller-supplied thresholds. Used by unit tests so they do
     * not need a live plugin / config.
     */
    Set<String> evaluateEntityAware(Path entitiesDir, long entitySizeKeepBytes,
                                     List<String> strongEntityIds) throws IOException {
        List<String> normalized = strongEntityIds.stream()
                .map(s -> s.toLowerCase(Locale.ROOT)).toList();
        return evaluateEntityAwareMode(entitiesDir,
                new EntityAwareSettings(entitySizeKeepBytes, normalized)).keepRegions();
    }

    /**
     * Package-private: compute the keep set for the given mode without writing
     * any report files. Used by PlanService for combined plans.
     */
    Set<String> computeKeepRegions(World world, HeuristicMode mode) throws IOException {
        Path worldFolder = world.getWorldFolder().toPath();
        Path regionDir = worldFolder.resolve("region");
        Path entitiesDir = worldFolder.resolve("entities");
        Path poiDir = worldFolder.resolve("poi");

        if (mode == HeuristicMode.SIZE) {
            return evaluateSizeMode(regionDir, entitiesDir, poiDir, readSizeThresholds());
        } else {
            return evaluateEntityAwareMode(entitiesDir, readEntityAwareSettings()).keepRegions();
        }
    }

    private Path dataRoot() {
        String configured = plugin.getConfig().getString("storage.dataRoot", "");
        if (configured == null || configured.isBlank()) {
            return plugin.getDataFolder().toPath();
        }
        return Path.of(configured);
    }

    private SizeThresholds readSizeThresholds() {
        long entitiesMin = plugin.getConfig().getLong("keepRules.size.entitiesMinBytes", 131072L);
        long poiMin = plugin.getConfig().getLong("keepRules.size.poiMinBytes", 65536L);
        long regionMin = plugin.getConfig().getLong("keepRules.size.regionMinBytes", 65536L);
        return new SizeThresholds(regionMin, entitiesMin, poiMin);
    }

    private EntityAwareSettings readEntityAwareSettings() {
        long entitySizeKeep = plugin.getConfig().getLong("keepRules.entitySizeKeepBytes", 262144L);
        List<String> configured = plugin.getConfig().getStringList("keepRules.strongEntityIds");
        List<String> strong = configured.isEmpty()
                ? List.of("minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:armor_stand",
                          "minecraft:painting", "minecraft:leash_knot")
                : configured;
        List<String> normalized = strong.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        return new EntityAwareSettings(entitySizeKeep, normalized);
    }

    private Set<String> evaluateSizeMode(Path regionDir, Path entitiesDir, Path poiDir, SizeThresholds t) throws IOException {
        Set<String> all = listAllRegionNames(regionDir, entitiesDir, poiDir);
        Set<String> keep = new TreeSet<>();

        for (String fileName : all) {
            long regionSize = safeSize(regionDir.resolve(fileName));
            long entitiesSize = safeSize(entitiesDir.resolve(fileName));
            long poiSize = safeSize(poiDir.resolve(fileName));
            if (regionSize >= t.regionMinBytes || entitiesSize >= t.entitiesMinBytes || poiSize >= t.poiMinBytes) {
                keep.add(fileName);
            }
        }

        return keep;
    }

    private EntityAwareEvaluation evaluateEntityAwareMode(Path entitiesDir, EntityAwareSettings settings) throws IOException {
        Set<String> keep = new TreeSet<>();
        List<String> details = new ArrayList<>();
        if (!Files.isDirectory(entitiesDir)) {
            details.add("entitiesDirMissing=true");
            return new EntityAwareEvaluation(keep, details);
        }

        List<Path> entityFiles = Files.list(entitiesDir)
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".mca"))
                .sorted()
                .toList();

        for (Path file : entityFiles) {
            String name = file.getFileName().toString();
            long size = safeSize(file);
            if (size >= settings.entitySizeKeepBytes()) {
                keep.add(name);
                details.add(name + "|keep=size-threshold|bytes=" + size);
                continue;
            }

            EntityScan scan = scanEntityRegionFile(file, settings.strongEntityIds());
            if (scan.keep()) {
                keep.add(name);
                details.add(name + "|keep=" + scan.reason());
            } else {
                details.add(name + "|prune|reason=" + scan.reason());
            }
        }

        return new EntityAwareEvaluation(keep, details);
    }

    private EntityScan scanEntityRegionFile(Path file, List<String> strongEntityIds) {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() < 8192) {
                return new EntityScan(false, "empty-header");
            }

            // Read all 1024 header entries up front before seeking anywhere,
            // otherwise seeks into chunk data corrupt subsequent readInt() positions.
            raf.seek(0);
            int[] locations = new int[1024];
            for (int i = 0; i < 1024; i++) {
                locations[i] = raf.readInt();
            }

            for (int i = 0; i < 1024; i++) {
                int location = locations[i];
                int sectorOffset = (location >> 8) & 0x00FFFFFF;
                int sectorCount = location & 0xFF;
                if (sectorOffset == 0 || sectorCount == 0) continue;

                long absoluteOffset = sectorOffset * 4096L;
                if (absoluteOffset + 5 > raf.length()) continue;

                raf.seek(absoluteOffset);
                int length = raf.readInt();
                if (length <= 1 || length > (sectorCount * 4096) - 4) continue;

                int compressionType = raf.readUnsignedByte();
                byte[] compressed = new byte[length - 1];
                raf.readFully(compressed);

                byte[] chunkPayload = decompressChunk(compressed, compressionType);
                if (chunkPayload.length == 0) continue;
                String payload = new String(chunkPayload, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);

                if (payload.contains("customname")) {
                    return new EntityScan(true, "custom-name");
                }
                if (payload.contains("owneruuid") || payload.contains("owner")) {
                    return new EntityScan(true, "owner-marker");
                }
                for (String id : strongEntityIds) {
                    if (id.contains("minecraft:chest_minecart")) continue;
                    if (payload.contains(id)) {
                        return new EntityScan(true, "strong-entity-id:" + id);
                    }
                }
            }
        } catch (Exception e) {
            return new EntityScan(true, "scan-error-conservative-keep");
        }

        return new EntityScan(false, "no-entity-signals");
    }

    private byte[] decompressChunk(byte[] compressed, int compressionType) throws IOException {
        return switch (compressionType) {
            case 1 -> new GZIPInputStream(new ByteArrayInputStream(compressed)).readAllBytes();
            case 2 -> new InflaterInputStream(new ByteArrayInputStream(compressed)).readAllBytes();
            case 3 -> compressed;
            default -> new byte[0];
        };
    }

    private Set<String> listAllRegionNames(Path regionDir, Path entitiesDir, Path poiDir) throws IOException {
        Set<String> names = new TreeSet<>();
        collectMcaNames(regionDir, names);
        collectMcaNames(entitiesDir, names);
        collectMcaNames(poiDir, names);
        return names;
    }

    private void collectMcaNames(Path dir, Set<String> names) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".mca"))
                    .forEach(names::add);
        }
    }

    private double estimateReclaimableGiB(Path worldFolder, Set<String> pruneCandidates) {
        long bytes = 0;
        bytes += sumCandidateBytes(worldFolder.resolve("region"), pruneCandidates);
        bytes += sumCandidateBytes(worldFolder.resolve("entities"), pruneCandidates);
        bytes += sumCandidateBytes(worldFolder.resolve("poi"), pruneCandidates);
        return bytes / 1024.0 / 1024.0 / 1024.0;
    }

    private long sumCandidateBytes(Path dir, Set<String> pruneCandidates) {
        if (!Files.isDirectory(dir)) return 0;
        long total = 0;
        for (String fileName : pruneCandidates) {
            total += safeSize(dir.resolve(fileName));
        }
        return total;
    }

    private long safeSize(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private void write(Path path, String text) throws IOException {
        Files.writeString(path, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private record SizeThresholds(long regionMinBytes, long entitiesMinBytes, long poiMinBytes) {}

    private record EntityAwareSettings(long entitySizeKeepBytes, List<String> strongEntityIds) {}

    private record EntityAwareEvaluation(Set<String> keepRegions, List<String> scanLines) {}

    private record EntityScan(boolean keep, String reason) {}
}
