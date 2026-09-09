package doom.render;

import doom.system.Doomdef;
import java.util.Arrays;

/** Optional presentation-only forward depth, in map units, for visible pixels. */
public final class RenderDepth {
    public static boolean enabled;
    public static int[] pixels;
    static boolean sky, weapon;
    static int spanDepth;
    private RenderDepth() { }

    public static void beginFrame() {
        if (!enabled) return;
        int size = Doomdef.RENDER_WIDTH * Doomdef.RENDER_HEIGHT;
        if (pixels == null || pixels.length != size) pixels = new int[size];
        Arrays.fill(pixels, Integer.MAX_VALUE);
        sky = weapon = false;
    }

    public static void disable() { enabled = false; pixels = null; sky = weapon = false; }

    static int columnDepth() {
        if (weapon) return 0;
        if (sky) return Integer.MAX_VALUE;
        // Inverse vertical texture scale times physical focal length.
        long focal = (long) RMain.projection << RMain.detailshift;
        return (int) Math.min(Integer.MAX_VALUE,
                ((RDraw.dcIscale & 0xffffffffL) * focal) >>> 32);
    }

    static void column(int start, int count) {
        if (!enabled || pixels == null) return;
        int depth = columnDepth();
        for (int i = 0; i < count; i++) pixels[start + i * Doomdef.RENDER_WIDTH] = depth;
    }

    static void span(int start, int count) {
        if (!enabled || pixels == null) return;
        Arrays.fill(pixels, start, start + count, spanDepth);
    }
}
