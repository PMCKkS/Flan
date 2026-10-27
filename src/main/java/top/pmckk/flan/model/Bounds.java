package top.pmckk.flan.model;

import top.pmckk.flan.config.MessageException;

public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public Bounds {
        if (minX > maxX || minY > maxY || minZ > maxZ) throw new MessageException("errors.bounds-order");
    }
    public static Bounds between(int x, int y, int z, int xx, int yy, int zz) {
        return new Bounds(Math.min(x, xx), Math.min(y, yy), Math.min(z, zz),
                Math.max(x, xx), Math.max(y, yy), Math.max(z, zz));
    }
    public long area() { return Math.multiplyExact((long) maxX - minX + 1, (long) maxZ - minZ + 1); }
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
    public boolean contains(Bounds other) {
        return contains(other.minX, other.minY, other.minZ) && contains(other.maxX, other.maxY, other.maxZ);
    }
    public boolean intersects(Bounds other) {
        return minX <= other.maxX && maxX >= other.minX && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }
}
