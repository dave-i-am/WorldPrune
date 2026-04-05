package dev.minecraft.prune;

record Rect(int x1, int z1, int x2, int z2) {
    int minX() { return Math.min(x1, x2); }
    int maxX() { return Math.max(x1, x2); }
    int minZ() { return Math.min(z1, z2); }
    int maxZ() { return Math.max(z1, z2); }
}
