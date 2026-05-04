package dev.minecraft.prune;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for WebMapService helper logic.
 *
 * <p>Full BlueMap/Dynmap integration cannot be tested without those plugins'
 * runtime jars; we verify the support methods, graceful no-ops, and the
 * region-coordinate parser instead.
 */
class WebMapServiceTest {

    private final WebMapService service = new WebMapService(Logger.getLogger("test"));

    // ── parseRegionCoords ─────────────────────────────────────────────────────

    @Test
    void parsesPositiveCoords() {
        assertArrayEquals(new int[]{3, 7}, WebMapService.parseRegionCoords("r.3.7.mca"));
    }

    @Test
    void parsesNegativeCoords() {
        assertArrayEquals(new int[]{-5, -12}, WebMapService.parseRegionCoords("r.-5.-12.mca"));
    }

    @Test
    void parsesZeroZero() {
        assertArrayEquals(new int[]{0, 0}, WebMapService.parseRegionCoords("r.0.0.mca"));
    }

    @Test
    void parsesWithoutMcaSuffix() {
        assertArrayEquals(new int[]{1, 2}, WebMapService.parseRegionCoords("r.1.2"));
    }

    @Test
    void returnsNullForWrongExtension() {
        assertNull(WebMapService.parseRegionCoords("r.1.2.txt"));
    }

    @Test
    void returnsNullForMissingRPrefix() {
        assertNull(WebMapService.parseRegionCoords("x.1.2.mca"));
    }

    @Test
    void returnsNullForTooFewParts() {
        assertNull(WebMapService.parseRegionCoords("r.1.mca"));
    }

    @Test
    void returnsNullForNonNumericCoord() {
        assertNull(WebMapService.parseRegionCoords("r.a.b.mca"));
    }

    @Test
    void returnsNullForNull() {
        assertNull(WebMapService.parseRegionCoords(null));
    }

    // ── updateMarkers — graceful no-op when BlueMap/Dynmap absent ─────────────

    @Test
    void updateMarkersDoesNotThrowWhenBothPluginsAbsent(@TempDir Path planDir) throws IOException {
        // Neither BlueMap nor dynmap is loaded in the test JVM — must not throw
        Path worldDir = planDir.resolve("world");
        Files.createDirectories(worldDir);
        Files.writeString(worldDir.resolve("kept-existing-regions.txt"),
                "r.0.0.mca\nr.1.0.mca\n", StandardCharsets.UTF_8);
        Files.writeString(worldDir.resolve("prune-candidate-regions.txt"),
                "r.100.100.mca\n", StandardCharsets.UTF_8);

        // isBlueMapAvailable() and isDynmapAvailable() both return false in tests
        // so updateMarkers should return immediately without touching any API.
        // We just verify it doesn't throw.
        org.bukkit.World mockWorld = org.mockito.Mockito.mock(org.bukkit.World.class);
        org.mockito.Mockito.when(mockWorld.getName()).thenReturn("world");

        service.updateMarkers(mockWorld, planDir);
        // No assertions needed — success means no exception thrown
    }

    // ── blueMapWorldNameCandidates ────────────────────────────────────────────
    // These tests guard the world-name candidate logic that maps Bukkit world
    // names to the variants BlueMap may use for nether/end dimensions.

    @Test
    void candidatesAlwaysIncludeOriginalName() {
        assertTrue(WebMapService.blueMapWorldNameCandidates("myworld").contains("myworld"));
    }

    @Test
    void candidatesForOverworldDoNotAddDimensionSuffixes() {
        var c = WebMapService.blueMapWorldNameCandidates("world");
        assertTrue(c.contains("world"));
        assertFalse(c.contains("world/nether"),  "overworld should not add /nether");
        assertFalse(c.contains("world/the_end"), "overworld should not add /the_end");
    }

    @Test
    void candidatesForNetherIncludesSlashNether() {
        var c = WebMapService.blueMapWorldNameCandidates("world_nether");
        assertTrue(c.contains("world_nether"),  "original name always included");
        assertTrue(c.contains("world/nether"),  "BlueMap 5.x nether path variant");
        assertTrue(c.contains("world-nether"),  "dash variant");
    }

    @Test
    void candidatesForCustomNetherWorld() {
        var c = WebMapService.blueMapWorldNameCandidates("survival_nether");
        assertTrue(c.contains("survival/nether"));
        assertTrue(c.contains("survival-nether"));
        assertTrue(c.contains("survival_nether"));
    }

    @Test
    void candidatesForTheEndIncludesSlashEnd() {
        var c = WebMapService.blueMapWorldNameCandidates("world_the_end");
        assertTrue(c.contains("world_the_end"), "original name always included");
        assertTrue(c.contains("world/the_end"), "BlueMap 5.x end path variant");
        assertTrue(c.contains("world/end"),     "short end variant");
        assertTrue(c.contains("world-the_end"), "dash variant");
        assertTrue(c.contains("world-end"),     "short dash variant");
    }

    @Test
    void candidatesForNonDimensionWorldHaveNoExtraEntries() {
        var c = WebMapService.blueMapWorldNameCandidates("creative");
        // Should only contain the exact name — no spurious nether/end variants
        assertTrue(c.contains("creative"));
        assertFalse(c.stream().anyMatch(s -> s.contains("nether") || s.contains("end")));
    }
}
