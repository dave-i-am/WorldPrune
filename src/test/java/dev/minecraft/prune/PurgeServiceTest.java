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

class PurgeServiceTest {

    @TempDir Path tmp;

    Path worldDir;
    Path quarantineRoot;

    WorldPrunePlugin plugin;
    World world;
    PurgeService service;

    @BeforeEach
    void setUp() throws IOException {
        worldDir       = tmp.resolve("world");
        quarantineRoot = worldDir.resolve("quarantine");
        Files.createDirectories(quarantineRoot);

        plugin = mock(WorldPrunePlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));

        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getWorldFolder()).thenReturn(worldDir.toFile());

        service = new PurgeService(plugin);
    }

    // ─── tokenForApply ─────────────────────────────────────────────────────────

    @Test
    void tokenForApply_isDeterministic() {
        String t1 = PurgeService.tokenForApply("apply-20260101-120000");
        String t2 = PurgeService.tokenForApply("apply-20260101-120000");
        assertEquals(t1, t2);
    }

    @Test
    void tokenForApply_differsByApplyId() {
        String t1 = PurgeService.tokenForApply("apply-20260101-120000");
        String t2 = PurgeService.tokenForApply("apply-20260101-130000");
        assertNotEquals(t1, t2);
    }

    @Test
    void tokenForApply_isUppercaseHex6Chars() {
        String token = PurgeService.tokenForApply("apply-20260101-120000");
        assertTrue(token.matches("[0-9A-F]{6}"), "Token should be 6 uppercase hex chars, got: " + token);
    }

    // ─── listQuarantine ────────────────────────────────────────────────────────

    @Test
    void listQuarantine_emptyWhenNoQuarantineDir() throws IOException {
        Files.delete(quarantineRoot);
        List<PurgeService.ApplyDirInfo> result = service.listQuarantine(world);
        assertTrue(result.isEmpty());
    }

    @Test
    void listQuarantine_listsRestoredAndActive() throws IOException {
        // Active apply (has apply-manifest.json)
        Path active = quarantineRoot.resolve("apply-20260101-130000");
        Files.createDirectories(active.resolve("region"));
        Files.writeString(active.resolve("apply-manifest.json"), "{\"moves\":[]}", StandardCharsets.UTF_8);
        Files.writeString(active.resolve("region").resolve("r.0.0.mca"), "data", StandardCharsets.UTF_8);

        // Restored apply (has apply-manifest.restored.json)
        Path restored = quarantineRoot.resolve("apply-20260101-120000");
        Files.createDirectories(restored);
        Files.writeString(restored.resolve("apply-manifest.restored.json"), "{\"moves\":[]}", StandardCharsets.UTF_8);

        List<PurgeService.ApplyDirInfo> result = service.listQuarantine(world);
        assertEquals(2, result.size());

        // Newest first
        assertEquals("apply-20260101-130000", result.get(0).applyId());
        assertFalse(result.get(0).isRestored());
        assertTrue(result.get(0).hasManifest());
        assertEquals(1, result.get(0).fileCount());

        assertEquals("apply-20260101-120000", result.get(1).applyId());
        assertTrue(result.get(1).isRestored());
        assertTrue(result.get(1).hasManifest());
        assertEquals(0, result.get(1).fileCount());
    }

    @Test
    void listQuarantine_includesIncompleteDirsWithNoManifest() throws IOException {
        Path orphan = quarantineRoot.resolve("apply-20260101-120000");
        Files.createDirectories(orphan);
        // No manifest file — orphaned from a crashed apply

        List<PurgeService.ApplyDirInfo> result = service.listQuarantine(world);
        assertEquals(1, result.size());
        assertFalse(result.get(0).hasManifest(), "Dir with no manifest should have hasManifest=false");
        assertFalse(result.get(0).isRestored());
    }

    @Test
    void listQuarantine_tokenMatchesTokenForApply() throws IOException {
        String applyId = "apply-20260101-120000";
        Path dir = quarantineRoot.resolve(applyId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("apply-manifest.restored.json"), "{}", StandardCharsets.UTF_8);

        List<PurgeService.ApplyDirInfo> result = service.listQuarantine(world);
        assertEquals(1, result.size());
        assertEquals(PurgeService.tokenForApply(applyId), result.get(0).purgeToken());
    }

    // ─── purge — wrong token ───────────────────────────────────────────────────

    @Test
    void purge_wrongToken_throws() throws IOException {
        String applyId = "apply-20260101-120000";
        Path dir = quarantineRoot.resolve(applyId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("apply-manifest.restored.json"), "{}", StandardCharsets.UTF_8);

        IOException ex = assertThrows(IOException.class,
                () -> service.purge(world, applyId, "WRONG1", new ArrayList<>()::add));
        assertTrue(ex.getMessage().contains("Invalid purge token"));
    }

    // ─── purge — missing dir ───────────────────────────────────────────────────

    @Test
    void purge_missingDir_throws() {
        String applyId = "apply-20260101-120000";
        String token   = PurgeService.tokenForApply(applyId);

        IOException ex = assertThrows(IOException.class,
                () -> service.purge(world, applyId, token, new ArrayList<>()::add));
        assertTrue(ex.getMessage().contains("not found"));
    }

    // ─── purge — full delete ───────────────────────────────────────────────────

    @Test
    void purge_deletesEntireApplyDir() throws IOException {
        String applyId = "apply-20260101-120000";
        String token   = PurgeService.tokenForApply(applyId);

        Path dir = quarantineRoot.resolve(applyId);
        Files.createDirectories(dir.resolve("region"));
        Files.writeString(dir.resolve("apply-manifest.restored.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("region").resolve("r.0.0.mca"), "data", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("region").resolve("r.1.1.mca"), "data", StandardCharsets.UTF_8);

        List<String> progress = new ArrayList<>();
        service.purge(world, applyId, token, progress::add);

        assertFalse(Files.exists(dir), "Apply dir should be fully deleted");
        assertTrue(progress.stream().anyMatch(s -> s.contains("2")), "Progress should mention 2 files");
    }

    @Test
    void purge_progressContainsPermanentWarning() throws IOException {
        String applyId = "apply-20260101-120000";
        String token   = PurgeService.tokenForApply(applyId);

        Path dir = quarantineRoot.resolve(applyId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("apply-manifest.restored.json"), "{}", StandardCharsets.UTF_8);

        List<String> progress = new ArrayList<>();
        service.purge(world, applyId, token, progress::add);

        assertTrue(progress.stream().anyMatch(s -> s.toLowerCase().contains("cannot be undone")
                || s.contains("ermanently")));
    }
}
