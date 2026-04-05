package dev.minecraft.prune;

import org.bukkit.World;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestoreServiceTest {

    @TempDir Path tmp;

    Path worldDir;
    Path quarantineRoot;

    WorldPrunePlugin plugin;
    World world;
    RestoreService service;

    static final String WORLD_NAME = "overworld";

    @BeforeEach
    void setUp() throws IOException {
        worldDir       = tmp.resolve(WORLD_NAME);
        quarantineRoot = worldDir.resolve("quarantine");
        Files.createDirectories(worldDir);
        Files.createDirectories(quarantineRoot);

        plugin = mock(WorldPrunePlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));

        world = mock(World.class);
        when(world.getName()).thenReturn(WORLD_NAME);
        when(world.getWorldFolder()).thenReturn(worldDir.toFile());

        service = new RestoreService(plugin);
    }

    // ─── readManifestMoves ──────────────────────────────────────────────────────

    @Test
    void readManifestMoves_parsesNormalManifest() throws IOException {
        String json = """
                {
                  "applyId": "apply-20260101-120000",
                  "planId": "plan-123",
                  "worldName": "overworld",
                  "timestampMs": 1234567890,
                  "moves": [
                    "region/r.0.0.mca",
                    "region/r.1.1.mca",
                    "entities/r.0.0.mca"
                  ]
                }
                """;
        Path manifest = writeManifest("apply-20260101-120000", json);
        List<String> moves = service.readManifestMoves(manifest);
        assertEquals(List.of("region/r.0.0.mca", "region/r.1.1.mca", "entities/r.0.0.mca"), moves);
    }

    @Test
    void readManifestMoves_emptyMovesArray() throws IOException {
        String json = "{\n  \"moves\": []\n}\n";
        Path manifest = writeManifest("apply-empty", json);
        List<String> moves = service.readManifestMoves(manifest);
        assertTrue(moves.isEmpty());
    }

    @Test
    void readManifestMoves_missingMovesKey_returnsEmpty() throws IOException {
        String json = "{\n  \"applyId\": \"apply-123\"\n}\n";
        Path manifest = writeManifest("apply-no-moves", json);
        List<String> moves = service.readManifestMoves(manifest);
        assertTrue(moves.isEmpty());
    }

    // ─── findLatestApplyDir ─────────────────────────────────────────────────────

    @Test
    void findLatestApplyDir_returnsMaxByName() throws IOException {
        Path olderDir = quarantineRoot.resolve("apply-20260101-100000");
        Path newerDir = quarantineRoot.resolve("apply-20260101-180000");
        Files.createDirectories(olderDir);
        Files.createDirectories(newerDir);

        // Only newerDir has a manifest
        Files.writeString(olderDir.resolve("apply-manifest.json"),
                "{\"moves\":[]}", StandardCharsets.UTF_8);
        Files.writeString(newerDir.resolve("apply-manifest.json"),
                "{\"moves\":[]}", StandardCharsets.UTF_8);

        Path result = service.findLatestApplyDir(quarantineRoot);
        assertEquals(newerDir, result);
    }

    @Test
    void findLatestApplyDir_ignoresDirsWithoutManifest() throws IOException {
        Path withManifest    = quarantineRoot.resolve("apply-20260101-100000");
        Path withoutManifest = quarantineRoot.resolve("apply-20260101-200000");
        Files.createDirectories(withManifest);
        Files.createDirectories(withoutManifest);
        Files.writeString(withManifest.resolve("apply-manifest.json"),
                "{\"moves\":[]}", StandardCharsets.UTF_8);
        // withoutManifest has no manifest file

        Path result = service.findLatestApplyDir(quarantineRoot);
        assertEquals(withManifest, result);
    }

    @Test
    void findLatestApplyDir_returnsNullWhenNoApplyDirs() throws IOException {
        // quarantineRoot exists but is empty
        Path result = service.findLatestApplyDir(quarantineRoot);
        assertNull(result);
    }

    @Test
    void findLatestApplyDir_returnsNullWhenQuarantineMissing() throws IOException {
        Path missing = tmp.resolve("nonexistent");
        Path result = service.findLatestApplyDir(missing);
        assertNull(result);
    }

    // ─── restore — full round-trip ──────────────────────────────────────────────

    @Test
    void restore_movesFilesBackToWorld() throws IOException {
        String applyId = "apply-20260101-120000";
        Path applyDir  = quarantineRoot.resolve(applyId);
        Files.createDirectories(applyDir.resolve("region"));

        // Place quarantined file
        Path quarantinedFile = applyDir.resolve("region").resolve("r.0.0.mca");
        Files.writeString(quarantinedFile, "mca-data", StandardCharsets.UTF_8);

        // Write manifest
        String manifest = "{\n  \"moves\": [\n    \"region/r.0.0.mca\"\n  ]\n}\n";
        Files.writeString(applyDir.resolve("apply-manifest.json"), manifest, StandardCharsets.UTF_8);

        List<String> progress = new ArrayList<>();
        service.restore(world, applyId, progress::add);

        // File returned to world dir
        assertTrue(Files.exists(worldDir.resolve("region").resolve("r.0.0.mca")));
        // No longer in quarantine
        assertFalse(Files.exists(quarantinedFile));

        // Manifest renamed to .restored.json
        assertFalse(Files.exists(applyDir.resolve("apply-manifest.json")));
        assertTrue(Files.exists(applyDir.resolve("apply-manifest.restored.json")));

        // Progress reported
        assertTrue(progress.stream().anyMatch(s -> s.contains("1")));
    }

    @Test
    void restore_nullApplyId_usesLatest() throws IOException {
        String applyId = "apply-20260101-150000";
        Path applyDir  = quarantineRoot.resolve(applyId);
        Files.createDirectories(applyDir.resolve("region"));

        Path quarantinedFile = applyDir.resolve("region").resolve("r.5.5.mca");
        Files.writeString(quarantinedFile, "data", StandardCharsets.UTF_8);

        Files.writeString(applyDir.resolve("apply-manifest.json"),
                "{\n  \"moves\": [\n    \"region/r.5.5.mca\"\n  ]\n}\n", StandardCharsets.UTF_8);

        List<String> progress = new ArrayList<>();
        service.restore(world, null, progress::add);  // null = latest

        assertTrue(Files.exists(worldDir.resolve("region").resolve("r.5.5.mca")));
        assertFalse(Files.exists(quarantinedFile));
    }

    @Test
    void restore_missingManifest_throws() throws IOException {
        String applyId = "apply-20260101-120000";
        Path applyDir  = quarantineRoot.resolve(applyId);
        Files.createDirectories(applyDir);
        // No manifest file

        IOException ex = assertThrows(IOException.class,
                () -> service.restore(world, applyId, new ArrayList<>()::add));
        assertTrue(ex.getMessage().contains("apply-manifest.json missing"));
    }

    @Test
    void restore_missingApplyDir_throws() throws IOException {
        IOException ex = assertThrows(IOException.class,
                () -> service.restore(world, "apply-nonexistent", new ArrayList<>()::add));
        assertTrue(ex.getMessage().contains("Quarantine not found"));
    }

    @Test
    void restore_noQuarantineDir_throws() throws IOException {
        // Delete quarantine root
        Files.delete(quarantineRoot);

        IOException ex = assertThrows(IOException.class,
                () -> service.restore(world, null, new ArrayList<>()::add));
        assertTrue(ex.getMessage().contains("No quarantine directory"));
    }

    @Test
    void restore_idempotent_skipsIfDestExists() throws IOException {
        String applyId = "apply-20260101-120000";
        Path applyDir  = quarantineRoot.resolve(applyId);
        Files.createDirectories(applyDir.resolve("region"));

        Path quarantinedFile = applyDir.resolve("region").resolve("r.0.0.mca");
        Files.writeString(quarantinedFile, "data", StandardCharsets.UTF_8);

        // Pre-create the destination file (simulates partially restored state)
        Files.createDirectories(worldDir.resolve("region"));
        Files.writeString(worldDir.resolve("region").resolve("r.0.0.mca"), "existing", StandardCharsets.UTF_8);

        Files.writeString(applyDir.resolve("apply-manifest.json"),
                "{\n  \"moves\": [\n    \"region/r.0.0.mca\"\n  ]\n}\n", StandardCharsets.UTF_8);

        List<String> progress = new ArrayList<>();
        // Should not throw — just skip
        service.restore(world, applyId, progress::add);

        // Destination still has original content (was not overwritten)
        assertEquals("existing", Files.readString(worldDir.resolve("region").resolve("r.0.0.mca")));
        // Quarantined file was skipped (still there since we couldn't safely move it)
        assertTrue(Files.exists(quarantinedFile));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────────

    private Path writeManifest(String applyId, String json) throws IOException {
        Path dir = quarantineRoot.resolve(applyId);
        Files.createDirectories(dir);
        Path manifest = dir.resolve("apply-manifest.json");
        Files.writeString(manifest, json, StandardCharsets.UTF_8);
        return manifest;
    }
}
