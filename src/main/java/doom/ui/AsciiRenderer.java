package doom.ui;

/** Colored bitmap ASCII post-processing of the software world framebuffer. */
final class AsciiRenderer {
    // Original 3x5 bitmap font, with a blank column/row between cells.
    // Shape matching uses a precomputed 15-bit lookup, not a font search per cell.
    private static final String CHARACTERS = " .,:;!?-_=+/\\|()[]<>*#@%0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZiltx";
    private static final int[][] GLYPHS = {
            {0, 0, 0, 0, 0}, // space
            {0, 0, 0, 0, 2}, // .
            {0, 0, 0, 2, 4}, // ,
            {0, 2, 0, 2, 0}, // :
            {0, 2, 0, 2, 4}, // ;
            {2, 2, 2, 0, 2}, // !
            {6, 1, 2, 0, 2}, // ?
            {0, 0, 7, 0, 0}, // -
            {0, 0, 0, 0, 7}, // _
            {0, 7, 0, 7, 0}, // =
            {0, 2, 7, 2, 0}, // +
            {1, 1, 2, 4, 4}, // /
            {4, 4, 2, 1, 1}, // \
            {2, 2, 2, 2, 2}, // |
            {1, 2, 2, 2, 1}, // (
            {4, 2, 2, 2, 4}, // )
            {3, 2, 2, 2, 3}, // [
            {6, 2, 2, 2, 6}, // ]
            {1, 2, 4, 2, 1}, // <
            {4, 2, 1, 2, 4}, // >
            {5, 2, 7, 2, 5}, // *
            {5, 7, 5, 7, 5}, // #
            {7, 5, 7, 4, 7}, // @
            {5, 1, 2, 4, 5}, // %
            {7, 5, 5, 5, 7}, // 0
            {2, 6, 2, 2, 7}, // 1
            {6, 1, 7, 4, 7}, // 2
            {6, 1, 3, 1, 6}, // 3
            {5, 5, 7, 1, 1}, // 4
            {7, 4, 6, 1, 6}, // 5
            {3, 4, 7, 5, 7}, // 6
            {7, 1, 2, 2, 2}, // 7
            {7, 5, 7, 5, 7}, // 8
            {7, 5, 7, 1, 6}, // 9
            {2, 5, 7, 5, 5}, // A
            {6, 5, 6, 5, 6}, // B
            {3, 4, 4, 4, 3}, // C
            {6, 5, 5, 5, 6}, // D
            {7, 4, 6, 4, 7}, // E
            {7, 4, 6, 4, 4}, // F
            {3, 4, 5, 5, 3}, // G
            {5, 5, 7, 5, 5}, // H
            {7, 2, 2, 2, 7}, // I
            {1, 1, 1, 5, 2}, // J
            {5, 5, 6, 5, 5}, // K
            {4, 4, 4, 4, 7}, // L
            {5, 7, 7, 5, 5}, // M
            {5, 7, 7, 7, 5}, // N
            {2, 5, 5, 5, 2}, // O
            {6, 5, 6, 4, 4}, // P
            {2, 5, 5, 7, 3}, // Q
            {6, 5, 6, 5, 5}, // R
            {3, 4, 2, 1, 6}, // S
            {7, 2, 2, 2, 2}, // T
            {5, 5, 5, 5, 7}, // U
            {5, 5, 5, 5, 2}, // V
            {5, 5, 7, 7, 5}, // W
            {5, 5, 2, 5, 5}, // X
            {5, 5, 2, 2, 2}, // Y
            {7, 1, 2, 4, 7}, // Z
            {2, 0, 2, 2, 2}, // i
            {6, 2, 2, 2, 7}, // l
            {2, 7, 2, 2, 3}, // t
            {0, 0, 5, 2, 5} // x
    };
    // Flat regions use a tone ramp; structured cells choose matching strokes.
    private static final String TONES = " .,:;!i-lI+1tYJx/()<>?C24VZEF3A5PHK69G0BDRUQMWN8#@";
    private static final int[] TONE_GLYPHS = buildToneLookup();
    private static final int[] SHAPE_GLYPHS = buildShapeLookup();

    private static int[] buildToneLookup() {
        int[] lookup = new int[256];
        for (int level = 0; level < lookup.length; level++) {
            lookup[level] = CHARACTERS.indexOf(TONES.charAt(level * TONES.length() / 256));
        }
        return lookup;
    }

    private static int[] buildShapeLookup() {
        int[] masks = new int[GLYPHS.length];
        for (int g = 0; g < GLYPHS.length; g++) {
            for (int row : GLYPHS[g]) masks[g] = (masks[g] << 3) | row;
        }
        int[] lookup = new int[1 << 15];
        for (int pattern = 0; pattern < lookup.length; pattern++) {
            int best = 16;
            for (int g = 0; g < masks.length; g++) {
                int difference = Integer.bitCount(pattern ^ masks[g]);
                if (difference < best) {
                    best = difference;
                    lookup[pattern] = g;
                }
            }
        }
        return lookup;
    }
    final int[] pixels;
    private final int width;
    private final int[] samples = new int[15];
    private final int[] sampleCounts = new int[15];
    private final byte[] levels;
    private final int columns;
    private int lastVx = -1, lastVy, lastVw, lastVh;
    private boolean adaptive;

    boolean adaptive() { return adaptive; }
    void setAdaptive(boolean value) {
        adaptive = value;
        java.util.Arrays.fill(levels, (byte) 0);
    }

    AsciiRenderer(int width, int height) {
        this.width = width;
        pixels = new int[width * height];
        columns = (width + 3) / 4;
        levels = new byte[columns * ((height + 5) / 6)];
    }

    // Fixed render-pixel cells in uniform mode; depth mode groups distant cells.
    void render(byte[] source, int[] palette, int vx, int vy, int vw, int vh) {
        render(source, palette, vx, vy, vw, vh, null);
    }

    void render(byte[] source, int[] palette, int vx, int vy, int vw, int vh, int[] depth) {
        int right = vx + vw, bottom = vy + vh;
        if (!adaptive || depth == null) {
            for (int y = vy; y < bottom; y += 6) for (int x = vx; x < right; x += 4) {
                drawCell(source, palette, x, y, Math.min(x + 4, right), Math.min(y + 6, bottom), 1);
            }
            return;
        }
        if (vx != lastVx || vy != lastVy || vw != lastVw || vh != lastVh) {
            java.util.Arrays.fill(levels, (byte) 0);
            lastVx = vx; lastVy = vy; lastVw = vw; lastVh = vh;
        }
        // Nearest depth wins, including one-pixel foreground edges and weapon posts.
        for (int y = vy; y < bottom; y += 6) for (int x = vx; x < right; x += 4) {
            int nearest = Integer.MAX_VALUE;
            for (int yy = y; yy < Math.min(y + 6, bottom); yy++) {
                for (int xx = x; xx < Math.min(x + 4, right); xx++) {
                    nearest = Math.min(nearest, depth[yy * width + xx]);
                }
            }
            int index = ((y - vy) / 6) * columns + (x - vx) / 4;
            int previous = levels[index];
            // Refine immediately; require extra distance before becoming coarser.
            levels[index] = (byte) (nearest < (previous == 0 ? 224 : 192) ? 0
                    : nearest < (previous < 2 ? 896 : 768) ? 1 : 2);
        }
        for (int y = vy; y < bottom; y += 24) for (int x = vx; x < right; x += 16) {
            drawRegion(source, palette, x, y, right, bottom, vx, vy, 4);
        }
    }

    private void drawRegion(byte[] source, int[] palette, int x, int y,
                            int right, int bottom, int vx, int vy, int scale) {
        int xEnd = Math.min(x + 4 * scale, right), yEnd = Math.min(y + 6 * scale, bottom);
        int required = scale == 4 ? 2 : scale == 2 ? 1 : 0;
        boolean split = false;
        for (int yy = y; yy < yEnd; yy += 6) for (int xx = x; xx < xEnd; xx += 4) {
            if (levels[((yy - vy) / 6) * columns + (xx - vx) / 4] < required) split = true;
        }
        if (split && scale > 1) {
            int half = scale / 2;
            for (int yy = y; yy < yEnd; yy += 6 * half) for (int xx = x; xx < xEnd; xx += 4 * half) {
                drawRegion(source, palette, xx, yy, xEnd, yEnd, vx, vy, half);
            }
        } else {
            drawCell(source, palette, x, y, xEnd, yEnd, scale);
        }
    }

    private void drawCell(byte[] source, int[] palette, int cx, int cy, int xEnd, int yEnd, int scale) {
        int red = 0, green = 0, blue = 0;
        int darkest = 255, lightest = 0;
        java.util.Arrays.fill(samples, 0);
        java.util.Arrays.fill(sampleCounts, 0);
        for (int y = cy; y < yEnd; y++) {
            for (int x = cx; x < xEnd; x++) {
                int rgb = palette[source[y * width + x] & 255];
                red += (rgb >>> 16) & 255;
                green += (rgb >>> 8) & 255;
                blue += rgb & 255;
                if ((x - cx) / scale < 3 && (y - cy) / scale < 5) {
                    int value = (77 * ((rgb >>> 16) & 255)
                            + 150 * ((rgb >>> 8) & 255) + 29 * (rgb & 255)) >>> 8;
                    int sample = ((y - cy) / scale) * 3 + (x - cx) / scale;
                    samples[sample] += value;
                    sampleCounts[sample]++;
                }
            }
        }
        for (int i = 0; i < samples.length; i++) {
            if (sampleCounts[i] > 0) samples[i] /= sampleCounts[i];
            darkest = Math.min(darkest, samples[i]);
            lightest = Math.max(lightest, samples[i]);
        }
        int count = (xEnd - cx) * (yEnd - cy);
        red /= count;
        green /= count;
        blue /= count;
        int luminance = (77 * red + 150 * green + 29 * blue) >>> 8;
        // Sparse glyphs already reduce brightness. Lift their ink and
        // density so shadowed Doom rooms do not become nearly black twice.
        int density = (int) Math.sqrt(luminance * 255);
        int glyphIndex;
        if (lightest - darkest >= 48 && xEnd - cx >= 3 * scale && yEnd - cy >= 5 * scale) {
            int threshold = (darkest + lightest) / 2;
            int pattern = 0;
            for (int sample : samples) pattern = (pattern << 1) | (sample > threshold ? 1 : 0);
            glyphIndex = SHAPE_GLYPHS[pattern];
        } else {
            glyphIndex = TONE_GLYPHS[density];
        }
        int[] glyph = GLYPHS[glyphIndex];
        int peak = Math.max(red, Math.max(green, blue));
        if (peak > 0 && peak < 192) {
            red = red * 192 / peak;
            green = green * 192 / peak;
            blue = blue * 192 / peak;
        }
        int ink = 0xff000000 | (red << 16) | (green << 8) | blue;
        for (int y = cy; y < yEnd; y++) {
            int gy = (y - cy) / scale;
            for (int x = cx; x < xEnd; x++) {
                int gx = (x - cx) / scale;
                boolean on = gy < 5 && gx < 3 && (glyph[gy] & (4 >> gx)) != 0;
                pixels[y * width + x] = on ? ink : 0xff000000;
            }
        }
    }
}
