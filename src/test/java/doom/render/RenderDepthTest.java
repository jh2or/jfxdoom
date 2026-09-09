package doom.render;

import doom.fixed.Fixed;
import doom.system.Doomdef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

class RenderDepthTest {
    @AfterEach void cleanup() { RenderDepth.disable(); }

    @Test void inverseScaleDepthIsIndependentOfRenderResolutionAndDetail() {
        int oldProjection = RMain.projection, oldDetail = RMain.detailshift, oldScale = RDraw.dcIscale;
        try {
            for (int render = 1; render <= 4; render++) for (int detail = 0; detail <= 1; detail++) {
                int physicalFocal = 160 * render;
                RMain.projection = (physicalFocal >> detail) * Fixed.FRACUNIT;
                RMain.detailshift = detail;
                RDraw.dcIscale = (int) (512L * Fixed.FRACUNIT / physicalFocal);
                assertTrue(Math.abs(512 - RenderDepth.columnDepth()) <= 1);
            }
        } finally { RMain.projection = oldProjection; RMain.detailshift = oldDetail; RDraw.dcIscale = oldScale; }
    }

    @Test void depthWritesFollowVisiblePostsAndWeaponOverwritesSky() {
        RenderDepth.enabled = true;
        RenderDepth.beginFrame();
        int stride = Doomdef.RENDER_WIDTH;
        RenderDepth.spanDepth = 400;
        RenderDepth.span(stride, 8);
        RenderDepth.sky = true;
        RenderDepth.column(0, 3);
        RenderDepth.weapon = true;
        RenderDepth.column(stride, 1);
        assertEquals(Integer.MAX_VALUE, RenderDepth.pixels[0]);
        assertEquals(0, RenderDepth.pixels[stride]);
        assertEquals(400, RenderDepth.pixels[stride + 1]);
        assertEquals(Integer.MAX_VALUE, RenderDepth.pixels[stride * 2]);
        assertEquals(Integer.MAX_VALUE, RenderDepth.pixels[stride + 8]);
        RenderDepth.beginFrame();
        assertEquals(Integer.MAX_VALUE, RenderDepth.pixels[stride]);
        RenderDepth.disable();
        assertNull(RenderDepth.pixels);
    }
}
