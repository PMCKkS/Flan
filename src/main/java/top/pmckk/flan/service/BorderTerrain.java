package top.pmckk.flan.service;

import java.util.*;
import java.util.function.IntPredicate;

public final class BorderTerrain {
    private BorderTerrain() {}

    /** Find the nearby floor, keeping cave floors and adding a marker at the liquid surface. */
    public static List<Integer> heights(int minimum, int maximum, int start, IntPredicate solid, IntPredicate liquid) {
        int y = Math.clamp(start, minimum, maximum);
        Set<Integer> result = new LinkedHashSet<>();
        if (solid.test(y)) {
            while (y < maximum && solid.test(y + 1)) y++;
            result.add(y);
            if (y < maximum && liquid.test(y + 1)) {
                y++;
                while (y < maximum && liquid.test(y + 1)) y++;
                result.add(y);
            }
        } else {
            int liquidTop = liquid.test(y) ? y : minimum - 1;
            if (liquidTop >= minimum) while (liquidTop < maximum && liquid.test(liquidTop + 1)) liquidTop++;
            while (y >= minimum && !solid.test(y)) {
                if (liquidTop < minimum && liquid.test(y)) liquidTop = y;
                y--;
            }
            if (y >= minimum) result.add(y);
            if (liquidTop >= minimum) result.add(liquidTop);
        }
        return List.copyOf(result);
    }
}
