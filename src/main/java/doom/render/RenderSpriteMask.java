package doom.render;

import doom.system.Doomdef;
import java.util.Arrays;

/** Visible sprite-pixel provenance for ASCII presentation, independent of depth fading. */
public final class RenderSpriteMask {
    public static boolean enabled;
    public static boolean[] pixels;
    static boolean sprite;
    private RenderSpriteMask() { }

    public static void beginFrame() {
        if (!enabled) return;
        int size = Doomdef.RENDER_WIDTH * Doomdef.RENDER_HEIGHT;
        if (pixels == null || pixels.length != size) pixels = new boolean[size];
        else Arrays.fill(pixels, false);
        sprite = false;
    }

    public static void disable() { enabled = false; pixels = null; sprite = false; }

    static void column(int start, int count) {
        if (!enabled || pixels == null) return;
        for (int i = 0; i < count; i++) pixels[start + i * Doomdef.RENDER_WIDTH] = sprite;
    }

    static void span(int start, int count) {
        if (!enabled || pixels == null) return;
        Arrays.fill(pixels, start, start + count, false);
    }
}
