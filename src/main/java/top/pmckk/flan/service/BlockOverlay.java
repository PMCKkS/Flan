package top.pmckk.flan.service;

import java.util.*;
import java.util.function.*;

/** Tracks client changes only. The restore callback reads the current world state. */
public final class BlockOverlay<T> {
    private final BiConsumer<BorderGeometry.Position, T> display;
    private final Consumer<BorderGeometry.Position> restore;
    private Map<BorderGeometry.Position, T> shown = Map.of();

    public BlockOverlay(BiConsumer<BorderGeometry.Position, T> display, Consumer<BorderGeometry.Position> restore) {
        this.display = display; this.restore = restore;
    }
    public void update(Map<BorderGeometry.Position, T> next) { update(next, false); }
    public void update(Map<BorderGeometry.Position, T> next, boolean refresh) {
        for (var position : shown.keySet()) if (!next.containsKey(position)) restore.accept(position);
        next.forEach((position, value) -> {
            if (refresh || !Objects.equals(shown.get(position), value)) display.accept(position, value);
        });
        shown = Map.copyOf(next);
    }
    public void clear() {
        shown.keySet().forEach(restore); shown = Map.of();
    }
}
