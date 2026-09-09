package doom.render;

import doom.system.Doomdef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

class RenderSpriteMaskTest {
    @AfterEach void cleanup() { RenderSpriteMask.disable(); RenderDepth.disable(); }

    @Test void visiblePostsMarkSpritesWhileGapsAndOccludingGeometryRemainCorrect() {
        RenderDepth.disable();
        RenderSpriteMask.enabled = true;
        RenderSpriteMask.beginFrame();
        int stride = Doomdef.RENDER_WIDTH;
        RenderSpriteMask.sprite = true;
        RenderSpriteMask.column(0, 1);
        RenderSpriteMask.column(2 * stride, 2);
        assertTrue(RenderSpriteMask.pixels[0]);
        assertFalse(RenderSpriteMask.pixels[stride], "Transparent gap remains geometry");
        assertTrue(RenderSpriteMask.pixels[2 * stride]);
        assertTrue(RenderSpriteMask.pixels[3 * stride]);
        RenderSpriteMask.sprite = false;
        RenderSpriteMask.column(2 * stride, 1);
        assertFalse(RenderSpriteMask.pixels[2 * stride], "A visible wall replaces sprite provenance");
        RenderSpriteMask.span(3 * stride, 1);
        assertFalse(RenderSpriteMask.pixels[3 * stride]);
        RenderSpriteMask.beginFrame();
        assertFalse(RenderSpriteMask.pixels[0], "No stale sprite trails next frame");
        RenderSpriteMask.disable();
        assertNull(RenderSpriteMask.pixels);
    }
}
