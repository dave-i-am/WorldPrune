package dev.minecraft.prune;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RectTest {

    @Test
    void minAndMaxAccessorsNormalizeInvertedCoordinates() {
        Rect rect = new Rect(120, -80, -40, 200);

        assertEquals(-40, rect.minX());
        assertEquals(120, rect.maxX());
        assertEquals(-80, rect.minZ());
        assertEquals(200, rect.maxZ());
    }
}
