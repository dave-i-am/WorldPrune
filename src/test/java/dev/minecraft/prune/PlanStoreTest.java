package dev.minecraft.prune;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanStoreTest {

    @Test
    void saveAndLoadMetadataRoundTripsCoreFields() throws Exception {
        Path reportsRoot = Files.createTempDirectory("planstore-test-");
        PlanStore store = new PlanStore(reportsRoot);

        store.savePlanMetadata("plan-1", "world", "claims", "claims", 5, 12, 3, 1.234, "ABC123");
        PlanStore.PlanMetadata loaded = store.loadPlanMetadata("plan-1");

        assertNotNull(loaded);
        assertEquals("plan-1", loaded.planId);
        assertEquals("world", loaded.worldName);
        assertEquals("claims", loaded.source);
        assertEquals("claims", loaded.mode);
        assertEquals(5, loaded.marginChunks);
        assertEquals(12, loaded.keepCount);
        assertEquals(3, loaded.pruneCount);
        assertEquals(1.234d, loaded.reclaimableGiB);
        assertTrue(loaded.timestampMs > 0);
        assertEquals("ABC123", loaded.confirmToken);
    }

    @Test
    void listPlansSortsNewestFirstAndFiltersByWorldCaseInsensitively() throws Exception {
        Path reportsRoot = Files.createTempDirectory("planstore-test-");
        PlanStore store = new PlanStore(reportsRoot);

        store.savePlanMetadata("plan-a", "world", "claims", "claims", 5, 10, 2, 0.500, PlanStore.tokenForPlan("plan-a"));
        Thread.sleep(5);
        store.savePlanMetadata("plan-b", "world_nether", "claims", "claims", 5, 8, 4, 0.250, PlanStore.tokenForPlan("plan-b"));
        Thread.sleep(5);
        store.savePlanMetadata("plan-c", "WORLD", "heuristic", "entity-aware", 0, 6, 7, 0.750, PlanStore.tokenForPlan("plan-c"));

        List<PlanStore.PlanMetadata> allPlans = store.listPlans(null);
        List<PlanStore.PlanMetadata> worldOnly = store.listPlans("world");

        assertEquals(List.of("plan-c", "plan-b", "plan-a"), allPlans.stream().map(m -> m.planId).toList());
        assertEquals(List.of("plan-c", "plan-a"), worldOnly.stream().map(m -> m.planId).toList());
    }

    @Test
    void listPlansSkipsMalformedIndexRows() throws Exception {
        Path reportsRoot = Files.createTempDirectory("planstore-test-");
        Files.writeString(
                reportsRoot.resolve("plans.index"),
                "bad-line\nplan-9|world|claims|claims|1000|5|10|2|0.123\n",
                StandardCharsets.UTF_8
        );

        PlanStore store = new PlanStore(reportsRoot);
        List<PlanStore.PlanMetadata> plans = store.listPlans(null);

        assertEquals(1, plans.size());
        assertEquals("plan-9", plans.getFirst().planId);
        // Legacy rows without a token field fall back to tokenForPlan()
        assertEquals(PlanStore.tokenForPlan("plan-9"), plans.getFirst().confirmToken);
    }

    @Test
    void getLatestPlanReturnsNewestForWorldAndNullWhenAbsent() throws Exception {
        Path reportsRoot = Files.createTempDirectory("planstore-test-");
        PlanStore store = new PlanStore(reportsRoot);

        assertNull(store.getLatestPlan("world"));

        store.savePlanMetadata("plan-old", "world", "claims", "claims", 5, 10, 2, 0.5, "TOK1");
        Thread.sleep(5);
        store.savePlanMetadata("plan-new", "world", "claims", "claims", 5, 10, 2, 0.5, "TOK2");

        PlanStore.PlanMetadata latest = store.getLatestPlan("world");
        assertNotNull(latest);
        assertEquals("plan-new", latest.planId);
        assertEquals("TOK2", latest.confirmToken);
    }
}
