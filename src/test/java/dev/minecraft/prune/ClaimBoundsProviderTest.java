package dev.minecraft.prune;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClaimBoundsProviderTest {

    @Test
    void fallsBackToClaimFilesAndParsesValidCornerData() throws Exception {
        Path claimDir = Files.createTempDirectory("claim-data-");
        Files.writeString(
                claimDir.resolve("claim-1.txt"),
                "10;64;20\n40;70;80\n",
                StandardCharsets.UTF_8
        );

        World world = mock(World.class);
        when(world.getName()).thenReturn("survival");

        ClaimBoundsProvider provider = new ClaimBoundsProvider();
        ClaimBoundsProvider.ClaimLoadResult result = provider.load(world, claimDir);

        assertEquals("claim-files", result.source());
        assertEquals(1, result.claims().size());
        assertEquals(10, result.claims().getFirst().x1());
        assertEquals(20, result.claims().getFirst().z1());
        assertEquals(40, result.claims().getFirst().x2());
        assertEquals(80, result.claims().getFirst().z2());
    }

    @Test
    void ignoresClaimsThatDeclareDifferentWorld() throws Exception {
        Path claimDir = Files.createTempDirectory("claim-data-");
        Files.writeString(
                claimDir.resolve("claim-other-world.txt"),
                "10;64;20\n40;70;80\nworld: nether\n",
                StandardCharsets.UTF_8
        );

        World world = mock(World.class);
        when(world.getName()).thenReturn("survival");

        ClaimBoundsProvider.ClaimLoadResult result = new ClaimBoundsProvider().load(world, claimDir);

        assertEquals("none", result.source());
        assertTrue(result.claims().isEmpty());
    }

    @Test
    void returnsEmptyWhenClaimDirectoryMissing() {
        Path missingDir = Path.of(System.getProperty("java.io.tmpdir"), "missing-claim-dir-" + UUID.randomUUID());
        World world = mock(World.class);
        when(world.getName()).thenReturn("survival");

        ClaimBoundsProvider.ClaimLoadResult result = new ClaimBoundsProvider().load(world, missingDir);

        assertEquals("none", result.source());
        assertTrue(result.claims().isEmpty());
    }

    // ─────────────────────────── Towny file fallback ─────────────────────────

    @Test
    void loadsTownyChunksFromFiles() throws Exception {
        Path townyDir = Files.createTempDirectory("towny-");
        Files.createFile(townyDir.resolve("survival_2_5.data"));
        Files.createFile(townyDir.resolve("survival_-1_3.data"));
        Files.createFile(townyDir.resolve("nether_0_0.data")); // different world — ignored

        World world = mock(World.class);
        when(world.getName()).thenReturn("survival");

        ClaimBoundsProvider.ClaimLoadResult result =
                new ClaimBoundsProvider().load(world, null, townyDir, null);

        assertEquals("towny-files", result.source());
        assertEquals(2, result.claims().size());

        // chunk (2,5) → blocks (32–47, 80–95)
        assertTrue(result.claims().stream().anyMatch(r ->
                r.x1() == 32 && r.z1() == 80 && r.x2() == 47 && r.z2() == 95));
        // chunk (-1,3) → blocks (-16–0-1, 48–63)
        assertTrue(result.claims().stream().anyMatch(r ->
                r.x1() == -16 && r.z1() == 48 && r.x2() == -1 && r.z2() == 63));
    }

    @Test
    void townyFileParsisIsCaseInsensitiveOnWorldName() throws Exception {
        Path townyDir = Files.createTempDirectory("towny-ci-");
        Files.createFile(townyDir.resolve("survival_0_0.data"));

        World world = mock(World.class);
        when(world.getName()).thenReturn("Survival"); // uppercase

        ClaimBoundsProvider.ClaimLoadResult result =
                new ClaimBoundsProvider().load(world, null, townyDir, null);

        assertEquals(1, result.claims().size());
        assertEquals("towny-files", result.source());
    }

    @Test
    void townyIgnoresMissingDirectory() {
        Path missingDir = Path.of(System.getProperty("java.io.tmpdir"), "no-towny-" + UUID.randomUUID());
        World world = mock(World.class);
        when(world.getName()).thenReturn("survival");

        ClaimBoundsProvider.ClaimLoadResult result =
                new ClaimBoundsProvider().load(world, null, missingDir, null);

        assertEquals("none", result.source());
        assertTrue(result.claims().isEmpty());
    }

    // ──────────────────────── Residence file fallback ────────────────────────

    @Test
    void loadsResidenceAreasFromFile() throws Exception {
        String yaml =
                "Residences:\n" +
                "  myfarm:\n" +
                "    Permissions:\n" +
                "      World: survival\n" +
                "    Areas:\n" +
                "      main:\n" +
                "        X1: -100\n" +
                "        Y1: 64\n" +
                "        Z1: -200\n" +
                "        X2: 100\n" +
                "        Y2: 256\n" +
                "        Z2: 200\n";
        Path resFile = Files.createTempFile("Global", ".yml");
        Files.writeString(resFile, yaml, StandardCharsets.UTF_8);

        World world = mock(World.class);
        when(world.getName()).thenReturn("survival");

        ClaimBoundsProvider.ClaimLoadResult result =
                new ClaimBoundsProvider().load(world, null, null, resFile);

        assertEquals("residence-file", result.source());
        assertEquals(1, result.claims().size());
        Rect r = result.claims().getFirst();
        assertEquals(-100, r.x1());
        assertEquals(-200, r.z1());
        assertEquals(100, r.x2());
        assertEquals(200, r.z2());
    }

    @Test
    void residenceFiltersOtherWorlds() throws Exception {
        String yaml =
                "Residences:\n" +
                "  netherbase:\n" +
                "    Permissions:\n" +
                "      World: nether\n" +
                "    Areas:\n" +
                "      main:\n" +
                "        X1: 0\n" +
                "        Y1: 64\n" +
                "        Z1: 0\n" +
                "        X2: 50\n" +
                "        Y2: 128\n" +
                "        Z2: 50\n";
        Path resFile = Files.createTempFile("Global-other", ".yml");
        Files.writeString(resFile, yaml, StandardCharsets.UTF_8);

        World world = mock(World.class);
        when(world.getName()).thenReturn("survival");

        ClaimBoundsProvider.ClaimLoadResult result =
                new ClaimBoundsProvider().load(world, null, null, resFile);

        assertEquals("none", result.source());
        assertTrue(result.claims().isEmpty());
    }

    @Test
    void residenceLoadsSubZoneAreas() throws Exception {
        String yaml =
                "Residences:\n" +
                "  bigclaim:\n" +
                "    Permissions:\n" +
                "      World: survival\n" +
                "    Areas:\n" +
                "      main:\n" +
                "        X1: 0\n" +
                "        Y1: 64\n" +
                "        Z1: 0\n" +
                "        X2: 512\n" +
                "        Y2: 256\n" +
                "        Z2: 512\n" +
                "    SubZones:\n" +
                "      sub1:\n" +
                "        Permissions:\n" +
                "          World: survival\n" +
                "        Areas:\n" +
                "          main:\n" +
                "            X1: 600\n" +
                "            Y1: 64\n" +
                "            Z1: 600\n" +
                "            X2: 700\n" +
                "            Y2: 256\n" +
                "            Z2: 700\n";
        Path resFile = Files.createTempFile("Global-sub", ".yml");
        Files.writeString(resFile, yaml, StandardCharsets.UTF_8);

        World world = mock(World.class);
        when(world.getName()).thenReturn("survival");

        ClaimBoundsProvider.ClaimLoadResult result =
                new ClaimBoundsProvider().load(world, null, null, resFile);

        assertEquals("residence-file", result.source());
        assertEquals(2, result.claims().size()); // main area + sub-zone
    }

    // ───────────────────────── Multi-source merging ────────────────────────

    @Test
    void combinesGpClaimsAndTownyFiles() throws Exception {
        // GP claim file
        Path gpDir = Files.createTempDirectory("gp-multi-");
        Files.writeString(gpDir.resolve("claim-1.txt"),
                "10;64;20\n40;70;80\n", StandardCharsets.UTF_8);

        // Towny files
        Path townyDir = Files.createTempDirectory("towny-multi-");
        Files.createFile(townyDir.resolve("survival_5_5.data"));

        World world = mock(World.class);
        when(world.getName()).thenReturn("survival");

        ClaimBoundsProvider.ClaimLoadResult result =
                new ClaimBoundsProvider().load(world, gpDir, townyDir, null);

        // source reflects both contributors
        assertTrue(result.source().contains("claim-files"));
        assertTrue(result.source().contains("towny-files"));
        // 1 GP claim + 1 Towny townblock
        assertEquals(2, result.claims().size());
    }
}
