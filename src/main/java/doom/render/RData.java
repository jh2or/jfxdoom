// Port of linuxdoom-1.10/r_data.c and r_data.h.
// Texture composition, flat/sprite lump tables, colormap loading.
//
// Translation notes:
//   * Globals stay as `public static` (CLAUDE.md §2). All array globals the
//     C allocates via Z_Malloc as raw bytes (then casts to typed pointers)
//     are translated to plain Java typed arrays — see deviation 7 in
//     PORT_STATUS.md. Lumps read straight from the WAD still come through
//     W_CacheLumpName / W_ReadLump and are freed via Z_Free, matching C.
//   * `unsigned short` column offsets become `int[]` (always 0..65535).
//   * `texture_t` and `texpatch_t` become inner classes; the C's flexible-
//     array hack (`texpatch_t patches[1];`) becomes a `TexPatch[]` field.
//   * `maptexture_t` is never materialised as a Java class: we parse it
//     directly out of the TEXTURE1/2 lump bytes, which is already the
//     shape the C has to deal with anyway (pointer into a byte blob).
//   * `R_PrecacheLevel` depends on playsim globals (sectors, sides, sprites,
//     demoplayback, thinkercap) that haven't been ported yet. It is stubbed
//     with an explicit "not yet wired" error; the real body will land as
//     part of the playsim integration pass.
package doom.render;

import doom.fixed.Fixed;
import doom.system.ISystem;
import doom.wad.WWad;
import doom.wad.ZZone;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class RData {

    // texpatch_t (r_data.c lines 99–107).
    public static final class TexPatch {
        public int originx;
        public int originy;
        public int patch; // lump number after PNAMES lookup; -1 means missing
    }

    // texture_t (r_data.c lines 113–125).
    public static final class Texture {
        public String name;       // 8-char, uppercase trimmed
        public int width;
        public int height;
        public int patchcount;
        public TexPatch[] patches;
    }

    // r_data.c lines 129–139.
    public static int firstflat;
    public static int lastflat;
    public static int numflats;

    public static int firstpatch;
    public static int lastpatch;
    public static int numpatches;

    public static int firstspritelump;
    public static int lastspritelump;
    public static int numspritelumps;

    public static int numtextures;
    public static Texture[] textures;

    public static int[] texturewidthmask;
    public static int[] textureheight;           // fixed_t
    public static int[] texturecompositesize;
    public static short[][] texturecolumnlump;   // signed, -1 means "composite"
    public static int[][] texturecolumnofs;      // unsigned short, widened
    public static byte[][] texturecomposite;     // lazily built per-texture

    public static int[] flattranslation;         // for global animation
    public static int[] texturetranslation;

    public static int[] spritewidth;             // fixed_t
    public static int[] spriteoffset;            // fixed_t
    public static int[] spritetopoffset;         // fixed_t

    public static byte[] colormaps;

    // R_PrecacheLevel accounting (r_data.c lines 739–741).
    public static int flatmemory;
    public static int texturememory;
    public static int spritememory;

    private RData() {
    }

    // void R_DrawColumnInCache(column_t* patch, byte* cache, int originy, int cacheheight)
    // Clip and draw a single column's posts into the composite cache at `cacheOfs`.
    // The C walks the posts in place via pointer bumps; we pass the raw patch
    // bytes + a starting offset and do the same with an index.
    static void rDrawColumnInCache(byte[] patchData, int patchOfs,
                                   byte[] cache, int cacheOfs,
                                   int originy, int cacheheight) {
        while ((patchData[patchOfs] & 0xFF) != 0xFF) {
            int topdelta = patchData[patchOfs] & 0xFF;
            int length = patchData[patchOfs + 1] & 0xFF;
            int sourceIdx = patchOfs + 3;
            int count = length;
            int position = originy + topdelta;

            if (position < 0) {
                count += position;
                position = 0;
            }

            if (position + count > cacheheight) {
                count = cacheheight - position;
            }

            if (count > 0) {
                System.arraycopy(patchData, sourceIdx,
                        cache, cacheOfs + position, count);
            }

            patchOfs = patchOfs + length + 4;
        }
    }

    // void R_GenerateComposite(int texnum)
    public static void rGenerateComposite(int texnum) throws IOException {
        Texture texture = textures[texnum];

        final int ft = texnum;
        ZZone.UserRef slot = new ZZone.UserRef() {
            public byte[] get() { return texturecomposite[ft]; }
            public void set(byte[] v) { texturecomposite[ft] = v; }
        };
        byte[] block = ZZone.zMalloc(texturecompositesize[texnum],
                ZZone.PU_STATIC, slot);

        short[] collump = texturecolumnlump[texnum];
        int[] colofs = texturecolumnofs[texnum];

        for (int i = 0; i < texture.patchcount; i++) {
            TexPatch patch = texture.patches[i];
            byte[] realpatchLump = WWad.wCacheLumpNum(patch.patch, ZZone.PU_CACHE);
            Patch realpatch = Patch.fromLump(realpatchLump);

            int x1 = patch.originx;
            int x2 = x1 + realpatch.width;

            int x = (x1 < 0) ? 0 : x1;
            if (x2 > texture.width) {
                x2 = texture.width;
            }

            for (; x < x2; x++) {
                // Column covered by a single patch — rendered directly at
                // draw time via R_GetColumn, no composite entry needed.
                if (collump[x] >= 0) {
                    continue;
                }

                int patchcolOfs = realpatch.columnofs[x - x1];
                rDrawColumnInCache(realpatchLump, patchcolOfs,
                        block, colofs[x],
                        patch.originy, texture.height);
            }
        }

        // Now that the texture has been built in column cache,
        //  it is purgable from zone memory.
        ZZone.zChangeTag(block, ZZone.PU_CACHE);
    }

    // void R_GenerateLookup(int texnum)
    public static void rGenerateLookup(int texnum) throws IOException {
        Texture texture = textures[texnum];

        // Composited texture not created yet.
        texturecomposite[texnum] = null;
        texturecompositesize[texnum] = 0;

        short[] collump = texturecolumnlump[texnum];
        int[] colofs = texturecolumnofs[texnum];

        // Count how many patches cover each column (stack-allocated `alloca` in C).
        byte[] patchcount = new byte[texture.width];

        for (int i = 0; i < texture.patchcount; i++) {
            TexPatch patch = texture.patches[i];
            byte[] realpatchLump = WWad.wCacheLumpNum(patch.patch, ZZone.PU_CACHE);
            Patch realpatch = Patch.fromLump(realpatchLump);

            int x1 = patch.originx;
            int x2 = x1 + realpatch.width;

            int x = (x1 < 0) ? 0 : x1;
            if (x2 > texture.width) {
                x2 = texture.width;
            }

            for (; x < x2; x++) {
                patchcount[x]++;
                collump[x] = (short) patch.patch;
                // Store post *data* offset (+3 skips topdelta,length,pad).
                colofs[x] = realpatch.columnofs[x - x1] + 3;
            }
        }

        for (int x = 0; x < texture.width; x++) {
            if (patchcount[x] == 0) {
                System.out.printf("R_GenerateLookup: column without a patch (%s)%n",
                        texture.name);
                return;
            }

            if ((patchcount[x] & 0xFF) > 1) {
                // Column covered by multiple patches — needs composite entry.
                collump[x] = -1;
                colofs[x] = texturecompositesize[texnum];

                if (texturecompositesize[texnum] > 0x10000 - texture.height) {
                    throw ISystem.iError("R_GenerateLookup: texture %d is >64k", texnum);
                }

                texturecompositesize[texnum] += texture.height;
            }
        }
    }

    // byte* R_GetColumn(int tex, int col)
    // Returns the (array, offset) pair packed as a ColumnRef — the renderer
    // reads the column out of either a lump cache or the composite cache.
    public static ColumnRef rGetColumn(int tex, int col) throws IOException {
        col &= texturewidthmask[tex];
        int lump = texturecolumnlump[tex][col];
        int ofs = texturecolumnofs[tex][col];

        if (lump > 0) {
            byte[] data = WWad.wCacheLumpNum(lump, ZZone.PU_CACHE);
            return new ColumnRef(data, ofs);
        }

        if (texturecomposite[tex] == null) {
            rGenerateComposite(tex);
        }

        return new ColumnRef(texturecomposite[tex], ofs);
    }

    // Packs a byte[] base and an offset into it — the Java stand-in for the
    // C `byte*` return from R_GetColumn. Hot callers (r_draw.c) can read the
    // two fields into locals to avoid repeated field loads.
    public static final class ColumnRef {
        public final byte[] data;
        public final int offset;
        public ColumnRef(byte[] data, int offset) {
            this.data = data;
            this.offset = offset;
        }
    }

    // void R_InitTextures(void)
    public static void rInitTextures() throws IOException {
        // Load the patch names from pnames.lmp.
        byte[] names = WWad.wCacheLumpName("PNAMES", ZZone.PU_STATIC);
        ByteBuffer nameBuf = ByteBuffer.wrap(names).order(ByteOrder.LITTLE_ENDIAN);
        int nummappatches = nameBuf.getInt();
        int[] patchlookup = new int[nummappatches];
        byte[] nameBytes = new byte[8];
        for (int i = 0; i < nummappatches; i++) {
            System.arraycopy(names, 4 + i * 8, nameBytes, 0, 8);
            String pname = WWad.trimLumpName(nameBytes);
            patchlookup[i] = WWad.wCheckNumForName(pname);
        }
        ZZone.zFree(names);

        // Load TEXTURE1 (always) and TEXTURE2 (commercial only).
        byte[] maptex1 = WWad.wCacheLumpName("TEXTURE1", ZZone.PU_STATIC);
        ByteBuffer tex1Buf = ByteBuffer.wrap(maptex1).order(ByteOrder.LITTLE_ENDIAN);
        int numtextures1 = tex1Buf.getInt();
        int maxoff = WWad.wLumpLength(WWad.wGetNumForName("TEXTURE1"));

        byte[] maptex2 = null;
        int numtextures2 = 0;
        int maxoff2 = 0;
        if (WWad.wCheckNumForName("TEXTURE2") != -1) {
            maptex2 = WWad.wCacheLumpName("TEXTURE2", ZZone.PU_STATIC);
            numtextures2 = ByteBuffer.wrap(maptex2).order(ByteOrder.LITTLE_ENDIAN).getInt();
            maxoff2 = WWad.wLumpLength(WWad.wGetNumForName("TEXTURE2"));
        }

        numtextures = numtextures1 + numtextures2;

        textures = new Texture[numtextures];
        texturecolumnlump = new short[numtextures][];
        texturecolumnofs = new int[numtextures][];
        texturecomposite = new byte[numtextures][];
        texturecompositesize = new int[numtextures];
        texturewidthmask = new int[numtextures];
        textureheight = new int[numtextures];

        byte[] maptex = maptex1;
        int maxoffCur = maxoff;
        int directoryIdx = 1; // directory = maptex+1 (ints)

        for (int i = 0; i < numtextures; i++, directoryIdx++) {
            if (i == numtextures1) {
                // Start looking in second texture file.
                maptex = maptex2;
                maxoffCur = maxoff2;
                directoryIdx = 1;
            }

            // offset = LONG(*directory);
            ByteBuffer dirBuf = ByteBuffer.wrap(maptex).order(ByteOrder.LITTLE_ENDIAN);
            int offset = dirBuf.getInt(directoryIdx * 4);

            if (offset > maxoffCur) {
                throw ISystem.iError("R_InitTextures: bad texture directory");
            }

            // Parse maptexture_t at (maptex + offset).
            // Layout: name[8], int masked, short width, short height,
            //         int columndirectory (OBSOLETE), short patchcount,
            //         mappatch_t patches[patchcount]  (10 bytes each)
            ByteBuffer mt = ByteBuffer.wrap(maptex).order(ByteOrder.LITTLE_ENDIAN);
            mt.position(offset);
            byte[] texName = new byte[8];
            mt.get(texName);
            mt.getInt();                         // masked (unused)
            int mtWidth = mt.getShort();
            int mtHeight = mt.getShort();
            mt.getInt();                         // columndirectory (OBSOLETE)
            int mtPatchcount = mt.getShort();

            Texture texture = new Texture();
            textures[i] = texture;
            texture.width = mtWidth;
            texture.height = mtHeight;
            texture.patchcount = mtPatchcount;
            texture.name = WWad.trimLumpName(texName);
            texture.patches = new TexPatch[mtPatchcount];

            for (int j = 0; j < mtPatchcount; j++) {
                int originx = mt.getShort();
                int originy = mt.getShort();
                int patchIdx = mt.getShort();
                mt.getShort(); // stepdir (OBSOLETE)
                mt.getShort(); // colormap (OBSOLETE)

                TexPatch patch = new TexPatch();
                patch.originx = originx;
                patch.originy = originy;
                patch.patch = patchlookup[patchIdx];
                if (patch.patch == -1) {
                    throw ISystem.iError("R_InitTextures: Missing patch in texture %s",
                            texture.name);
                }
                texture.patches[j] = patch;
            }

            texturecolumnlump[i] = new short[texture.width];
            texturecolumnofs[i] = new int[texture.width];

            int j = 1;
            while (j * 2 <= texture.width) {
                j <<= 1;
            }
            texturewidthmask[i] = j - 1;
            textureheight[i] = Fixed.fromInt(texture.height);
        }

        ZZone.zFree(maptex1);
        if (maptex2 != null) {
            ZZone.zFree(maptex2);
        }

        // Precalculate whatever possible.
        for (int i = 0; i < numtextures; i++) {
            rGenerateLookup(i);
        }

        // Create translation table for global animation.
        texturetranslation = new int[numtextures + 1];
        for (int i = 0; i < numtextures; i++) {
            texturetranslation[i] = i;
        }
    }

    // void R_InitFlats(void)
    public static void rInitFlats() {
        firstflat = WWad.wGetNumForName("F_START") + 1;
        lastflat = WWad.wGetNumForName("F_END") - 1;
        numflats = lastflat - firstflat + 1;

        flattranslation = new int[numflats + 1];
        for (int i = 0; i < numflats; i++) {
            flattranslation[i] = i;
        }
    }

    // void R_InitSpriteLumps(void)
    public static void rInitSpriteLumps() throws IOException {
        firstspritelump = WWad.wGetNumForName("S_START") + 1;
        lastspritelump = WWad.wGetNumForName("S_END") - 1;

        numspritelumps = lastspritelump - firstspritelump + 1;
        spritewidth = new int[numspritelumps];
        spriteoffset = new int[numspritelumps];
        spritetopoffset = new int[numspritelumps];

        for (int i = 0; i < numspritelumps; i++) {
            byte[] lump = WWad.wCacheLumpNum(firstspritelump + i, ZZone.PU_CACHE);
            Patch patch = Patch.fromLump(lump);
            spritewidth[i] = Fixed.fromInt(patch.width);
            spriteoffset[i] = Fixed.fromInt(patch.leftoffset);
            spritetopoffset[i] = Fixed.fromInt(patch.topoffset);
        }
    }

    // void R_InitColormaps(void)
    public static void rInitColormaps() throws IOException {
        // C rounds the Z_Malloc result up to a 256-byte boundary for fast
        // indexing by `colormap_offset & 0xff`. Java can't do pointer
        // alignment; the indexing code will just use `(baseIdx + i*256) + c`
        // anyway, so a plain lump-sized buffer suffices here.
        int lump = WWad.wGetNumForName("COLORMAP");
        int length = WWad.wLumpLength(lump);
        colormaps = new byte[length];
        WWad.wReadLump(lump, colormaps);
    }

    // void R_InitData(void)
    public static void rInitData() throws IOException {
        rInitTextures();
        System.out.println();
        System.out.print("InitTextures");
        rInitFlats();
        System.out.println();
        System.out.print("InitFlats");
        rInitSpriteLumps();
        System.out.println();
        System.out.print("InitSprites");
        rInitColormaps();
        System.out.println();
        System.out.print("InitColormaps");
    }

    // int R_FlatNumForName(char* name)
    public static int rFlatNumForName(String name) {
        int i = WWad.wCheckNumForName(name);
        if (i == -1) {
            throw ISystem.iError("R_FlatNumForName: %s not found", name);
        }
        return i - firstflat;
    }

    // int R_CheckTextureNumForName(char* name)
    public static int rCheckTextureNumForName(String name) {
        // "NoTexture" marker.
        if (!name.isEmpty() && name.charAt(0) == '-') {
            return 0;
        }
        String upper = name.length() > 8
                ? name.substring(0, 8).toUpperCase()
                : name.toUpperCase();
        for (int i = 0; i < numtextures; i++) {
            if (textures[i].name.equals(upper)) {
                return i;
            }
        }
        return -1;
    }

    // int R_TextureNumForName(char* name)
    public static int rTextureNumForName(String name) {
        int i = rCheckTextureNumForName(name);
        if (i == -1) {
            throw ISystem.iError("R_TextureNumForName: %s not found", name);
        }
        return i;
    }

    // void R_PrecacheLevel(void)
    // Precaches all graphics referenced by the current level's sectors,
    // sidedefs and active sprite thinkers. Depends on playsim globals
    // (sectors, sides, sprites, thinkercap, demoplayback, skytexture) that
    // have not been ported yet — see PORT_STATUS.md deviation 7.
    public static void rPrecacheLevel() {
        throw new UnsupportedOperationException(
                "R_PrecacheLevel: awaits p_setup/p_tick/r_sky port");
    }

    // Test-only: drop all state so tests are independent.
    static void resetForTests() {
        firstflat = lastflat = numflats = 0;
        firstpatch = lastpatch = numpatches = 0;
        firstspritelump = lastspritelump = numspritelumps = 0;
        numtextures = 0;
        textures = null;
        texturewidthmask = null;
        textureheight = null;
        texturecompositesize = null;
        texturecolumnlump = null;
        texturecolumnofs = null;
        texturecomposite = null;
        flattranslation = null;
        texturetranslation = null;
        spritewidth = spriteoffset = spritetopoffset = null;
        colormaps = null;
        flatmemory = texturememory = spritememory = 0;
    }
}
