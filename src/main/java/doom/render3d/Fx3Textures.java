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

    public static final int KIND_WALL = 0;
    public static final int KIND_FLAT = 1;

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
        if (kind == KIND_WALL) {
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
                img = (kind == KIND_WALL)
                        ? bakeWall(num, lightnum)
                        : bakeFlat(num, lightnum);
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
        int[] idx = decodeTexture(tex);
        int tilesU = Math.max(1, (BAKE_UNITS + w - 1) / w);
        int tilesV = Math.max(1, (BAKE_UNITS + h - 1) / h);
        return bake(idx, w, h, tilesU, tilesV, lightnum);
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
    // what R_GenerateComposite produces (uncovered texels → 0, like
    // the zeroed composite cache).
    private static int[] decodeTexture(RData.Texture tex) throws IOException {
        int w = tex.width;
        int h = tex.height;
        int[] idx = new int[w * h];
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
