package dev.minecraft.prune;

import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplyServiceTest {

    @TempDir Path tmp;

    Path worldDir;
    Path planDir;
    Path dataDir;

    WorldPrunePlugin plugin;
    PlanStore planStore;
    World world;
    ApplyService service;

    static final String PLAN_ID    = "plan-20260101-120000";
    static final String TOKEN      = "ABCD12";
    static final String WORLD_NAME = "survival";

    @BeforeEach
    void setUp() throws IOException {
        worldDir = tmp.resolve("survival");
        planDir  = tmp.resolve("reports").resolve(PLAN_ID);
        dataDir  = tmp.resolve("data");
        Files.createDirectories(worldDir);
        Files.createDirectories(planDir);
        Files.createDirectories(dataDir);

        // Mock plugin
        plugin = mock(WorldPrunePlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getDataFolder()).thenReturn(dataDir.toFile());

        FileConfiguration cfg = mock(FileConfiguration.class);
        when(cfg.getString("storage.dataRoot", "")).thenReturn("");
        when(plugin.getConfig()).thenReturn(cfg);

        // Mock world
        world = mock(World.class);
        when(world.getName()).thenReturn(WORLD_NAME);
        when(world.getWorldFolder()).thenReturn(worldDir.toFile());

        // Mock plan store
        planStore = mock(PlanStore.class);
        PlanStore.PlanMetadata meta = new PlanStore.PlanMetadata(
                PLAN_ID, WORLD_NAME, "heuristic", "entity-aware",
                System.currentTimeMillis(), 5, 10, 3, 0.5, TOKEN);

        when(planStore.loadPlanMetadata(PLAN_ID)).thenReturn(meta);
        when(planStore.getPlanReportDir(PLAN_ID)).thenReturn(planDir.toFile());

        service = new ApplyService(plugin, planStore);
    }

    // ─── loadCandidates ────────────────────────────────────────────────────────

    @Test
    void loadCandidates_findsCanonicalFile() throws IOException {
        Path candidateFile = planDir.resolve("prune-candidate-regions.txt");
        Files.writeString(candidateFile, "r.0.0.mca\nr.1.-1.mca\n  \n", StandardCharsets.UTF_8);

        List<String> result = service.loadCandidates(PLAN_ID, WORLD_NAME);
        assertEquals(List.of("r.0.0.mca", "r.1.-1.mca"), result);
    }

    @Test
    void loadCandidates_findsHeuristicVariant() throws IOException {
        Path candidateFile = planDir.resolve("prune-candidate-regions-heuristic-entity-aware.txt");
        Files.writeString(candidateFile, "r.2.2.mca\n", StandardCharsets.UTF_8);

        List<String> result = service.loadCandidates(PLAN_ID, WORLD_NAME);
        assertEquals(List.of("r.2.2.mca"), result);
    }

    @Test
    void loadCandidates_ignoresNonMcaLines() throws IOException {
        Path candidateFile = planDir.resolve("prune-candidate-regions.txt");
        Files.writeString(candidateFile, "r.0.0.mca\nnot-a-region\nr.1.1.mca\n", StandardCharsets.UTF_8);

        List<String> result = service.loadCandidates(PLAN_ID, WORLD_NAME);
        assertEquals(List.of("r.0.0.mca", "r.1.1.mca"), result);
    }

    @Test
    void loadCandidates_emptyWhenNoFileFound() throws IOException {
        List<String> result = service.loadCandidates(PLAN_ID, WORLD_NAME);
        assertTrue(result.isEmpty());
    }

    // ─── applyPlan — wrong token ────────────────────────────────────────────────

    @Test
    void applyPlan_wrongToken_throws() {
        List<String> progress = new ArrayList<>();
        IOException ex = assertThrows(IOException.class,
                () -> service.applyPlan(world, PLAN_ID, "WRONG1", true, progress::add));
        assertTrue(ex.getMessage().contains("Invalid confirm token"));
    }

    // ─── applyPlan — full happy path ─────────────────────────────────────────

    @Test
    void applyPlan_movesFilesToQuarantine() throws IOException {
        // Create candidate list
        Files.writeString(planDir.resolve("prune-candidate-regions.txt"),
                "r.0.0.mca\nr.1.1.mca\n", StandardCharsets.UTF_8);

        // Create the actual region files in world dir
        Path worldRegion = worldDir.resolve("region");
        Files.createDirectories(worldRegion);
        Files.writeString(worldRegion.resolve("r.0.0.mca"), "fake-mca-data", StandardCharsets.UTF_8);
        Files.writeString(worldRegion.resolve("r.1.1.mca"), "fake-mca-data", StandardCharsets.UTF_8);

        // Also create an entities file for one region
        Path worldEntities = worldDir.resolve("entities");
        Files.createDirectories(worldEntities);
        Files.writeString(worldEntities.resolve("r.0.0.mca"), "fake-entities", StandardCharsets.UTF_8);

        List<String> progress = new ArrayList<>();
        String applyId = service.applyPlan(world, PLAN_ID, TOKEN, true, progress::add);

        // applyId format
        assertTrue(applyId.startsWith("apply-"), "applyId should start with 'apply-'");

        // Files moved to quarantine
        Path quarantineApplyDir = worldDir.resolve("quarantine").resolve(applyId);
        assertTrue(Files.exists(quarantineApplyDir.resolve("region").resolve("r.0.0.mca")));
        assertTrue(Files.exists(quarantineApplyDir.resolve("region").resolve("r.1.1.mca")));
        assertTrue(Files.exists(quarantineApplyDir.resolve("entities").resolve("r.0.0.mca")));

        // Originals gone
        assertFalse(Files.exists(worldRegion.resolve("r.0.0.mca")));
        assertFalse(Files.exists(worldRegion.resolve("r.1.1.mca")));

        // Manifest exists and has expected content
        Path manifest = quarantineApplyDir.resolve("apply-manifest.json");
        assertTrue(Files.exists(manifest));
        String json = Files.readString(manifest, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"applyId\": \"" + applyId + "\""));
        assertTrue(json.contains("\"planId\": \"" + PLAN_ID + "\""));
        assertTrue(json.contains("\"worldName\": \"" + WORLD_NAME + "\""));
        assertTrue(json.contains("region/r.0.0.mca"));

        // Progress messages emitted
        assertTrue(progress.stream().anyMatch(s -> s.contains("3")), "Progress should report 3 moved files");
    }

    // ─── applyPlan — lock file cleanup ─────────────────────────────────────────

    @Test
    void applyPlan_lockFileNotLeftBehind() throws IOException {
        Files.writeString(planDir.resolve("prune-candidate-regions.txt"), "", StandardCharsets.UTF_8);
        List<String> progress = new ArrayList<>();
        service.applyPlan(world, PLAN_ID, TOKEN, true, progress::add);

        Path lockFile = worldDir.resolve("quarantine").resolve(".apply-lock");
        assertFalse(Files.exists(lockFile), "Lock file must be cleaned up after apply");
    }

    @Test
    void applyPlan_existingLock_throws() throws IOException {
        Path quarantineRoot = worldDir.resolve("quarantine");
        Files.createDirectories(quarantineRoot);
        Files.writeString(quarantineRoot.resolve(".apply-lock"), "apply-old\n", StandardCharsets.UTF_8);

        IOException ex = assertThrows(IOException.class,
                () -> service.applyPlan(world, PLAN_ID, TOKEN, true, new ArrayList<>()::add));
        assertTrue(ex.getMessage().contains("already in progress"));
    }

    // ─── applyPlan — idempotency ───────────────────────────────────────────────

    @Test
    void applyPlan_idempotent_skipsAlreadyQuarantined() throws IOException {
        Files.writeString(planDir.resolve("prune-candidate-regions.txt"), "r.0.0.mca\n", StandardCharsets.UTF_8);

        // Create region file
        Path worldRegion = worldDir.resolve("region");
        Files.createDirectories(worldRegion);
        Files.writeString(worldRegion.resolve("r.0.0.mca"), "data", StandardCharsets.UTF_8);

        List<String> progress = new ArrayList<>();
        String applyId1 = service.applyPlan(world, PLAN_ID, TOKEN, true, progress::add);

        // Now the file is in quarantine and NOT in the world dir
        // Simulate a re-run with the file already quarantined (no source)
        progress.clear();

        // Reset mock to same metadata (token still valid)
        // The file is gone from worldDir, so doApply will find dest exists and skip
        String applyId2 = service.applyPlan(world, PLAN_ID, TOKEN, true, progress::add);

        // Second apply should complete (returning new applyId) but report 0 moved
        assertTrue(progress.stream().anyMatch(s -> s.contains("0")), "Second run should report 0 moved");
    }

    // ─── applyPlan — no candidates ────────────────────────────────────────────

    @Test
    void applyPlan_noCandidates_completesGracefully() throws IOException {
        Files.writeString(planDir.resolve("prune-candidate-regions.txt"), "\n  \n", StandardCharsets.UTF_8);
        List<String> progress = new ArrayList<>();
        String applyId = service.applyPlan(world, PLAN_ID, TOKEN, true, progress::add);
        assertTrue(applyId.startsWith("apply-"));
        assertTrue(progress.stream().anyMatch(s -> s.contains("No prune candidates")));
    }
}
