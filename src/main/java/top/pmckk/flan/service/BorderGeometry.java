package top.pmckk.flan.service;

import top.pmckk.flan.model.Bounds;
import java.util.*;

/** Inclusive block coordinates, with a bounded number of markers even for very long edges. */
public final class BorderGeometry {
    private BorderGeometry() {}
    public record Position(int x, int y, int z) {}
    private record Edge(Position from, Position to) {
        long length() { return Math.abs((long) to.x - from.x) + Math.abs((long) to.y - from.y) + Math.abs((long) to.z - from.z); }
        long desired() {
            return Math.min(Math.max(0, length() - 1), 2 + 2 * Math.max(0, (length() + 7) / 20));
        }
        Position at(int index) {
            long length = length();
            long offset = Math.min(1L + (index / 2) * 10L, length / 2);
            double fraction = (double) (index % 2 == 0 ? offset : length - offset) / length;
            return new Position(interpolate(from.x, to.x, fraction), interpolate(from.y, to.y, fraction), interpolate(from.z, to.z, fraction));
        }
        private static int interpolate(int a, int b, double fraction) { return (int) Math.round(a + ((long) b - a) * fraction); }
    }

    public static List<Position> markers(Bounds bounds, boolean threeDimensional, int height, int maximum) {
        if (maximum < 8) throw new IllegalArgumentException("maximum < 8");
        List<Position> vertices = new ArrayList<>();
        int low = threeDimensional ? bounds.minY() : height;
        for (int y : threeDimensional ? new int[]{low, bounds.maxY()} : new int[]{low}) {
            vertices.add(new Position(bounds.minX(), y, bounds.minZ()));
            vertices.add(new Position(bounds.maxX(), y, bounds.minZ()));
            vertices.add(new Position(bounds.maxX(), y, bounds.maxZ()));
            vertices.add(new Position(bounds.minX(), y, bounds.maxZ()));
        }
        Set<Position> result = new LinkedHashSet<>(vertices);
        List<Edge> edges = new ArrayList<>();
        // Reserve the first markers for vertical edges, even with a small point budget.
        if (threeDimensional) for (int i = 0; i < 4; i++) edges.add(new Edge(vertices.get(i), vertices.get(i + 4)));
        for (int offset = 0; offset < vertices.size(); offset += 4)
            for (int i = 0; i < 4; i++) edges.add(new Edge(vertices.get(offset + i), vertices.get(offset + (i + 1) % 4)));
        int[] counts = new int[edges.size()];
        int remaining = maximum - result.size();
        // Like the original display, sample inward from both endpoints, then every ten blocks.
        boolean added = true;
        while (remaining > 0 && added) {
            added = false;
            for (int i = 0; i < edges.size() && remaining > 0; i++) {
                long desired = edges.get(i).desired();
                if (counts[i] < desired) { counts[i]++; remaining--; added = true; }
            }
        }
        for (int i = 0; i < edges.size(); i++)
            for (int j = 0; j < counts[i]; j++) result.add(edges.get(i).at(j));
        return List.copyOf(result);
    }
}
