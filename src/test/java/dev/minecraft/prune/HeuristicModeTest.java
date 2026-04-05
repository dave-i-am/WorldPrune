package dev.minecraft.prune;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeuristicModeTest {

    @Test
    void fromStringResolvesKnownValuesAndDefaultsSafely() {
        assertEquals(HeuristicMode.SIZE, HeuristicMode.fromString("size"));
        assertEquals(HeuristicMode.SIZE, HeuristicMode.fromString("SIZE"));
        assertEquals(HeuristicMode.ENTITY_AWARE, HeuristicMode.fromString("entity-aware"));
        assertEquals(HeuristicMode.ENTITY_AWARE, HeuristicMode.fromString("ENTITY_AWARE"));
        assertEquals(HeuristicMode.ENTITY_AWARE, HeuristicMode.fromString("unknown"));
        assertEquals(HeuristicMode.ENTITY_AWARE, HeuristicMode.fromString(null));
    }

    @Test
    void cliValuesStayStable() {
        assertEquals("size", HeuristicMode.SIZE.cli());
        assertEquals("entity-aware", HeuristicMode.ENTITY_AWARE.cli());
    }
}
