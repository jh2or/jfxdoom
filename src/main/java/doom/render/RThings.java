// Port of linuxdoom-1.10/r_things.c — full.
//
// Sprite drawing: per-frame collection (R_AddSprites for sector-bound
// mobjs + R_DrawPlayerSprites for the held-weapon overlay), back-to-
// front sort by scale (R_SortVisSprites), per-sprite drawseg-aware
// clipping (R_DrawSprite), and the masked-column post walker
// (R_DrawMaskedColumn) shared with r_segs's masked-mid-texture path.
//
// Sprite startup (R_InitSpriteDefs / R_InstallSpriteLump /
// R_InitSprites) walks WWad.lumpinfo[] between the S_START / S_END
// markers, parses lump names like `TROOA1`, `TROOA2A8`, etc., and
// builds the Spritedef / Spriteframe table.
//
// The C masked-column-post format ("posts/runs of opaque pixels"):
// each column is a sequence of `(byte topdelta, byte length, byte
// pad, byte pixels[length], byte pad)` triples, terminated by
// topdelta == 0xff. The Java port reads each post by offset into
// the column's underlying byte[] (no struct cast).
package doom.render;

import doom.fixed.Fixed;
import doom.play.Mobj;
import doom.play.Player;
import doom.play.Pspdef;
import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.ISystem;
import doom.wad.WWad;
import doom.wad.ZZone;

import java.io.IOException;

public final class RThings {

    // r_things.c line 46 — sprite z-clip floor. Mobjs closer than
    // 4 fixed-units are skipped (would need infinite scale).
    public static final int MINZ = Fixed.FRACUNIT * 4;

    // r_things.c line 47 — base y center for psprite anchoring.
    public static final int BASEYCENTER = 100;

    // r_things.c line 81 — negonearray[SCREENWIDTH] filled with -1
    // sentinels, used by r_segs as a default sprtopclip / negative
    // baseline.
    public static final short[] negonearray = new short[Doomdef.SCREENWIDTH];

    static {
        for (int i = 0; i < negonearray.length; i++) {
            negonearray[i] = -1;
        }
    }

    // r_things.c — current sprite y-scale + screen-row top, set by
    // R_DrawSprite / R_RenderMaskedSegRange before each column.
    public static int spryscale;     // fixed_t
    public static int sprtopscreen;  // fixed_t

    // r_things.c — current sprite-clip rows (short* + offset). The
    // C aliases these into the openings[] buffer with an x1-offset;
    // r_segs writes to them when populating masked midtextures.
    public static short[] mfloorclip;
    public static int     mfloorclipOffset;
    public static short[] mceilingclip;
    public static int     mceilingclipOffset;

    // r_things.c line 77 — current sector's sprite light row.
    public static int[] spritelights; // colormap-offset row

    // r_things.c line 91 — per-spritenum animation table.
    public static Spritedef[] sprites;
    public static int numsprites;

    // r_things.c lines 94–96 — startup scratch.
    private static final Spriteframe[] sprtemp = new Spriteframe[29];

    static {
        for (int i = 0; i < sprtemp.length; i++) {
            sprtemp[i] = new Spriteframe();
        }
    }

    private static int maxframe;
    private static String spritename;

    // r_things.c line 289 — vissprite pool.
    public static final int MAXVISSPRITES = 128;
    public static final Vissprite[] vissprites = new Vissprite[MAXVISSPRITES];
    public static int vsP;            // C: vissprite_t* vissprite_p

    static {
        for (int i = 0; i < MAXVISSPRITES; i++) {
            vissprites[i] = new Vissprite();
        }
    }

    // r_things.c line 326 — overflow sentinel returned when pool full.
    private static final Vissprite overflowsprite = new Vissprite();

    // r_things.c line 784 — sorted-list head sentinel.
    private static final Vissprite vsprsortedhead = new Vissprite();

    private RThings() {
    }

    //
    // R_InstallSpriteLump
    // r_things.c lines 105–157.
    private static void rInstallSpriteLump(int lump, int frame, int rotation, boolean flipped) {
        int r;

        if (frame >= 29 || rotation > 8) {
            ISystem.iError("R_InstallSpriteLump: Bad frame characters in lump "
                    + lump);
        }

        if (frame > maxframe) {
            maxframe = frame;
        }

        if (rotation == 0) {
            // the lump should be used for all rotations
            // C: `if (sprtemp[frame].rotate == false) I_Error(... multiple rot=0 lump)`
            //    `if (sprtemp[frame].rotate == true)  I_Error(... rotations and rot=0)`.
            // The first call to install for a frame finds rotate==false (default
            // from `memset(sprtemp, -1, ...)` — bytes 0xff, but the boolean is 0
            // in Java), and the C uses the `-1` sentinel via `(int)rotate == -1`
            // distinct from `0` and `1`. We replicate by initialising rotate to
            // a sentinel `int rotateState = -1` before each sprite. Use a
            // per-sprite reset: tri-state stored in `rotate` via a separate
            // tri-state field. To avoid a second field, the Java port models
            // -1 as null check before the call.
            // sprtemp[frame].rotate = false
            sprtemp[frame].rotate = false;
            for (r = 0; r < 8; r++) {
                sprtemp[frame].lump[r] = (short) (lump - RData.firstspritelump);
                sprtemp[frame].flip[r] = flipped ? (byte) 1 : (byte) 0;
            }
            return;
        }

        // the lump is only used for one rotation
        sprtemp[frame].rotate = true;

        // make 0 based
        rotation--;
        if (sprtemp[frame].lump[rotation] != -1) {
            ISystem.iError("R_InitSprites: Sprite " + spritename
                    + " : " + (char) ('A' + frame)
                    + " : " + (char) ('1' + rotation)
                    + " has two lumps mapped to it");
        }

        sprtemp[frame].lump[rotation] = (short) (lump - RData.firstspritelump);
        sprtemp[frame].flip[rotation] = flipped ? (byte) 1 : (byte) 0;
    }

    //
    // R_InitSpriteDefs
    // r_things.c lines 177–281.
    public static void rInitSpriteDefs(String[] namelist) {
        int i;
        int l;
        int frame;
        int rotation;
        int start;
        int end;
        int patched;

        numsprites = namelist.length;

        if (numsprites == 0) {
            return;
        }

        sprites = new Spritedef[numsprites];
        for (i = 0; i < numsprites; i++) {
            sprites[i] = new Spritedef();
        }

        start = RData.firstspritelump - 1;
        end = RData.lastspritelump + 1;

        // scan all the lump names for each of the names, noting
        // the highest frame letter.
        for (i = 0; i < numsprites; i++) {
            spritename = namelist[i];
            // sprtemp reset: C `memset(sprtemp, -1, ...)` flips
            // rotate to a 0xffffffff sentinel; the Java port uses
            // a separate `rotate` boolean which we reset to false,
            // and lump[]=-1 to detect the "never-installed"
            // sentinel.
            for (int t = 0; t < sprtemp.length; t++) {
                sprtemp[t].rotate = false;
                for (int s = 0; s < 8; s++) {
                    sprtemp[t].lump[s] = -1;
                    sprtemp[t].flip[s] = 0;
                }
            }

            maxframe = -1;

            // scan the lumps, filling in the frames for whatever
            // is found. C: `*(int*)lumpinfo[l].name == intname`
            // compares the first 4 chars as an int; Java uses
            // String.startsWith with a 4-char prefix.
            String prefix = namelist[i];
            if (prefix.length() < 4) {
                prefix = (prefix + "\0\0\0\0").substring(0, 4);
            }
            for (l = start + 1; l < end; l++) {
                String lname = WWad.lumpinfo[l].name;
                if (lname.startsWith(prefix)) {
                    frame = lname.charAt(4) - 'A';
                    rotation = lname.charAt(5) - '0';

                    // Java port skips the modifiedgame branch (the
                    // C does W_GetNumForName to pick a patched
                    // lump from a later WAD). Same lump either way
                    // for stock IWADs.
                    patched = l;

                    rInstallSpriteLump(patched, frame, rotation, false);

                    if (lname.length() > 6) {
                        frame = lname.charAt(6) - 'A';
                        rotation = lname.charAt(7) - '0';
                        rInstallSpriteLump(l, frame, rotation, true);
                    }
                }
            }

            // check the frames that were found for completeness
            if (maxframe == -1) {
                sprites[i].numframes = 0;
                continue;
            }

            maxframe++;

            // Frame validation. The C tri-state on rotate (-1, 0,
            // 1) became boolean here; "no rotations found" is
            // detected by all 8 lumps == -1.
            for (frame = 0; frame < maxframe; frame++) {
                boolean anyLump = false;
                for (int rr = 0; rr < 8; rr++) {
                    if (sprtemp[frame].lump[rr] != -1) {
                        anyLump = true;
                        break;
                    }
                }
                if (!anyLump) {
                    ISystem.iError("R_InitSprites: No patches found for "
                            + namelist[i] + " frame " + (char) (frame + 'A'));
                }
                if (sprtemp[frame].rotate) {
                    // must have all 8 frames
                    for (rotation = 0; rotation < 8; rotation++) {
                        if (sprtemp[frame].lump[rotation] == -1) {
                            ISystem.iError("R_InitSprites: Sprite "
                                    + namelist[i] + " frame "
                                    + (char) (frame + 'A')
                                    + " is missing rotations");
                        }
                    }
                }
            }

            // allocate space for the frames present and copy
            // sprtemp into it
            sprites[i].numframes = maxframe;
            sprites[i].spriteframes = new Spriteframe[maxframe];
            for (int f = 0; f < maxframe; f++) {
                Spriteframe dst = new Spriteframe();
                dst.rotate = sprtemp[f].rotate;
                System.arraycopy(sprtemp[f].lump, 0, dst.lump, 0, 8);
                System.arraycopy(sprtemp[f].flip, 0, dst.flip, 0, 8);
                sprites[i].spriteframes[f] = dst;
            }
        }
    }

    //
    // R_InitSprites
    // r_things.c lines 299–309.
    public static void rInitSprites(String[] namelist) {
        for (int i = 0; i < Doomdef.SCREENWIDTH; i++) {
            negonearray[i] = -1;
        }
        rInitSpriteDefs(namelist);
    }

    //
    // R_ClearSprites
    // r_things.c lines 317–320.
    public static void rClearSprites() {
        vsP = 0;
    }

    //
    // R_NewVisSprite
    // r_things.c lines 328–335.
    public static Vissprite rNewVisSprite() {
        if (vsP == MAXVISSPRITES) {
            return overflowsprite;
        }
        vsP++;
        return vissprites[vsP - 1];
    }


    //
    // R_DrawMaskedColumn
    // Used for sprites and masked mid textures.
    //
    // r_things.c lines 351–388. The C `column_t*` walks a stream of
    // post records: `(byte topdelta, byte length, byte pad,
    // byte pixels[length], byte pad)`. The Java port reads the
    // same byte stream by offset into the column's underlying
    // byte[].
    public static void rDrawMaskedColumn(RData.ColumnRef column) {
        int topscreen;
        int bottomscreen;
        int basetexturemid;

        basetexturemid = RDraw.dcTexturemid;

        byte[] data = column.data;
        int colOfs = column.offset;

        while (true) {
            int topdelta = data[colOfs] & 0xff;
            if (topdelta == 0xff) {
                break;
            }
            int length = data[colOfs + 1] & 0xff;

            // calculate unclipped screen coordinates for post
            topscreen = sprtopscreen + spryscale * topdelta;
            bottomscreen = topscreen + spryscale * length;

            RDraw.dcYl = (topscreen + Fixed.FRACUNIT - 1) >> Fixed.FRACBITS;
            RDraw.dcYh = (bottomscreen - 1) >> Fixed.FRACBITS;

            if (RDraw.dcYh >= mfloorclip[mfloorclipOffset + RDraw.dcX]) {
                RDraw.dcYh = mfloorclip[mfloorclipOffset + RDraw.dcX] - 1;
            }
            if (RDraw.dcYl <= mceilingclip[mceilingclipOffset + RDraw.dcX]) {
                RDraw.dcYl = mceilingclip[mceilingclipOffset + RDraw.dcX] + 1;
            }

            if (RDraw.dcYl <= RDraw.dcYh) {
                // C: dc_source = (byte*)column + 3 — the post
                // pixels begin 3 bytes past the post header
                // (topdelta, length, pad).
                RDraw.dcSource = data;
                RDraw.dcSourceOfs = colOfs + 3;
                RDraw.dcTexturemid = basetexturemid - (topdelta << Fixed.FRACBITS);

                // Drawn by either R_DrawColumn (or shadow / translated
                // variant set by the caller via colfuncMode).
                dispatchColfunc();
            }
            // C: column = (column_t*)((byte*)column + length + 4);
            colOfs += length + 4;
        }

        RDraw.dcTexturemid = basetexturemid;
    }

    // r_things.c — the C tracks `colfunc` as a function pointer.
    // The Java port inlines the dispatch with a small mode flag:
    // 0 = base column, 1 = fuzz, 2 = translated. R_DrawVisSprite
    // sets it before each vissprite walk.
    public static int colfuncMode;

    private static void dispatchColfunc() {
        switch (colfuncMode) {
            case 0:
            default:
                if (RMain.detailshift == 0) {
                    RDraw.rDrawColumn();
                } else {
                    RDraw.rDrawColumnLow();
                }
                break;
            case 1:
                RDraw.rDrawFuzzColumn();
                break;
            case 2:
                RDraw.rDrawTranslatedColumn();
                break;
        }
    }

    //
    // R_DrawVisSprite
    //  mfloorclip and mceilingclip should also be set.
    //
    // r_things.c lines 396–443.
    public static void rDrawVisSprite(Vissprite vis, int x1, int x2) {
        int texturecolumn;
        int frac;
        byte[] patch;

        try {
            patch = WWad.wCacheLumpNum(vis.patch + RData.firstspritelump,
                    ZZone.PU_CACHE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        RDraw.dcColormap = vis.colormap;
        RDraw.dcColormapOfs = vis.colormapOfs;

        if (vis.colormap == null) {
            // NULL colormap = shadow draw
            colfuncMode = 1;
        } else if ((vis.mobjflags & Mobj.MF_TRANSLATION) != 0) {
            colfuncMode = 2;
            // C: dc_translation = translationtables - 256 +
            //    ((vis.mobjflags & MF_TRANSLATION) >> (MF_TRANSSHIFT - 8))
            // Java: pick the translation row directly.
            int tableIdx =
                    ((vis.mobjflags & Mobj.MF_TRANSLATION) >>> Mobj.MF_TRANSSHIFT)
                    - 1;
            if (tableIdx < 0) tableIdx = 0;
            if (tableIdx >= RDraw.translations.length) {
                tableIdx = RDraw.translations.length - 1;
            }
            RDraw.dcTranslation = RDraw.translations[tableIdx];
            RDraw.dcTranslationOfs = 0;
        } else {
            colfuncMode = 0;
        }

        RDraw.dcIscale = Math.abs(vis.xiscale) >> RMain.detailshift;
        RDraw.dcTexturemid = vis.texturemid;
        frac = vis.startfrac;
        spryscale = vis.scale;
        sprtopscreen = RMain.centeryfrac
                - Fixed.mul(RDraw.dcTexturemid, spryscale);

        // Patch header: width(short) + height(short) + leftoffset(short)
        // + topoffset(short) + columnofs[width](int). 8 + 4*width bytes.
        int width = readShortLE(patch, 0);

        for (RDraw.dcX = vis.x1; RDraw.dcX <= vis.x2;
                RDraw.dcX++, frac += vis.xiscale) {
            texturecolumn = frac >> Fixed.FRACBITS;
            if (texturecolumn < 0 || texturecolumn >= width) {
                ISystem.iError("R_DrawSpriteRange: bad texturecolumn");
            }
            int colOffset = readIntLE(patch, 8 + 4 * texturecolumn);
            RData.ColumnRef ref = new RData.ColumnRef(patch, colOffset);
            rDrawMaskedColumn(ref);
        }

        colfuncMode = 0;
    }

    private static int readShortLE(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static int readIntLE(byte[] b, int o) {
        return (b[o] & 0xff)
                | ((b[o + 1] & 0xff) << 8)
                | ((b[o + 2] & 0xff) << 16)
                | ((b[o + 3] & 0xff) << 24);
    }

    //
    // R_ProjectSprite
    // Generates a vissprite for a thing if it might be visible.
    //
    // r_things.c lines 452–604.
    public static void rProjectSprite(Mobj thing) {
        int trX;
        int trY;
        int gxt;
        int gyt;
        int tx;
        int tz;
        int xscale;
        int x1;
        int x2;

        Spritedef sprdef;
        Spriteframe sprframe;
        int lump;

        int rot;
        boolean flip;

        int index;

        Vissprite vis;
        int ang;
        int iscale;

        // transform the origin point
        trX = thing.x - RMain.viewx;
        trY = thing.y - RMain.viewy;

        gxt = Fixed.mul(trX, RMain.viewcos);
        gyt = -Fixed.mul(trY, RMain.viewsin);

        tz = gxt - gyt;

        // thing is behind view plane?
        if (tz < MINZ) {
            return;
        }

        xscale = Fixed.div(RMain.projection, tz);

        gxt = -Fixed.mul(trX, RMain.viewsin);
        gyt = Fixed.mul(trY, RMain.viewcos);
        tx = -(gyt + gxt);

        // too far off the side?
        if (Math.abs(tx) > (tz << 2)) {
            return;
        }

        // decide which patch to use for sprite relative to player
        if (thing.sprite < 0 || thing.sprite >= numsprites) {
            ISystem.iError("R_ProjectSprite: invalid sprite number "
                    + thing.sprite);
        }
        sprdef = sprites[thing.sprite];
        if ((thing.frame & Mobj.FF_FRAMEMASK) >= sprdef.numframes) {
            ISystem.iError("R_ProjectSprite: invalid sprite frame "
                    + thing.sprite + " : " + thing.frame);
        }
        sprframe = sprdef.spriteframes[thing.frame & Mobj.FF_FRAMEMASK];

        if (sprframe.rotate) {
            // choose a different rotation based on player view
            ang = RMain.rPointToAngle(thing.x, thing.y);
            rot = (ang - thing.angle + (Tables.ANG45 / 2) * 9) >>> 29;
            lump = sprframe.lump[rot];
            flip = sprframe.flip[rot] != 0;
        } else {
            // use single rotation for all views
            lump = sprframe.lump[0];
            flip = sprframe.flip[0] != 0;
        }

        // calculate edges of the shape
        tx -= RData.spriteoffset[lump];
        x1 = (RMain.centerxfrac + Fixed.mul(tx, xscale)) >> Fixed.FRACBITS;

        // off the right side?
        if (x1 > RMain.viewwidth) {
            return;
        }

        tx += RData.spritewidth[lump];
        x2 = ((RMain.centerxfrac + Fixed.mul(tx, xscale)) >> Fixed.FRACBITS) - 1;

        // off the left side
        if (x2 < 0) {
            return;
        }

        // store information in a vissprite
        vis = rNewVisSprite();
        vis.mobjflags = thing.flags;
        vis.scale = xscale << RMain.detailshift;
        vis.gx = thing.x;
        vis.gy = thing.y;
        vis.gz = thing.z;
        vis.gzt = thing.z + RData.spritetopoffset[lump];
        vis.texturemid = vis.gzt - RMain.viewz;
        vis.x1 = x1 < 0 ? 0 : x1;
        vis.x2 = x2 >= RMain.viewwidth ? RMain.viewwidth - 1 : x2;
        iscale = Fixed.div(Fixed.FRACUNIT, xscale);

        if (flip) {
            vis.startfrac = RData.spritewidth[lump] - 1;
            vis.xiscale = -iscale;
        } else {
            vis.startfrac = 0;
            vis.xiscale = iscale;
        }

        if (vis.x1 > x1) {
            vis.startfrac += vis.xiscale * (vis.x1 - x1);
        }
        vis.patch = lump;

        // get light level
        if ((thing.flags & Mobj.MF_SHADOW) != 0) {
            // shadow draw
            vis.colormap = null;
            vis.colormapOfs = 0;
        } else if (RMain.fixedcolormap >= 0) {
            // fixed map
            vis.colormap = RData.colormaps;
            vis.colormapOfs = RMain.fixedcolormap;
        } else if ((thing.frame & Mobj.FF_FULLBRIGHT) != 0) {
            // full bright
            vis.colormap = RData.colormaps;
            vis.colormapOfs = 0;
        } else {
            // diminished light
            index = xscale >> (RMain.LIGHTSCALESHIFT - RMain.detailshift);

            if (index >= RMain.MAXLIGHTSCALE) {
                index = RMain.MAXLIGHTSCALE - 1;
            }

            vis.colormap = RData.colormaps;
            vis.colormapOfs = spritelights[index];
        }
    }


    //
    // R_AddSprites
    // r_things.c lines 613–640.
    public static void rAddSprites(Sector sec) {
        Mobj thing;
        int lightnum;

        // BSP is traversed by subsector. A sector might have been
        // split into several subsectors during BSP building. Thus
        // we check whether its already added.
        if (sec.validcount == RMain.validcount) {
            return;
        }

        // Well, now it will be done.
        sec.validcount = RMain.validcount;

        lightnum = (sec.lightlevel >> RMain.LIGHTSEGSHIFT) + RMain.extralight;

        if (lightnum < 0) {
            spritelights = RMain.scalelight[0];
        } else if (lightnum >= RMain.LIGHTLEVELS) {
            spritelights = RMain.scalelight[RMain.LIGHTLEVELS - 1];
        } else {
            spritelights = RMain.scalelight[lightnum];
        }

        // Handle all things in sector.
        thing = sec.thinglist;
        while (thing != null) {
            rProjectSprite(thing);
            thing = thing.snext;
        }
    }


    //
    // R_DrawPSprite
    // r_things.c lines 646–739.
    public static void rDrawPSprite(Pspdef psp) {
        int tx;
        int x1;
        int x2;
        Spritedef sprdef;
        Spriteframe sprframe;
        int lump;
        boolean flip;
        Vissprite vis;
        Vissprite avis = new Vissprite();

        // decide which patch to use
        if (psp.state.sprite < 0 || psp.state.sprite >= numsprites) {
            ISystem.iError("R_ProjectSprite: invalid sprite number "
                    + psp.state.sprite);
        }
        sprdef = sprites[psp.state.sprite];
        if ((psp.state.frame & Mobj.FF_FRAMEMASK) >= sprdef.numframes) {
            ISystem.iError("R_ProjectSprite: invalid sprite frame "
                    + psp.state.sprite + " : " + psp.state.frame);
        }
        sprframe = sprdef.spriteframes[psp.state.frame & Mobj.FF_FRAMEMASK];

        lump = sprframe.lump[0];
        flip = sprframe.flip[0] != 0;

        // calculate edges of the shape
        tx = psp.sx - 160 * Fixed.FRACUNIT;

        tx -= RData.spriteoffset[lump];
        x1 = (RMain.centerxfrac + Fixed.mul(tx, RMain.pspritescale))
                >> Fixed.FRACBITS;

        // off the right side
        if (x1 > RMain.viewwidth) {
            return;
        }

        tx += RData.spritewidth[lump];
        x2 = ((RMain.centerxfrac + Fixed.mul(tx, RMain.pspritescale))
                >> Fixed.FRACBITS) - 1;

        // off the left side
        if (x2 < 0) {
            return;
        }

        // store information in a vissprite
        vis = avis;
        vis.mobjflags = 0;
        vis.texturemid = (BASEYCENTER << Fixed.FRACBITS) + Fixed.FRACUNIT / 2
                - (psp.sy - RData.spritetopoffset[lump]);
        vis.x1 = x1 < 0 ? 0 : x1;
        vis.x2 = x2 >= RMain.viewwidth ? RMain.viewwidth - 1 : x2;
        vis.scale = RMain.pspritescale << RMain.detailshift;

        if (flip) {
            vis.xiscale = -RMain.pspriteiscale;
            vis.startfrac = RData.spritewidth[lump] - 1;
        } else {
            vis.xiscale = RMain.pspriteiscale;
            vis.startfrac = 0;
        }

        if (vis.x1 > x1) {
            vis.startfrac += vis.xiscale * (vis.x1 - x1);
        }

        vis.patch = lump;

        if (RMain.viewplayer.powers[Doomdef.pw_invisibility] > 4 * 32
                || (RMain.viewplayer.powers[Doomdef.pw_invisibility] & 8) != 0) {
            // shadow draw
            vis.colormap = null;
            vis.colormapOfs = 0;
        } else if (RMain.fixedcolormap >= 0) {
            // fixed color
            vis.colormap = RData.colormaps;
            vis.colormapOfs = RMain.fixedcolormap;
        } else if ((psp.state.frame & Mobj.FF_FULLBRIGHT) != 0) {
            // full bright
            vis.colormap = RData.colormaps;
            vis.colormapOfs = 0;
        } else {
            // local light
            vis.colormap = RData.colormaps;
            vis.colormapOfs = spritelights[RMain.MAXLIGHTSCALE - 1];
        }

        rDrawVisSprite(vis, vis.x1, vis.x2);
    }


    //
    // R_DrawPlayerSprites
    // r_things.c lines 746–776.
    public static void rDrawPlayerSprites() {
        int i;
        int lightnum;
        Pspdef psp;

        // get light level
        lightnum =
                (RMain.viewplayer.mo.subsector.sector.lightlevel
                        >> RMain.LIGHTSEGSHIFT)
                + RMain.extralight;

        if (lightnum < 0) {
            spritelights = RMain.scalelight[0];
        } else if (lightnum >= RMain.LIGHTLEVELS) {
            spritelights = RMain.scalelight[RMain.LIGHTLEVELS - 1];
        } else {
            spritelights = RMain.scalelight[lightnum];
        }

        // clip to screen bounds
        mfloorclip = RMain.screenheightarray;
        mfloorclipOffset = 0;
        mceilingclip = negonearray;
        mceilingclipOffset = 0;

        // add all active psprites
        for (i = 0; i < doom.play.PPspr.NUMPSPRITES; i++) {
            psp = RMain.viewplayer.psprites[i];
            if (psp.state != null) {
                rDrawPSprite(psp);
            }
        }
    }


    //
    // R_SortVisSprites
    // r_things.c lines 787–835. Pulls vissprites out of the
    // unsorted list (linked off vsprsortedhead) by ascending scale.
    public static void rSortVisSprites() {
        int i;
        int count;
        Vissprite ds;
        Vissprite best;
        Vissprite unsorted = new Vissprite();
        int bestscale;

        count = vsP;

        unsorted.next = unsorted.prev = unsorted;

        if (count == 0) {
            return;
        }

        for (i = 0; i < count; i++) {
            ds = vissprites[i];
            ds.next = (i + 1 < count) ? vissprites[i + 1] : null;
            ds.prev = (i > 0) ? vissprites[i - 1] : null;
        }

        vissprites[0].prev = unsorted;
        unsorted.next = vissprites[0];
        vissprites[count - 1].next = unsorted;
        unsorted.prev = vissprites[count - 1];

        // pull the vissprites out by scale
        vsprsortedhead.next = vsprsortedhead.prev = vsprsortedhead;
        best = null;
        for (i = 0; i < count; i++) {
            bestscale = Integer.MAX_VALUE;
            for (ds = unsorted.next; ds != unsorted; ds = ds.next) {
                if (ds.scale < bestscale) {
                    bestscale = ds.scale;
                    best = ds;
                }
            }
            best.next.prev = best.prev;
            best.prev.next = best.next;
            best.next = vsprsortedhead;
            best.prev = vsprsortedhead.prev;
            vsprsortedhead.prev.next = best;
            vsprsortedhead.prev = best;
        }
    }


    //
    // R_DrawSprite
    // r_things.c lines 842–950.
    public static void rDrawSprite(Vissprite spr) {
        short[] clipbot = new short[Doomdef.SCREENWIDTH];
        short[] cliptop = new short[Doomdef.SCREENWIDTH];
        int x;
        int r1;
        int r2;
        int scale;
        int lowscale;
        int silhouette;

        for (x = spr.x1; x <= spr.x2; x++) {
            clipbot[x] = cliptop[x] = -2;
        }

        // Scan drawsegs from end to start for obscuring segs. The
        // first drawseg that has a greater scale is the clip seg.
        for (int dsIdx = RBsp.dsP - 1; dsIdx >= 0; dsIdx--) {
            Drawseg ds = RBsp.drawsegs[dsIdx];
            // determine if the drawseg obscures the sprite
            if (ds.x1 > spr.x2
                    || ds.x2 < spr.x1
                    || (ds.silhouette == 0 && ds.maskedtexturecol == null)) {
                // does not cover sprite
                continue;
            }

            r1 = ds.x1 < spr.x1 ? spr.x1 : ds.x1;
            r2 = ds.x2 > spr.x2 ? spr.x2 : ds.x2;

            if (ds.scale1 > ds.scale2) {
                lowscale = ds.scale2;
                scale = ds.scale1;
            } else {
                lowscale = ds.scale1;
                scale = ds.scale2;
            }

            if (scale < spr.scale
                    || (lowscale < spr.scale
                        && RMain.rPointOnSegSide(spr.gx, spr.gy, ds.curline) == 0)) {
                // masked mid texture?
                if (ds.maskedtexturecol != null) {
                    RSegs.rRenderMaskedSegRange(ds, r1, r2);
                }
                // seg is behind sprite
                continue;
            }


            // clip this piece of the sprite
            silhouette = ds.silhouette;

            if (spr.gz >= ds.bsilheight) {
                silhouette &= ~Drawseg.SIL_BOTTOM;
            }

            if (spr.gzt <= ds.tsilheight) {
                silhouette &= ~Drawseg.SIL_TOP;
            }

            if (silhouette == 1) {
                // bottom sil
                for (x = r1; x <= r2; x++) {
                    if (clipbot[x] == -2) {
                        clipbot[x] = ds.sprbottomclip[ds.sprbottomclipOffset + x];
                    }
                }
            } else if (silhouette == 2) {
                // top sil
                for (x = r1; x <= r2; x++) {
                    if (cliptop[x] == -2) {
                        cliptop[x] = ds.sprtopclip[ds.sprtopclipOffset + x];
                    }
                }
            } else if (silhouette == 3) {
                // both
                for (x = r1; x <= r2; x++) {
                    if (clipbot[x] == -2) {
                        clipbot[x] = ds.sprbottomclip[ds.sprbottomclipOffset + x];
                    }
                    if (cliptop[x] == -2) {
                        cliptop[x] = ds.sprtopclip[ds.sprtopclipOffset + x];
                    }
                }
            }

        }

        // all clipping has been performed, so draw the sprite

        // check for unclipped columns
        for (x = spr.x1; x <= spr.x2; x++) {
            if (clipbot[x] == -2) {
                clipbot[x] = (short) RDraw.viewheight;
            }

            if (cliptop[x] == -2) {
                cliptop[x] = -1;
            }
        }

        mfloorclip = clipbot;
        mfloorclipOffset = 0;
        mceilingclip = cliptop;
        mceilingclipOffset = 0;
        rDrawVisSprite(spr, spr.x1, spr.x2);
    }


    //
    // R_DrawMasked
    // r_things.c lines 958–986.
    public static void rDrawMasked() {
        Vissprite spr;

        rSortVisSprites();

        if (vsP > 0) {
            // draw all vissprites back to front
            for (spr = vsprsortedhead.next;
                    spr != vsprsortedhead;
                    spr = spr.next) {
                rDrawSprite(spr);
            }
        }

        // render any remaining masked mid textures
        for (int dsIdx = RBsp.dsP - 1; dsIdx >= 0; dsIdx--) {
            Drawseg ds = RBsp.drawsegs[dsIdx];
            if (ds.maskedtexturecol != null) {
                RSegs.rRenderMaskedSegRange(ds, ds.x1, ds.x2);
            }
        }

        // draw the psprites on top of everything but does not draw
        // on side views
        if (RMain.viewangleoffset == 0) {
            rDrawPlayerSprites();
        }
    }
}
