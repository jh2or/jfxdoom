package doom.ui;

/** Colored bitmap ASCII post-processing of the software world framebuffer. */
final class AsciiRenderer {
    // Original 3x5 bitmap glyphs, with a blank column/row between cells.
    // Increasing brightness selects: space . : - + = * # @
    private static final int[][] GLYPHS = {
            {0, 0, 0, 0, 0}, {0, 0, 0, 0, 2}, {0, 2, 0, 2, 0},
            {0, 0, 7, 0, 0}, {0, 2, 7, 2, 0}, {0, 7, 0, 7, 0},
            {5, 2, 7, 2, 5}, {5, 7, 5, 7, 5}, {7, 5, 7, 4, 7}
    };
    final int[] pixels;
    private final int width;

    AsciiRenderer(int width, int height) {
        this.width = width;
        pixels = new int[width * height];
    }

    // Fixed render-pixel cells: increasing internal resolution adds symbols.
    // Only the world viewport is converted; the shell composites UI afterward.
    void render(byte[] source, int[] palette, int vx, int vy, int vw, int vh) {
        int right = vx + vw;
        int bottom = vy + vh;
        int cellW = 4;
        int cellH = 6;
        for (int cy = vy; cy < bottom; cy += cellH) {
            int yEnd = Math.min(cy + cellH, bottom);
            for (int cx = vx; cx < right; cx += cellW) {
                int xEnd = Math.min(cx + cellW, right);
                int red = 0, green = 0, blue = 0;
                for (int y = cy; y < yEnd; y++) {
                    for (int x = cx; x < xEnd; x++) {
                        int rgb = palette[source[y * width + x] & 255];
                        red += (rgb >>> 16) & 255;
                        green += (rgb >>> 8) & 255;
                        blue += rgb & 255;
                    }
                }
                int count = (xEnd - cx) * (yEnd - cy);
                red /= count;
                green /= count;
                blue /= count;
                int luminance = (77 * red + 150 * green + 29 * blue) >>> 8;
                // Sparse glyphs already reduce brightness. Lift their ink and
                // density so shadowed Doom rooms do not become nearly black twice.
                int density = (int) Math.sqrt(luminance * 255);
                int[] glyph = GLYPHS[density * GLYPHS.length / 256];
                int peak = Math.max(red, Math.max(green, blue));
                if (peak > 0 && peak < 192) {
                    red = red * 192 / peak;
                    green = green * 192 / peak;
                    blue = blue * 192 / peak;
                }
                int ink = 0xff000000 | (red << 16) | (green << 8) | blue;
                for (int y = cy; y < yEnd; y++) {
                    int gy = y - cy;
                    for (int x = cx; x < xEnd; x++) {
                        int gx = x - cx;
                        boolean on = gy < 5 && gx < 3 && (glyph[gy] & (4 >> gx)) != 0;
                        pixels[y * width + x] = on ? ink : 0xff000000;
                    }
                }
            }
        }
    }
}
