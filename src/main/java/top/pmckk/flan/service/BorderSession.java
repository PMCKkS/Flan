package top.pmckk.flan.service;

import top.pmckk.flan.config.BorderSettings.Kind;
import top.pmckk.flan.model.Bounds;
import java.util.*;

/** Combines temporary command/selection displays with nearby claims shown while holding the tool. */
public final class BorderSession {
    public record Outline(Bounds bounds, Kind kind, boolean threeDimensional) {}
    private record Shape(Bounds bounds, boolean threeDimensional) {}
    private final List<Outline> manual;
    private final int duration;
    private List<Outline> nearby = List.of();
    private List<Outline> preview = List.of();
    private boolean holding;
    private int elapsed;

    public BorderSession(List<Outline> manual, int duration) { this.manual = List.copyOf(manual); this.duration = duration; }
    public void tool(boolean holding, List<Outline> nearby) {
        tool(holding, nearby, List.of());
    }
    public void tool(boolean holding, List<Outline> nearby, List<Outline> preview) {
        this.preview = holding ? List.copyOf(preview) : List.of();
        this.holding = holding;
        this.nearby = holding ? List.copyOf(nearby) : List.of();
    }
    public boolean advance() {
        if (holding) elapsed = 0;
        else if (elapsed++ >= duration) return false;
        return holding || !manual.isEmpty() || !nearby.isEmpty();
    }
    public List<Outline> outlines(int maximum) {
        Map<Shape, Outline> shapes = new LinkedHashMap<>();
        for (Outline outline : manual) shapes.put(new Shape(outline.bounds(), outline.threeDimensional()), outline);
        int manualCount = shapes.size();
        for (Outline outline : preview) {
            if (shapes.size() >= maximum) break;
            shapes.putIfAbsent(new Shape(outline.bounds(), outline.threeDimensional()), outline);
        }
        for (Outline outline : nearby) {
            if (shapes.size() >= maximum) break;
            shapes.putIfAbsent(new Shape(outline.bounds(), outline.threeDimensional()), outline);
        }
        // Explicit selection and conflict displays take precedence where markers overlap.
        List<Outline> result = new ArrayList<>(shapes.values());
        Collections.rotate(result, -manualCount);
        return List.copyOf(result);
    }
}
