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
                int darkest = 255, lightest = 0;
                for (int y = cy; y < yEnd; y++) {
                    for (int x = cx; x < xEnd; x++) {
                        int rgb = palette[source[y * width + x] & 255];
                        red += (rgb >>> 16) & 255;
                        green += (rgb >>> 8) & 255;
                        blue += rgb & 255;
                        if (x - cx < 3 && y - cy < 5) {
                            int value = (77 * ((rgb >>> 16) & 255)
                                    + 150 * ((rgb >>> 8) & 255) + 29 * (rgb & 255)) >>> 8;
                            samples[(y - cy) * 3 + x - cx] = value;
                            darkest = Math.min(darkest, value);
                            lightest = Math.max(lightest, value);
                        }
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
                int glyphIndex;
                if (lightest - darkest >= 48 && xEnd - cx >= 3 && yEnd - cy >= 5) {
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
