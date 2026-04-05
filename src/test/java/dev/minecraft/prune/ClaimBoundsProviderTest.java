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

        assertEquals("claim-files", result.source());
        assertTrue(result.claims().isEmpty());
    }

    @Test
    void returnsEmptyWhenClaimDirectoryMissing() {
        Path missingDir = Path.of(System.getProperty("java.io.tmpdir"), "missing-claim-dir-" + UUID.randomUUID());
        World world = mock(World.class);
        when(world.getName()).thenReturn("survival");

        ClaimBoundsProvider.ClaimLoadResult result = new ClaimBoundsProvider().load(world, missingDir);

        assertEquals("claim-files", result.source());
        assertTrue(result.claims().isEmpty());
    }
}
