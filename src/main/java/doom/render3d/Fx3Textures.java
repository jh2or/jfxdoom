// Texture/flat decoding for the experimental JavaFX scene-graph
// renderer: Doom's column/post composite textures and raw 64×64
// flats become PhongMaterial diffuse maps.
//
// Two JavaFX limits shape this class (see 3D_OVERHAUL.md):
//
//   * No texture wrap mode — UVs clamp at [0,1]. So each image is
//     the texture pre-tiled ("baked") to cover at least BAKE_UNITS
//     map units per axis, and Fx3LevelMesh splits geometry on the
//     matching grid so every quad's UVs stay inside [0,1]. The grid
//     pitch is per-texture: pitchU/pitchV = texture size × tile
//     count, queried via wallPitchU/V and FLAT_PITCH.
//
//   * No filter control — sampling is always bilinear. Decoded
//     pixels are pre-upscaled ×UPSCALE nearest-neighbour so the
//     interpolation happens between duplicated texels and the look
//     stays reasonably crisp.
//
// Lighting: Doom shades by remapping palette indices through
// COLORMAP, which is non-linear in RGB — so tinting is done the
// authentic way: pixels are passed through colormap level
// (15 − lightnum) × 2 before palette lookup, one cached image per
// (texture, lightnum). lightnum is the software renderer's 16-level
// light index (lightlevel >> 4, ± fake contrast).
//
// Animation: materials are cached per *base* texture/flat index and
// their diffuse map is chosen through RData.texturetranslation /
// flattranslation — refreshTranslations() re-resolves them and swaps
// the Image on materials whose translated target moved (nukage,
// blinking tech walls, switches after P_ChangeSwitchTexture rebuild).
package doom.render3d;

import doom.render.Patch;
import doom.render.RData;
import doom.wad.WWad;
import doom.wad.ZZone;

import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class Fx3Textures {

    // Target coverage of one baked image, in map units. Textures
    // larger than this get a single tile (pitch = texture size).
    static final int BAKE_UNITS = 128;

    // Nearest-neighbour pre-upscale factor.
    static final int UPSCALE = 2;

    // Flats are 64×64 and world-aligned: 2×2 tiles = 128-unit pitch.
    static final int FLAT_PITCH = BAKE_UNITS;

    public static final int KIND_WALL    = 0;
    public static final int KIND_FLAT    = 1;
    public static final int KIND_SKY     = 2;
    public static final int KIND_SPRITE  = 3; // patch lump, alpha
    public static final int KIND_SPRITEF = 4; // horizontally flipped
    public static final int KIND_MASKED  = 5; // wall texture with alpha

    private Fx3Textures() {
    }

    // (kind, translated num, lightnum) → baked image.
    private static final Map<Integer, Image> images = new HashMap<>();

    // (kind, base num, lightnum) → material + which translated index
    // its map currently shows.
    private static final Map<Integer, MatEntry> materials = new HashMap<>();

    private static final class MatEntry {
        final PhongMaterial material;
        final int kind;
        final int baseNum;
        final int lightnum;
        int shownNum;

        MatEntry(PhongMaterial material, int kind, int baseNum, int lightnum,
                 int shownNum) {
            this.material = material;
            this.kind = kind;
            this.baseNum = baseNum;
            this.lightnum = lightnum;
            this.shownNum = shownNum;
        }
    }

    private static byte[] playpal;

    public static void reset() {
        images.clear();
        materials.clear();
        spriteHeights.clear();
        playpal = null;
    }

    private static int key(int kind, int num, int lightnum) {
        return (kind << 24) | (num << 5) | lightnum;
    }

    // ---- pitch queries (map units covered by one baked image) ----

    static int wallPitchU(int texnum) {
        int w = RData.textures[texnum].width;
        return w * Math.max(1, (BAKE_UNITS + w - 1) / w);
    }

    static int wallPitchV(int texnum) {
        int h = RData.textures[texnum].height;
        return h * Math.max(1, (BAKE_UNITS + h - 1) / h);
    }

    static int textureHeight(int texnum) {
        return RData.textures[texnum].height;
    }

    // ---- materials ----

    public static PhongMaterial wallMaterial(int texnum, int lightnum) {
        return material(KIND_WALL, texnum, lightnum);
    }

    public static PhongMaterial flatMaterial(int flatnum, int lightnum) {
        return material(KIND_FLAT, flatnum, lightnum);
    }

    // Sky: always fullbright (the software renderer draws it with
    // colormap 0 regardless of sector light), baked 2× tiled
    // horizontally so Fx3Sky's 128-column segments always land
    // inside [0,1].
    public static PhongMaterial skyMaterial(int texnum) {
        return material(KIND_SKY, texnum, 15);
    }

    // Sprite patch (relative sprite-lump index, as in
    // Spriteframe.lump). Alpha where posts don't cover; flip mirrors
    // in the bake so quads keep constant UVs.
    public static PhongMaterial spriteMaterial(int lump, int lightnum,
                                               boolean flip) {
        return material(flip ? KIND_SPRITEF : KIND_SPRITE, lump, lightnum);
    }

    // Two-sided midtexture: like a wall bake but transparent where
    // posts don't cover, and never tiled vertically (r_segs draws
    // masked textures as a single vertical run).
    public static PhongMaterial maskedMaterial(int texnum, int lightnum) {
        return material(KIND_MASKED, texnum, lightnum);
    }

    // Sprite patch pixel height (patch header), needed for quad
    // geometry; RData only caches width/offsets as fixed_t.
    private static final Map<Integer, Integer> spriteHeights = new HashMap<>();

    public static int spriteHeight(int lump) {
        Integer h = spriteHeights.get(lump);
        if (h == null) {
            try {
                byte[] raw = WWad.wCacheLumpNum(
                        RData.firstspritelump + lump, ZZone.PU_CACHE);
                h = ((raw[4] & 0xff) | ((raw[5] & 0xff) << 8));
            } catch (IOException ex) {
                h = 0;
            }
            spriteHeights.put(lump, h);
        }
        return h;
    }

    private static PhongMaterial material(int kind, int num, int lightnum) {
        int ln = clampLight(lightnum);
        MatEntry e = materials.get(key(kind, num, ln));
        if (e == null) {
            int shown = translate(kind, num);
            PhongMaterial m = new PhongMaterial(Color.WHITE);
            m.setSpecularColor(Color.BLACK);
            m.setDiffuseMap(image(kind, shown, ln));
            e = new MatEntry(m, kind, num, ln, shown);
            materials.put(key(kind, num, ln), e);
        }
        return e.material;
    }

    // Re-resolve animation translations; swap maps whose target
    // changed. Called once per tic by Fx3Renderer.
    public static void refreshTranslations() {
        for (MatEntry e : materials.values()) {
            int shown = translate(e.kind, e.baseNum);
            if (shown != e.shownNum) {
                e.material.setDiffuseMap(image(e.kind, shown, e.lightnum));
                e.shownNum = shown;
            }
        }
    }

    private static int translate(int kind, int num) {
        if (kind == KIND_SKY || kind == KIND_SPRITE || kind == KIND_SPRITEF) {
            return num;
        }
        if (kind == KIND_WALL || kind == KIND_MASKED) {
            if (RData.texturetranslation != null
                    && num >= 0 && num < RData.texturetranslation.length) {
                return RData.texturetranslation[num];
            }
        } else {
            if (RData.flattranslation != null
                    && num >= 0 && num < RData.flattranslation.length) {
                return RData.flattranslation[num];
            }
        }
        return num;
    }

    private static int clampLight(int lightnum) {
        return Math.max(0, Math.min(15, lightnum));
    }

    // ---- images ----

    private static Image image(int kind, int num, int lightnum) {
        Image img = images.get(key(kind, num, lightnum));
        if (img == null) {
            try {
                img = switch (kind) {
                    case KIND_WALL    -> bakeWall(num, lightnum);
                    case KIND_FLAT    -> bakeFlat(num, lightnum);
                    case KIND_SKY     -> bakeSky(num);
                    case KIND_SPRITE  -> bakeSprite(num, lightnum, false);
                    case KIND_SPRITEF -> bakeSprite(num, lightnum, true);
                    default           -> bakeMasked(num, lightnum);
                };
            } catch (IOException ex) {
                // Missing lump — a 1×1 magenta marker beats crashing
                // the view thread.
                WritableImage w = new WritableImage(1, 1);
                w.getPixelWriter().setArgb(0, 0, 0xffff00ff);
                img = w;
            }
            images.put(key(kind, num, lightnum), img);
        }
        return img;
    }

    private static Image bakeWall(int texnum, int lightnum) throws IOException {
        RData.Texture tex = RData.textures[texnum];
        int w = tex.width;
        int h = tex.height;
        int[] idx = decodeTexture(tex, false);
        int tilesU = Math.max(1, (BAKE_UNITS + w - 1) / w);
        int tilesV = Math.max(1, (BAKE_UNITS + h - 1) / h);
        return bake(idx, w, h, tilesU, tilesV, lightnum);
    }

    private static Image bakeSky(int texnum) throws IOException {
        RData.Texture tex = RData.textures[texnum];
        int[] idx = decodeTexture(tex, false);
        // lightnum 15 → colormap 0 (fullbright).
        return bake(idx, tex.width, tex.height, 2, 1, 15);
    }

    // Masked midtexture: transparent holes, tiled horizontally only
    // (masked walls never tile vertically).
    private static Image bakeMasked(int texnum, int lightnum) throws IOException {
        RData.Texture tex = RData.textures[texnum];
        int[] idx = decodeTexture(tex, true);
        int tilesU = Math.max(1, (BAKE_UNITS + tex.width - 1) / tex.width);
        return bake(idx, tex.width, tex.height, tilesU, 1, lightnum);
    }

    private static Image bakeSprite(int lump, int lightnum, boolean flip)
            throws IOException {
        byte[] raw = WWad.wCacheLumpNum(RData.firstspritelump + lump,
                ZZone.PU_CACHE);
        Patch p = Patch.fromLump(raw);
        spriteHeights.put(lump, p.height);
        int[] idx = new int[p.width * p.height];
        java.util.Arrays.fill(idx, -1);
        for (int x = 0; x < p.width; x++) {
            int tx = flip ? p.width - 1 - x : x;
            int ofs = p.columnofs[x];
            while ((p.data[ofs] & 0xff) != 0xff) {
                int topdelta = p.data[ofs] & 0xff;
                int length = p.data[ofs + 1] & 0xff;
                for (int i = 0; i < length; i++) {
                    int ty = topdelta + i;
                    if (ty >= 0 && ty < p.height) {
                        idx[ty * p.width + tx] = p.data[ofs + 3 + i] & 0xff;
                    }
                }
                ofs += length + 4;
            }
        }
        return bake(idx, p.width, p.height, 1, 1, lightnum);
    }

    private static Image bakeFlat(int flatnum, int lightnum) throws IOException {
        byte[] raw = WWad.wCacheLumpNum(RData.firstflat + flatnum, ZZone.PU_CACHE);
        int[] idx = new int[64 * 64];
        for (int i = 0; i < idx.length && i < raw.length; i++) {
            idx[i] = raw[i] & 0xff;
        }
        return bake(idx, 64, 64, FLAT_PITCH / 64, FLAT_PITCH / 64, lightnum);
    }

    // Composite a column/post texture into a full index grid, exactly
    // what R_GenerateComposite produces. keepHoles=false → uncovered
    // texels stay 0 (like the zeroed composite cache); true → −1,
    // which bake() turns into transparent pixels.
    private static int[] decodeTexture(RData.Texture tex, boolean keepHoles)
            throws IOException {
        int w = tex.width;
        int h = tex.height;
        int[] idx = new int[w * h];
        if (keepHoles) {
            java.util.Arrays.fill(idx, -1);
        }
        for (RData.TexPatch tp : tex.patches) {
            if (tp.patch < 0) continue;
            Patch p = Patch.fromLump(WWad.wCacheLumpNum(tp.patch, ZZone.PU_CACHE));
            for (int x = 0; x < p.width; x++) {
                int tx = tp.originx + x;
                if (tx < 0 || tx >= w) continue;
                int ofs = p.columnofs[x];
                while ((p.data[ofs] & 0xff) != 0xff) {
                    int topdelta = p.data[ofs] & 0xff;
                    int length = p.data[ofs + 1] & 0xff;
                    for (int i = 0; i < length; i++) {
                        int ty = tp.originy + topdelta + i;
                        if (ty >= 0 && ty < h) {
                            idx[ty * w + tx] = p.data[ofs + 3 + i] & 0xff;
                        }
                    }
                    ofs += length + 4;
                }
            }
        }
        return idx;
    }

    // Shade through COLORMAP, resolve to ARGB, tile, upscale.
    private static Image bake(int[] idx, int w, int h,
                              int tilesU, int tilesV, int lightnum)
            throws IOException {
        if (playpal == null) {
            playpal = WWad.wCacheLumpName("PLAYPAL", ZZone.PU_CACHE);
        }
        // The software renderer shades startmap = (15 − lightnum) × 4
        // minus a distance/scale term; without per-pixel distance we
        // sit between its near and mid values (×2 − 2), which keeps
        // close-range rooms from reading darker than vanilla.
        int cm = Math.max(0, Math.min(31, (15 - clampLight(lightnum)) * 2 - 2));
        byte[] map = RData.colormaps;

        int[] argb = new int[w * h];
        for (int i = 0; i < argb.length; i++) {
            int pi = idx[i];
            if (pi < 0) {
                // Uncovered texel of a masked texture / sprite.
                continue;
            }
            int shaded = (map != null) ? (map[cm * 256 + pi] & 0xff) : pi;
            int r = playpal[shaded * 3]     & 0xff;
            int g = playpal[shaded * 3 + 1] & 0xff;
            int b = playpal[shaded * 3 + 2] & 0xff;
            argb[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }

        int outW = w * tilesU * UPSCALE;
        int outH = h * tilesV * UPSCALE;
        int[] out = new int[outW * outH];
        for (int y = 0; y < outH; y++) {
            int sy = (y / UPSCALE) % h;
            int rowOff = y * outW;
            int srcRow = sy * w;
            for (int x = 0; x < outW; x++) {
                out[rowOff + x] = argb[srcRow + (x / UPSCALE) % w];
            }
        }
        WritableImage img = new WritableImage(outW, outH);
        img.getPixelWriter().setPixels(0, 0, outW, outH,
                PixelFormat.getIntArgbPreInstance(), out, 0, outW);
        return img;
    }
}
