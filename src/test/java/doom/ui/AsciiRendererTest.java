package doom.ui;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AsciiRendererTest {
    @Test void whiteCellDrawsAnAtSignAndBlackCellStaysBlack() {
        AsciiRenderer renderer = new AsciiRenderer(8, 6);
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

    @Test void glyphsRetainColorAndClipToPartialViewportCells() {
        for (int scale = 1; scale <= 4; scale++) {
            int w = 13 * scale, h = 15 * scale;
            AsciiRenderer renderer = new AsciiRenderer(w, h);
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

    @Test void internalResolutionIncreasesSymbolCountInsteadOfEnlargingGlyphs() {
        int[] palette = new int[256];
        palette[0] = 0xffffffff;
        for (int scale = 1; scale <= 4; scale++) {
            int width = 320 * scale, height = 168 * scale;
            AsciiRenderer renderer = new AsciiRenderer(width, height);
            renderer.render(new byte[width * height], palette, 0, 0, width, height);
            int columns = 0, rows = 0;
            for (int x = 0; x < width; x++) {
                if (renderer.pixels[x] == 0xffffffff
                        && (x == 0 || renderer.pixels[x - 1] == 0xff000000)) columns++;
            }
            for (int y = 0; y < height; y++) {
                if (renderer.pixels[y * width] == 0xffffffff
                        && (y == 0 || renderer.pixels[(y - 1) * width] == 0xff000000)) rows++;
            }
            assertEquals(80 * scale, columns, "Higher resolution adds text columns");
            assertEquals(28 * scale, rows, "Higher resolution adds text rows");
        }
    }

    @Test void contrastingCellsPreserveLettersDigitsAndDirectionalStrokes() {
        int[][] shapes = {
                {2, 5, 7, 5, 5}, // A
                {6, 1, 7, 4, 7}, // 2
                {1, 1, 2, 4, 4}, // slash
                {4, 4, 2, 1, 1}  // backslash: same brightness, different direction
        };
        int[] palette = new int[256];
        palette[0] = 0xff000000;
        palette[1] = 0xffffffff;
        AsciiRenderer renderer = new AsciiRenderer(4, 6);
        for (int[] shape : shapes) {
            byte[] source = new byte[24];
            for (int y = 0; y < 5; y++) for (int x = 0; x < 3; x++) {
                if ((shape[y] & (4 >> x)) != 0) source[y * 4 + x] = 1;
            }
            renderer.render(source, palette, 0, 0, 4, 6);
            for (int y = 0; y < 5; y++) for (int x = 0; x < 3; x++) {
                assertEquals((shape[y] & (4 >> x)) != 0,
                        renderer.pixels[y * 4 + x] != 0xff000000,
                        "Preserve strokes rather than selecting by average brightness alone");
            }
            int[] first = renderer.pixels.clone();
            renderer.render(new byte[24], palette, 0, 0, 4, 6);
            renderer.render(source, palette, 0, 0, 4, 6);
            assertArrayEquals(first, renderer.pixels, "Selection must not depend on frame history");
        }
    }

    @Test void smoothBrightnessRampUsesMoreThanTheOriginalNineSymbols() {
        int[] palette = new int[256];
        java.util.Set<String> patterns = new java.util.HashSet<>();
        AsciiRenderer renderer = new AsciiRenderer(4, 6);
        for (int gray = 0; gray < 256; gray++) {
            palette[0] = 0xff000000 | gray << 16 | gray << 8 | gray;
            renderer.render(new byte[24], palette, 0, 0, 4, 6);
            StringBuilder pattern = new StringBuilder();
            for (int pixel : renderer.pixels) pattern.append(pixel == 0xff000000 ? '.' : '#');
            patterns.add(pattern.toString());
        }
        assertTrue(patterns.size() >= 30, "At least 30 distinct glyph shapes across the tone ramp");
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
