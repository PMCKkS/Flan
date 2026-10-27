package top.pmckk.flan.service;

import top.pmckk.flan.config.MessageException;
import top.pmckk.flan.model.Bounds;
import java.util.UUID;

/** Keeps raw corner heights even when the active creation mode covers the full world height. */
public final class ClaimSelection {
    public record Point(UUID world, int x, int y, int z) {}
    private final Point[] points = new Point[2];
    private Boolean threeDimensional;

    public void select(UUID world, int x, int y, int z, int corner) {
        if (corner < 0 || corner > 1) throw new IllegalArgumentException("corner");
        if (points[1 - corner] != null && !points[1 - corner].world().equals(world)) points[1 - corner] = null;
        points[corner] = new Point(world, x, y, z);
    }
    public Point anchor(UUID world) {
        for (Point point : points) if (point != null && point.world().equals(world)) return point;
        return null;
    }
    public void clearPoints() { points[0] = null; points[1] = null; }
    /** Largest affordable square before selection; a fixed corner plus the live target after selection. */
    public Bounds preview(UUID world, int x, int y, int z, int minimum, int maximumExclusive,
                          boolean threeDimensional, long area, int distance) {
        if (complete() && points[0].world().equals(world)) return bounds(world, minimum, maximumExclusive, threeDimensional);
        if (area <= 0) return null;
        Point anchor = anchor(world);
        Bounds result;
        if (anchor == null) {
            int side = (int) Math.min(Math.floor(Math.sqrt(area)), Math.max(1, (int) (distance * Math.sqrt(2))));
            y = Math.clamp(y, minimum, maximumExclusive - 1);
            int low = (side - 1) / 2, high = side - 1 - low;
            result = new Bounds(x - low, Math.max(minimum, y - low), z - low,
                    x + high, Math.min(maximumExclusive - 1, y + high), z + high);
        } else {
            long width = Math.abs((long) x - anchor.x()) + 1, length = Math.abs((long) z - anchor.z()) + 1;
            if (width * length > area) {
                double scale = Math.sqrt((double) area / (width * length));
                width = Math.clamp((long) Math.floor(width * scale), 1, area);
                length = Math.max(1, Math.min(area / width, (long) Math.floor(length * scale)));
            }
            int endX = anchor.x() + (int) ((width - 1) * Integer.signum(x - anchor.x()));
            int endZ = anchor.z() + (int) ((length - 1) * Integer.signum(z - anchor.z()));
            result = Bounds.between(anchor.x(), anchor.y(), anchor.z(), endX, Math.clamp(y, minimum, maximumExclusive - 1), endZ);
        }
        return threeDimensional ? result : new Bounds(result.minX(), minimum, result.minZ(), result.maxX(), maximumExclusive - 1, result.maxZ());
    }
    public boolean complete() { return points[0] != null && points[1] != null; }
    public void setMode(boolean value) { threeDimensional = value; }
    public boolean mode(boolean defaultMode) { return threeDimensional == null ? defaultMode : threeDimensional; }

    public Bounds bounds(UUID world, int minimum, int maximumExclusive, boolean threeDimensional) {
        if (!complete()) throw new MessageException("errors.selection-required");
        if (!points[0].world().equals(world) || !points[1].world().equals(world)) throw new MessageException("errors.selection-world");
        Bounds bounds = Bounds.between(points[0].x(), points[0].y(), points[0].z(), points[1].x(), points[1].y(), points[1].z());
        return threeDimensional ? bounds : new Bounds(bounds.minX(), minimum, bounds.minZ(), bounds.maxX(), maximumExclusive - 1, bounds.maxZ());
    }
}
