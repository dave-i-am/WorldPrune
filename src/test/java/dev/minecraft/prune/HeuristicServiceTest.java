package dev.minecraft.prune;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the entity-aware evaluation logic in HeuristicService.
 *
 * Tests use synthetic .mca binary fixtures built in-memory and written to a
 * temp directory. No plugin, server, or Bukkit runtime is required.
 *
 * .mca region format recap:
 *   Bytes 0–4095    : 1024 × 4-byte location entries (big-endian)
 *                     entry = (sectorOffset << 8) | sectorCount
 *   Bytes 4096–8191 : 1024 × 4-byte timestamp entries (can be zero)
 *   Bytes 8192+     : chunk sectors (4096-byte aligned)
 *                     each sector begins with a 4-byte length (includes
 *                     the compression-type byte), then 1-byte compression
 *                     type, then the payload.
 *                     Compression type 3 = uncompressed pass-through.
 */
class HeuristicServiceTest {

    @TempDir Path tmp;

    /** Service under test – plugin and planStore are null because the tested
     *  code path (evaluateEntityAware) does not touch either field. */
    HeuristicService service;

    // Default test thresholds
    static final long THRESHOLD_HIGH = 1_000_000L; // won't be exceeded → always scan
    static final long THRESHOLD_LOW  = 1L;          // any non-empty file exceeds this → kept by size

    static final List<String> STRONG_IDS = List.of(
            "minecraft:villager",
            "minecraft:item_frame",
            "minecraft:armor_stand",
            "minecraft:chest_minecart"  // explicitly excluded by the service implementation
    );

    @BeforeEach
    void setUp() {
        service = new HeuristicService(null, null);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Build a minimal valid .mca file that contains exactly one chunk at
     * location-table slot 0 with an uncompressed (type 3) payload.
     */
    static byte[] buildMca(byte[] chunkPayload) throws IOException {
        // length field counts the compression-type byte itself
        int lengthField = chunkPayload.length + 1;
        // How many 4096-byte sectors does the chunk occupy?
        int sectorCount = Math.max(1, (int) Math.ceil((lengthField + 4) / 4096.0));
        // Chunk data immediately follows the 8192-byte header (sectors 0 + 1)
        int sectorOffset = 2;
        int locationEntry = (sectorOffset << 8) | sectorCount;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Location table (1024 × 4 bytes = 4096 bytes)
        dos.writeInt(locationEntry); // slot 0
        for (int i = 1; i < 1024; i++) dos.writeInt(0);

        // Timestamp table (1024 × 4 bytes = 4096 bytes)
        for (int i = 0; i < 1024; i++) dos.writeInt(0);

        // Chunk sector at byte offset 8192
        dos.writeInt(lengthField);   // length (includes compression-type byte)
        dos.writeByte(3);            // compression type: uncompressed
        dos.write(chunkPayload);

        // Pad to a 4096-byte boundary so the file length is realistic
        int written = 4 + 1 + chunkPayload.length;
        int pad = (4096 - (written % 4096)) % 4096;
        dos.write(new byte[pad]);

        dos.flush();
        return baos.toByteArray();
    }

    /** Write an .mca file to {@code dir/<name>} and return its path. */
    static Path writeMca(Path dir, String name, byte[] content) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.write(file, content);
        return file;
    }

    /** Convenience: build and write an .mca file with a plain-text payload. */
    static void writeMcaWithPayload(Path entitiesDir, String name, String payload) throws IOException {
        writeMca(entitiesDir, name, buildMca(payload.getBytes(StandardCharsets.ISO_8859_1)));
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void missingEntitiesDir_returnsEmptyKeepSet() throws IOException {
        Path noSuchDir = tmp.resolve("entities-missing");
        Set<String> kept = service.evaluateEntityAware(noSuchDir, THRESHOLD_HIGH, STRONG_IDS);
        assertTrue(kept.isEmpty(), "No entities dir → nothing to keep");
    }

    @Test
    void emptyEntitiesDir_returnsEmptyKeepSet() throws IOException {
        Path entitiesDir = tmp.resolve("entities");
        Files.createDirectories(entitiesDir);
        Set<String> kept = service.evaluateEntityAware(entitiesDir, THRESHOLD_HIGH, STRONG_IDS);
        assertTrue(kept.isEmpty(), "Empty entities dir → nothing to keep");
    }

    @Test
    void nonMcaFileIgnored() throws IOException {
        Path entitiesDir = tmp.resolve("entities");
        Files.createDirectories(entitiesDir);
        Files.writeString(entitiesDir.resolve("readme.txt"), "not a region file");
        Set<String> kept = service.evaluateEntityAware(entitiesDir, THRESHOLD_HIGH, STRONG_IDS);
        assertTrue(kept.isEmpty(), ".txt files should be ignored");
    }

    @Test
    void fileLargeEnoughForSizeThreshold_keptWithoutScanning() throws IOException {
        Path entitiesDir = tmp.resolve("entities");
        // Payload has no entity signals — but the file size will equal the
        // threshold so the size fast-path kicks in before the scan.
        byte[] plain = "no interesting entities here".getBytes(StandardCharsets.ISO_8859_1);
        byte[] mca = buildMca(plain);

        writeMca(entitiesDir, "r.0.0.mca", mca);

        // Threshold set exactly to the file's size → the file meets the threshold
        long threshold = mca.length;
        Set<String> kept = service.evaluateEntityAware(entitiesDir, threshold, List.of());
        assertTrue(kept.contains("r.0.0.mca"),
                "File of size == threshold should be kept via size fast-path");
    }

    @Test
    void strongEntityVillager_keptByPayloadScan() throws IOException {
        Path entitiesDir = tmp.resolve("entities");
        writeMcaWithPayload(entitiesDir, "r.1.0.mca", "...minecraft:villager...");

        Set<String> kept = service.evaluateEntityAware(entitiesDir, THRESHOLD_HIGH, STRONG_IDS);
        assertTrue(kept.contains("r.1.0.mca"), "Villager in payload → keep");
    }

    @Test
    void strongEntityItemFrame_keptByPayloadScan() throws IOException {
        Path entitiesDir = tmp.resolve("entities");
        writeMcaWithPayload(entitiesDir, "r.2.0.mca", "minecraft:item_frame something");

        Set<String> kept = service.evaluateEntityAware(entitiesDir, THRESHOLD_HIGH, STRONG_IDS);
        assertTrue(kept.contains("r.2.0.mca"), "Item frame in payload → keep");
    }

    @Test
    void customName_keptByPayloadScan() throws IOException {
        Path entitiesDir = tmp.resolve("entities");
        // customname is a special signal independent of strongEntityIds
        writeMcaWithPayload(entitiesDir, "r.3.0.mca", "tag:customname:fred");

        Set<String> kept = service.evaluateEntityAware(entitiesDir, THRESHOLD_HIGH, STRONG_IDS);
        assertTrue(kept.contains("r.3.0.mca"), "CustomName NBT tag → keep");
    }

    @Test
    void ownerUuid_keptByPayloadScan() throws IOException {
        Path entitiesDir = tmp.resolve("entities");
        writeMcaWithPayload(entitiesDir, "r.4.0.mca", "owneruuid:some-uuid");

        Set<String> kept = service.evaluateEntityAware(entitiesDir, THRESHOLD_HIGH, STRONG_IDS);
        assertTrue(kept.contains("r.4.0.mca"), "OwnerUUID NBT tag → keep");
    }

    @Test
    void ownerTag_keptByPayloadScan() throws IOException {
        Path entitiesDir = tmp.resolve("entities");
        writeMcaWithPayload(entitiesDir, "r.5.0.mca", "owner:player123");

        Set<String> kept = service.evaluateEntityAware(entitiesDir, THRESHOLD_HIGH, STRONG_IDS);
        assertTrue(kept.contains("r.5.0.mca"), "Owner NBT tag → keep");
    }

    @Test
    void noEntitySignals_pruned() throws IOException {
        Path entitiesDir = tmp.resolve("entities");
        writeMcaWithPayload(entitiesDir, "r.6.0.mca",
                "minecraft:creeper minecraft:zombie some other data no signals here");

        Set<String> kept = service.evaluateEntityAware(entitiesDir, THRESHOLD_HIGH,
                List.of("minecraft:villager")); // only villager counts
        assertFalse(kept.contains("r.6.0.mca"), "No signals → should be pruned");
    }

    @Test
    void fileShorterThanHeader_pruned() throws IOException {
        Path entitiesDir = tmp.resolve("entities");
        // File shorter than 8192 bytes → empty-header branch → prune
        writeMca(entitiesDir, "r.7.0.mca", "too small".getBytes(StandardCharsets.ISO_8859_1));

        Set<String> kept = service.evaluateEntityAware(entitiesDir, THRESHOLD_HIGH, STRONG_IDS);
        assertFalse(kept.contains("r.7.0.mca"), "File shorter than header → prune");
    }

    @Test
    void chestMinecartExplicitlyExcluded_pruned() throws IOException {
        Path entitiesDir = tmp.resolve("entities");
        // minecraft:chest_minecart is in STRONG_IDS but the service skips it
        writeMcaWithPayload(entitiesDir, "r.8.0.mca", "minecraft:chest_minecart stuff");

        Set<String> kept = service.evaluateEntityAware(entitiesDir, THRESHOLD_HIGH, STRONG_IDS);
        assertFalse(kept.contains("r.8.0.mca"),
                "chest_minecart must be excluded even when in strongEntityIds");
    }

    @Test
    void corruptChunkDecompression_conservativeKeep() throws IOException {
        Path entitiesDir = tmp.resolve("entities");
        // Write a valid header pointing at a chunk that claims to be GZIP-compressed
        // (type 1) but contains garbage bytes → GZIPInputStream throws → catch →
        // conservative keep ("scan-error-conservative-keep").
        byte[] garbage = "this is not valid gzip data at all !!!".getBytes(StandardCharsets.ISO_8859_1);
        int lengthField = garbage.length + 1;
        int sectorCount = 1;
        int locationEntry = (2 << 8) | sectorCount; // sector offset 2, count 1

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(locationEntry);
        for (int i = 1; i < 1024; i++) dos.writeInt(0);
        for (int i = 0; i < 1024; i++) dos.writeInt(0); // timestamps
        dos.writeInt(lengthField);
        dos.writeByte(1); // GZIP — but the data is garbage
        dos.write(garbage);
        dos.write(new byte[4096]); // padding
        dos.flush();

        writeMca(entitiesDir, "r.9.0.mca", baos.toByteArray());

        Set<String> kept = service.evaluateEntityAware(entitiesDir, THRESHOLD_HIGH, STRONG_IDS);
        assertTrue(kept.contains("r.9.0.mca"),
                "Corrupt chunk should trigger conservative keep to avoid data loss");
    }

    @Test
    void gzipCompressedStrongEntity_keptByPayloadScan() throws IOException {
        Path entitiesDir = tmp.resolve("entities");

        // Build a properly GZIP-compressed payload
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(compressed)) {
            gz.write("minecraft:armor_stand".getBytes(StandardCharsets.ISO_8859_1));
        }

        int lengthField = compressed.size() + 1;
        int sectorCount = Math.max(1, (int) Math.ceil((lengthField + 4) / 4096.0));
        int locationEntry = (2 << 8) | sectorCount;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(locationEntry);
        for (int i = 1; i < 1024; i++) dos.writeInt(0);
        for (int i = 0; i < 1024; i++) dos.writeInt(0);
        dos.writeInt(lengthField);
        dos.writeByte(1); // GZIP compression
        dos.write(compressed.toByteArray());
        int written = 4 + 1 + compressed.size();
        dos.write(new byte[(4096 - (written % 4096)) % 4096]);
        dos.flush();

        writeMca(entitiesDir, "r.10.0.mca", baos.toByteArray());

        Set<String> kept = service.evaluateEntityAware(entitiesDir, THRESHOLD_HIGH, STRONG_IDS);
        assertTrue(kept.contains("r.10.0.mca"),
                "GZIP-compressed chunk with armor_stand → keep");
    }

    @Test
    void multipleFiles_mixedKeepAndPrune() throws IOException {
        Path entitiesDir = tmp.resolve("entities");
        writeMcaWithPayload(entitiesDir, "r.0.0.mca", "minecraft:villager nearby");
        writeMcaWithPayload(entitiesDir, "r.0.1.mca", "minecraft:creeper and zombies only");
        writeMcaWithPayload(entitiesDir, "r.1.0.mca", "owneruuid:player-abc");
        writeMcaWithPayload(entitiesDir, "r.1.1.mca", "nothing notable here");

        Set<String> kept = service.evaluateEntityAware(entitiesDir, THRESHOLD_HIGH,
                List.of("minecraft:villager"));

        assertTrue(kept.contains("r.0.0.mca"),  "villager → keep");
        assertFalse(kept.contains("r.0.1.mca"), "creeper only → prune");
        assertTrue(kept.contains("r.1.0.mca"),  "ownerUUID → keep");
        assertFalse(kept.contains("r.1.1.mca"), "no signals → prune");
    }

    @Test
    void payloadMatchIsCaseInsensitive() throws IOException {
        Path entitiesDir = tmp.resolve("entities");
        // The service lowercases the payload before checking, so uppercase in raw
        // bytes should still trigger a keep.
        writeMcaWithPayload(entitiesDir, "r.11.0.mca", "MINECRAFT:VILLAGER");

        Set<String> kept = service.evaluateEntityAware(entitiesDir, THRESHOLD_HIGH,
                List.of("minecraft:villager"));
        assertTrue(kept.contains("r.11.0.mca"), "Match should be case-insensitive");
    }
}
