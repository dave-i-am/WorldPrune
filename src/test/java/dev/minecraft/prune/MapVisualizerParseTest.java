package dev.minecraft.prune;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapVisualizerParseTest {

    @Test
    void parse_normalPositive() {
        int[] r = MapVisualizer.parseRegionName("r.3.7.mca");
        assertNotNull(r);
        assertEquals(3, r[0]);
        assertEquals(7, r[1]);
    }

    @Test
    void parse_negativeCoords() {
        int[] r = MapVisualizer.parseRegionName("r.-1.-2.mca");
        assertNotNull(r);
        assertEquals(-1, r[0]);
        assertEquals(-2, r[1]);
    }

    @Test
    void parse_zeroZero() {
        int[] r = MapVisualizer.parseRegionName("r.0.0.mca");
        assertNotNull(r);
        assertEquals(0, r[0]);
        assertEquals(0, r[1]);
    }

    @Test
    void parse_wrongExtension_returnsNull() {
        assertNull(MapVisualizer.parseRegionName("r.0.0.nbt"));
    }

    @Test
    void parse_missingLeadingR_returnsNull() {
        assertNull(MapVisualizer.parseRegionName("x.0.0.mca"));
    }

    @Test
    void parse_tooFewParts_returnsNull() {
        assertNull(MapVisualizer.parseRegionName("r.0.mca"));
    }

    @Test
    void parse_tooManyParts_returnsNull() {
        assertNull(MapVisualizer.parseRegionName("r.0.0.0.mca"));
    }

    @Test
    void parse_nonNumericCoord_returnsNull() {
        assertNull(MapVisualizer.parseRegionName("r.abc.0.mca"));
    }

    @Test
    void parse_null_returnsNull() {
        assertNull(MapVisualizer.parseRegionName(null));
    }
}
