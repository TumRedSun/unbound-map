package net.unbound.maps.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-frame label decluttering, Xaero-style: each label claims its rectangle;
 * a label that would overlap an already placed one is skipped (the marker
 * itself still renders). Waypoints claim first (higher priority), then entity
 * names, so a pile of players in one spot no longer smears into unreadable
 * text. {@link #begin()} must be called once per rendered frame.
 */
public final class LabelPlacer {
    private static final List<int[]> RECTS = new ArrayList<>(64);
    private static final int PADDING = 2;

    private LabelPlacer() {
    }

    /** Clears the claimed rectangles. Call at the start of every frame. */
    public static void begin() {
        RECTS.clear();
    }

    /** Tries to reserve a label rect centered horizontally at cx, top at y. */
    public static boolean tryPlaceCentered(float cx, float y, float width, float height) {
        return tryPlace(cx - width / 2.0F, y, width, height);
    }

    /** Tries to reserve a label rect with the given top-left corner. */
    public static boolean tryPlace(float x, float y, float width, float height) {
        float x0 = x - PADDING;
        float y0 = y - PADDING;
        float x1 = x + width + PADDING;
        float y1 = y + height + PADDING;
        for (int[] rect : RECTS) {
            if (x0 < rect[2] && x1 > rect[0] && y0 < rect[3] && y1 > rect[1]) {
                return false;
            }
        }

        RECTS.add(new int[]{(int)x0, (int)y0, (int)Math.ceil(x1), (int)Math.ceil(y1)});
        return true;
    }

    /** Like {@link #tryPlace(float, float, float, float)} but always succeeds (selected search hit). */
    public static void placeForce(float x, float y, float width, float height) {
        RECTS.add(new int[]{(int)(x - PADDING), (int)(y - PADDING), (int)Math.ceil(x + width + PADDING), (int)Math.ceil(y + height + PADDING)});
    }
}
