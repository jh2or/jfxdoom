package doom.ui;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AsciiRendererTest {
    @Test void whiteCellDrawsAnAtSignAndBlackCellStaysBlack() {
        AsciiRenderer renderer = new AsciiRenderer(8, 6, 1);
        byte[] source = new byte[48];
        int[] palette = new int[256];
        palette[0] = 0xff000000;
        palette[255] = 0xffffffff;
        for (int y = 0; y < 6; y++) Arrays.fill(source, y * 8, y * 8 + 4, (byte) 255);
        byte[] original = source.clone();
        renderer.render(source, palette, 0, 0, 8, 6);
        String[] expected = {"###.....", "#.#.....", "###.....", "#.......", "###.....", "........"};
        for (int y = 0; y < 6; y++) for (int x = 0; x < 8; x++) {
            assertEquals(expected[y].charAt(x) == '#' ? 0xffffffff : 0xff000000,
                    renderer.pixels[y * 8 + x]);
        }
        assertArrayEquals(original, source, "Presentation must not modify the engine framebuffer");
    }

    @Test void scaledGlyphsRetainColorAndClipToPartialViewportCells() {
        for (int scale = 1; scale <= 4; scale++) {
            int w = 13 * scale, h = 15 * scale;
            AsciiRenderer renderer = new AsciiRenderer(w, h, scale);
            byte[] source = new byte[w * h];
            int[] palette = new int[256];
            palette[0] = 0xffff0000;
            Arrays.fill(renderer.pixels, 0x12345678);
            renderer.render(source, palette, scale, scale, 9 * scale, 11 * scale);
            int inkCount = 0;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int pixel = renderer.pixels[y * w + x];
                if (x < scale || x >= 10 * scale || y < scale || y >= 12 * scale) {
                    assertEquals(0x12345678, pixel, "Never overwrite the HUD/border");
                } else {
                    assertTrue(pixel == 0xff000000 || pixel == 0xffff0000);
                    if (pixel == 0xffff0000) inkCount++;
                }
            }
            assertTrue(inkCount > 0);
            palette[0] = 0xff000000;
            renderer.render(source, palette, scale, scale, 9 * scale, 11 * scale);
            assertEquals(0xff000000, renderer.pixels[2 * scale * w + 2 * scale], "No stale glyph pixels");
        }
    }

    @Test void asciiModeUsesOnlyTheSoftwarePath() {
        assertFalse(Launchpad.RendererMode.ASCII.usesFx3());
        assertFalse(Launchpad.RendererMode.ASCII.sideBySide());
        assertTrue(Launchpad.RendererMode.FX3D.usesFx3());
        assertTrue(Launchpad.RendererMode.SBS_H.usesFx3());
    }

    @Test void compositePreservesUiOverAsciiAndBypassesAsciiOutsideTheWorld() throws Exception {
        JfxDoomApp app = new JfxDoomApp();
        var paletteField = JfxDoomApp.class.getDeclaredField("palette");
        paletteField.setAccessible(true);
        int[] palette = (int[]) paletteField.get(app);
        palette[0] = 0xff000000;
        palette[1] = 0xffffffff;
        var composite = JfxDoomApp.class.getDeclaredMethod("compositeSoftware", int[].class,
                byte[].class, byte[].class, boolean.class, int.class, int.class,
                int.class, int.class, int.class, int.class, int.class, int[].class);
        composite.setAccessible(true);
        int[] output = new int[320 * 200];
        int[] ascii = new int[output.length];
        Arrays.fill(ascii, 0xff00ff00);
        byte[] ui = new byte[output.length], view = new byte[output.length];
        ui[0] = 1;
        ui[180 * 320] = 1;
        composite.invoke(app, output, ui, view, true, 0, 0, 320, 168, 320, 200, 1, ascii);
        assertEquals(0xffffffff, output[0], "Menu text overlays ASCII");
        assertEquals(0xff00ff00, output[1], "World uses ASCII");
        assertEquals(0xffffffff, output[180 * 320], "Status bar remains original art");
        composite.invoke(app, output, ui, view, false, 0, 0, 320, 168, 320, 200, 1, ascii);
        assertEquals(0xff000000, output[1], "Automap and non-level screens bypass ASCII");
    }
}
