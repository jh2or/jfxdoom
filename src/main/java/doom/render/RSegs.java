// Port of linuxdoom-1.10/r_segs.c — full.
//
// All wall-tier rendering: solid-wall mid textures, two-sided top /
// bottom step textures, and masked midtextures. R_StoreWallRange
// is the entry point r_bsp.c calls per visible seg-column-range; it
// computes scaling / texture-mid / silhouette / clip-list entries
// and dispatches the per-column render to R_RenderSegLoop.
//
// R_RenderMaskedSegRange is called by r_things.c (deferred) to
// paint each two-sided wall's transparent midtexture from the
// drawseg's masked-column buffer.
//
// The C uses `lastopening` as a moving short* into a shared
// openings[] buffer; r_segs writes sprite-clip rows + masked-texture
// columns there for r_things to read later. Java preserves the same
// shared buffer (RPlane.openings + RPlane.lastopening) but stores
// the per-drawseg slice as `(short[] base, int offset)` per the
// pointer-arithmetic deviation (CLAUDE.md §5).
package doom.render;

import doom.fixed.Fixed;
import doom.system.ISystem;

import java.io.IOException;

public final class RSegs {

    // r_segs.c line 46 — set by R_StoreWallRange when any of the
    // tier textures will need column reads.
    public static boolean segtextured;

    // r_segs.c lines 49–50 — true when the front sector's floor /
    // ceiling visplane needs a fresh strip in this seg's column
    // range (height/lightlevel mismatch with backsector).
    public static boolean markfloor;
    public static boolean markceiling;

    public static boolean maskedtexture;
    public static int toptexture;
    public static int bottomtexture;
    public static int midtexture;

    // r_segs.c line 58 — angle of the wall normal (perpendicular to
    // the seg). Read by R_ScaleFromGlobalAngle.
    public static int rwNormalangle; // angle_t

    // r_segs.c line 60 — angle to the seg's start vertex from
    // viewx/y. The C declares this in r_segs.c but r_bsp.c
    // writes it via the extern declaration in r_state.h. The
    // Java port lives in RBsp where it's actually set; RSegs
    // reads `RBsp.rwAngle1` directly. Keeping a second field
    // here would shadow it (always 0) — see the porting-bug
    // history in PORT_STATUS deviation 39.

    // r_segs.c lines 65–74 — per-seg render scratch.
    public static int rwX;
    public static int rwStopx;
    public static int rwCenterangle;     // angle_t
    public static int rwOffset;          // fixed_t
    public static int rwDistance;        // fixed_t
    public static int rwScale;           // fixed_t
    public static int rwScalestep;       // fixed_t
    public static int rwMidtexturemid;   // fixed_t
    public static int rwToptexturemid;   // fixed_t
    public static int rwBottomtexturemid; // fixed_t

    // r_segs.c lines 76–79 — world-space relative heights.
    public static int worldtop;
    public static int worldbottom;
    public static int worldhigh;
    public static int worldlow;

    // r_segs.c lines 81–90 — per-column screen-row stepping
    // accumulators. pixhigh / pixlow track the top / bottom step
    // texture's row; topfrac / bottomfrac the wall's outer edges.
    public static int pixhigh;       // fixed_t
    public static int pixlow;        // fixed_t
    public static int pixhighstep;   // fixed_t
    public static int pixlowstep;    // fixed_t

    public static int topfrac;       // fixed_t
    public static int topstep;       // fixed_t

    public static int bottomfrac;    // fixed_t
    public static int bottomstep;    // fixed_t

    // r_segs.c line 95 — masked-texture column buffer slice for the
    // current seg. Aliased into RPlane.openings by R_StoreWallRange.
    public static short[] maskedtexturecol;
    public static int     maskedtexturecolOffset;

    // r_segs.c line 203–204 — pixel-fraction precision split across
    // top/bottomfrac.
    public static final int HEIGHTBITS = 12;
    public static final int HEIGHTUNIT = 1 << HEIGHTBITS;

    private RSegs() {
    }

    //
    // R_RenderMaskedSegRange
    //
    // r_segs.c lines 102–190. Called by r_things during the masked
    // pass; reads the drawseg's masked-column buffer and dispatches
    // R_DrawMaskedColumn for each non-sentinel column.
    public static void rRenderMaskedSegRange(Drawseg ds, int x1, int x2) {
        int index;
        int lightnum;
        int texnum;

        // Calculate light table.
        // Use different light tables
        //   for horizontal / vertical / diagonal. Diagonal?
        // OPTIMIZE: get rid of LIGHTSEGSHIFT globally
        RBsp.curline = ds.curline;
        RBsp.frontsector = RBsp.curline.frontsector;
        RBsp.backsector = RBsp.curline.backsector;
        texnum = RData.texturetranslation[RBsp.curline.sidedef.midtexture];

        lightnum = (RBsp.frontsector.lightlevel >> RMain.LIGHTSEGSHIFT)
                + RMain.extralight;

        if (RBsp.curline.v1.y == RBsp.curline.v2.y) {
            lightnum--;
        } else if (RBsp.curline.v1.x == RBsp.curline.v2.x) {
            lightnum++;
        }

        if (lightnum < 0) {
            RMain.walllights = RMain.scalelight[0];
        } else if (lightnum >= RMain.LIGHTLEVELS) {
            RMain.walllights = RMain.scalelight[RMain.LIGHTLEVELS - 1];
        } else {
            RMain.walllights = RMain.scalelight[lightnum];
        }

        maskedtexturecol = ds.maskedtexturecol;
        maskedtexturecolOffset = ds.maskedtexturecolOffset;

        rwScalestep = ds.scalestep;
        RThings.spryscale = ds.scale1 + (x1 - ds.x1) * rwScalestep;
        RThings.mfloorclip   = ds.sprbottomclip;
        RThings.mfloorclipOffset = ds.sprbottomclipOffset;
        RThings.mceilingclip = ds.sprtopclip;
        RThings.mceilingclipOffset = ds.sprtopclipOffset;

        // find positioning
        if ((RBsp.curline.linedef.flags & Line.ML_DONTPEGBOTTOM) != 0) {
            RDraw.dcTexturemid = RBsp.frontsector.floorheight
                    > RBsp.backsector.floorheight
                    ? RBsp.frontsector.floorheight
                    : RBsp.backsector.floorheight;
            RDraw.dcTexturemid = RDraw.dcTexturemid
                    + RData.textureheight[texnum] - RMain.viewz;
        } else {
            RDraw.dcTexturemid = RBsp.frontsector.ceilingheight
                    < RBsp.backsector.ceilingheight
                    ? RBsp.frontsector.ceilingheight
                    : RBsp.backsector.ceilingheight;
            RDraw.dcTexturemid = RDraw.dcTexturemid - RMain.viewz;
        }
        RDraw.dcTexturemid += RBsp.curline.sidedef.rowoffset;

        if (RMain.fixedcolormap >= 0) {
            RDraw.dcColormap = RData.colormaps;
            RDraw.dcColormapOfs = RMain.fixedcolormap;
        }

        // draw the columns
        for (RDraw.dcX = x1; RDraw.dcX <= x2; RDraw.dcX++) {
            // calculate lighting
            short col = maskedtexturecol[maskedtexturecolOffset + RDraw.dcX];
            if (col != Short.MAX_VALUE) {
                if (RMain.fixedcolormap < 0) {
                    index = RThings.spryscale >> RMain.LIGHTSCALESHIFT;

                    if (index >= RMain.MAXLIGHTSCALE) {
                        index = RMain.MAXLIGHTSCALE - 1;
                    }

                    RDraw.dcColormap = RData.colormaps;
                    RDraw.dcColormapOfs = RMain.walllights[index];
                }

                RThings.sprtopscreen = RMain.centeryfrac
                        - Fixed.mul(RDraw.dcTexturemid, RThings.spryscale);
                RDraw.dcIscale = (int) (0xffffffffL / (RThings.spryscale & 0xffffffffL));

                // draw the texture. R_GetColumn lands at the post's
                // pixel data (it adds +3 internally for the C
                // R_DrawColumn fast path). R_DrawMaskedColumn wants
                // the column HEADER (topdelta byte), so we back the
                // offset up by 3 to match the C's
                // `(byte*)R_GetColumn(...) - 3`.
                try {
                    RData.ColumnRef cref = RData.rGetColumn(texnum,
                            col & 0xffff);
                    RThings.rDrawMaskedColumn(
                            new RData.ColumnRef(cref.data, cref.offset - 3));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                maskedtexturecol[maskedtexturecolOffset + RDraw.dcX]
                        = Short.MAX_VALUE;
            }
            RThings.spryscale += rwScalestep;
        }
    }


    //
    // R_RenderSegLoop
    // Draws zero, one, or two textures (and possibly a masked
    // texture) for walls. Can draw or mark the starting pixel of
    // floor and ceiling textures.
    //
    // r_segs.c lines 206–364. CALLED: CORE LOOPING ROUTINE.
    public static void rRenderSegLoop() {
        int angle;
        int index;
        int yl;
        int yh;
        int mid;
        int texturecolumn = 0;
        int top;
        int bottom;

        for (; rwX < rwStopx; rwX++) {
            // mark floor / ceiling areas
            yl = (topfrac + HEIGHTUNIT - 1) >> HEIGHTBITS;

            // no space above wall?
            if (yl < RPlane.ceilingclip[rwX] + 1) {
                yl = RPlane.ceilingclip[rwX] + 1;
            }

            if (markceiling) {
                top = RPlane.ceilingclip[rwX] + 1;
                bottom = yl - 1;

                if (bottom >= RPlane.floorclip[rwX]) {
                    bottom = RPlane.floorclip[rwX] - 1;
                }

                if (top <= bottom) {
                    // C: ceilingplane->top[rwX] = top; bottom[rwX] = bottom.
                    // Java: top[]/bottom[] sized SCREENWIDTH+2 with +1 bias.
                    RBsp.ceilingplane.top[rwX + 1]    = (byte) top;
                    RBsp.ceilingplane.bottom[rwX + 1] = (byte) bottom;
                }
            }

            yh = bottomfrac >> HEIGHTBITS;

            if (yh >= RPlane.floorclip[rwX]) {
                yh = RPlane.floorclip[rwX] - 1;
            }

            if (markfloor) {
                top = yh + 1;
                bottom = RPlane.floorclip[rwX] - 1;
                if (top <= RPlane.ceilingclip[rwX]) {
                    top = RPlane.ceilingclip[rwX] + 1;
                }
                if (top <= bottom) {
                    RBsp.floorplane.top[rwX + 1]    = (byte) top;
                    RBsp.floorplane.bottom[rwX + 1] = (byte) bottom;
                }
            }

            // texturecolumn and lighting are independent of wall tiers
            if (segtextured) {
                // calculate texture offset
                angle = ((rwCenterangle + RMain.xtoviewangle[rwX])
                        >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;
                texturecolumn = rwOffset
                        - Fixed.mul(Tables.finetangent[angle], rwDistance);
                texturecolumn >>= Fixed.FRACBITS;
                // calculate lighting
                index = rwScale >> RMain.LIGHTSCALESHIFT;

                if (index >= RMain.MAXLIGHTSCALE) {
                    index = RMain.MAXLIGHTSCALE - 1;
                }

                RDraw.dcColormap = RData.colormaps;
                RDraw.dcColormapOfs = RMain.walllights[index];
                RDraw.dcX = rwX;
                RDraw.dcIscale = (int) (0xffffffffL / (rwScale & 0xffffffffL));
            }

            // draw the wall tiers
            if (midtexture != 0) {
                // single sided line
                RDraw.dcYl = yl;
                RDraw.dcYh = yh;
                RDraw.dcTexturemid = rwMidtexturemid;
                try {
                    RData.ColumnRef col =
                            RData.rGetColumn(midtexture, texturecolumn);
                    RDraw.dcSource = col.data;
                    RDraw.dcSourceOfs = col.offset;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                if (RMain.detailshift == 0) {
                    RDraw.rDrawColumn();
                } else {
                    RDraw.rDrawColumnLow();
                }
                RPlane.ceilingclip[rwX] = (short) RDraw.viewheight;
                RPlane.floorclip[rwX]   = -1;
            } else {
                // two sided line
                if (toptexture != 0) {
                    // top wall
                    mid = pixhigh >> HEIGHTBITS;
                    pixhigh += pixhighstep;

                    if (mid >= RPlane.floorclip[rwX]) {
                        mid = RPlane.floorclip[rwX] - 1;
                    }

                    if (mid >= yl) {
                        RDraw.dcYl = yl;
                        RDraw.dcYh = mid;
                        RDraw.dcTexturemid = rwToptexturemid;
                        try {
                            RData.ColumnRef col = RData.rGetColumn(
                                    toptexture, texturecolumn);
                            RDraw.dcSource = col.data;
                            RDraw.dcSourceOfs = col.offset;
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        if (RMain.detailshift == 0) {
                            RDraw.rDrawColumn();
                        } else {
                            RDraw.rDrawColumnLow();
                        }
                        RPlane.ceilingclip[rwX] = (short) mid;
                    } else {
                        RPlane.ceilingclip[rwX] = (short) (yl - 1);
                    }
                } else {
                    // no top wall
                    if (markceiling) {
                        RPlane.ceilingclip[rwX] = (short) (yl - 1);
                    }
                }

                if (bottomtexture != 0) {
                    // bottom wall
                    mid = (pixlow + HEIGHTUNIT - 1) >> HEIGHTBITS;
                    pixlow += pixlowstep;

                    // no space above wall?
                    if (mid <= RPlane.ceilingclip[rwX]) {
                        mid = RPlane.ceilingclip[rwX] + 1;
                    }

                    if (mid <= yh) {
                        RDraw.dcYl = mid;
                        RDraw.dcYh = yh;
                        RDraw.dcTexturemid = rwBottomtexturemid;
                        try {
                            RData.ColumnRef col = RData.rGetColumn(
                                    bottomtexture, texturecolumn);
                            RDraw.dcSource = col.data;
                            RDraw.dcSourceOfs = col.offset;
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        if (RMain.detailshift == 0) {
                            RDraw.rDrawColumn();
                        } else {
                            RDraw.rDrawColumnLow();
                        }
                        RPlane.floorclip[rwX] = (short) mid;
                    } else {
                        RPlane.floorclip[rwX] = (short) (yh + 1);
                    }
                } else {
                    // no bottom wall
                    if (markfloor) {
                        RPlane.floorclip[rwX] = (short) (yh + 1);
                    }
                }

                if (maskedtexture) {
                    // save texturecol for backdrawing of masked mid texture
                    maskedtexturecol[maskedtexturecolOffset + rwX]
                            = (short) texturecolumn;
                }
            }

            rwScale += rwScalestep;
            topfrac += topstep;
            bottomfrac += bottomstep;
        }
    }


    //
    // R_StoreWallRange
    // A wall segment will be drawn between start and stop pixels
    // (inclusive).
    //
    // r_segs.c lines 374–745.
    public static void rStoreWallRange(int start, int stop) {
        int hyp;       // fixed_t
        int sineval;   // fixed_t
        int distangle; // angle_t
        int offsetangle; // angle_t
        int vtop;      // fixed_t
        int lightnum;

        // don't overflow and crash
        if (RBsp.dsP == RBsp.MAXDRAWSEGS) {
            return;
        }

        if (start >= RMain.viewwidth || start > stop) {
            ISystem.iError("Bad R_RenderWallRange: " + start + " to " + stop);
        }

        RBsp.sidedef = RBsp.curline.sidedef;
        RBsp.linedef = RBsp.curline.linedef;

        // mark the segment as visible for auto map
        RBsp.linedef.flags |= Line.ML_MAPPED;

        // calculate rw_distance for scale calculation
        rwNormalangle = RBsp.curline.angle + Tables.ANG90;
        // C: offsetangle = abs(rw_normalangle - rw_angle1)
        // — angle_t arithmetic is unsigned 32-bit, so abs() doesn't
        // really make sense; the C effectively wants the magnitude
        // of the angular distance. Match the C bit-for-bit.
        offsetangle = Math.abs(rwNormalangle - RBsp.rwAngle1);

        if (Integer.compareUnsigned(offsetangle, Tables.ANG90) > 0) {
            offsetangle = Tables.ANG90;
        }

        distangle = Tables.ANG90 - offsetangle;
        hyp = RMain.rPointToDist(RBsp.curline.v1.x, RBsp.curline.v1.y);
        sineval = Tables.finesine[(distangle >>> Tables.ANGLETOFINESHIFT)
                & Tables.FINEMASK];
        rwDistance = Fixed.mul(hyp, sineval);

        Drawseg ds = RBsp.drawsegs[RBsp.dsP];
        if (ds == null) {
            ds = new Drawseg();
            RBsp.drawsegs[RBsp.dsP] = ds;
        }

        ds.x1 = rwX = start;
        ds.x2 = stop;
        ds.curline = RBsp.curline;
        rwStopx = stop + 1;

        // calculate scale at both ends and step
        ds.scale1 = rwScale = RMain.rScaleFromGlobalAngle(
                RMain.viewangle + RMain.xtoviewangle[start]);

        if (stop > start) {
            ds.scale2 = RMain.rScaleFromGlobalAngle(
                    RMain.viewangle + RMain.xtoviewangle[stop]);
            ds.scalestep = rwScalestep =
                    (ds.scale2 - rwScale) / (stop - start);
        } else {
            ds.scale2 = ds.scale1;
        }

        // calculate texture boundaries and decide if floor / ceiling
        // marks are needed
        worldtop = RBsp.frontsector.ceilingheight - RMain.viewz;
        worldbottom = RBsp.frontsector.floorheight - RMain.viewz;

        midtexture = toptexture = bottomtexture = 0;
        maskedtexture = false;
        ds.maskedtexturecol = null;
        ds.maskedtexturecolOffset = 0;

        if (RBsp.backsector == null) {
            // single sided line
            midtexture = RData.texturetranslation[RBsp.sidedef.midtexture];
            // a single sided line is terminal, so it must mark ends
            markfloor = markceiling = true;
            if ((RBsp.linedef.flags & Line.ML_DONTPEGBOTTOM) != 0) {
                vtop = RBsp.frontsector.floorheight
                        + RData.textureheight[RBsp.sidedef.midtexture];
                // bottom of texture at bottom
                rwMidtexturemid = vtop - RMain.viewz;
            } else {
                // top of texture at top
                rwMidtexturemid = worldtop;
            }
            rwMidtexturemid += RBsp.sidedef.rowoffset;

            ds.silhouette = Drawseg.SIL_BOTH;
            ds.sprtopclip = RMain.screenheightarray;
            ds.sprtopclipOffset = 0;
            ds.sprbottomclip = RThings.negonearray;
            ds.sprbottomclipOffset = 0;
            ds.bsilheight = Integer.MAX_VALUE;
            ds.tsilheight = Integer.MIN_VALUE;
        } else {
            // two sided line
            ds.sprtopclip = ds.sprbottomclip = null;
            ds.silhouette = Drawseg.SIL_NONE;

            if (RBsp.frontsector.floorheight > RBsp.backsector.floorheight) {
                ds.silhouette = Drawseg.SIL_BOTTOM;
                ds.bsilheight = RBsp.frontsector.floorheight;
            } else if (RBsp.backsector.floorheight > RMain.viewz) {
                ds.silhouette = Drawseg.SIL_BOTTOM;
                ds.bsilheight = Integer.MAX_VALUE;
                // ds.sprbottomclip = negonearray;
            }

            if (RBsp.frontsector.ceilingheight < RBsp.backsector.ceilingheight) {
                ds.silhouette |= Drawseg.SIL_TOP;
                ds.tsilheight = RBsp.frontsector.ceilingheight;
            } else if (RBsp.backsector.ceilingheight < RMain.viewz) {
                ds.silhouette |= Drawseg.SIL_TOP;
                ds.tsilheight = Integer.MIN_VALUE;
                // ds.sprtopclip = screenheightarray;
            }

            if (RBsp.backsector.ceilingheight <= RBsp.frontsector.floorheight) {
                ds.sprbottomclip = RThings.negonearray;
                ds.sprbottomclipOffset = 0;
                ds.bsilheight = Integer.MAX_VALUE;
                ds.silhouette |= Drawseg.SIL_BOTTOM;
            }

            if (RBsp.backsector.floorheight >= RBsp.frontsector.ceilingheight) {
                ds.sprtopclip = RMain.screenheightarray;
                ds.sprtopclipOffset = 0;
                ds.tsilheight = Integer.MIN_VALUE;
                ds.silhouette |= Drawseg.SIL_TOP;
            }

            worldhigh = RBsp.backsector.ceilingheight - RMain.viewz;
            worldlow  = RBsp.backsector.floorheight - RMain.viewz;

            // hack to allow height changes in outdoor areas
            if (RBsp.frontsector.ceilingpic == RSky.skyflatnum
                    && RBsp.backsector.ceilingpic == RSky.skyflatnum) {
                worldtop = worldhigh;
            }


            if (worldlow != worldbottom
                    || RBsp.backsector.floorpic != RBsp.frontsector.floorpic
                    || RBsp.backsector.lightlevel != RBsp.frontsector.lightlevel) {
                markfloor = true;
            } else {
                // same plane on both sides
                markfloor = false;
            }


            if (worldhigh != worldtop
                    || RBsp.backsector.ceilingpic != RBsp.frontsector.ceilingpic
                    || RBsp.backsector.lightlevel != RBsp.frontsector.lightlevel) {
                markceiling = true;
            } else {
                // same plane on both sides
                markceiling = false;
            }

            if (RBsp.backsector.ceilingheight <= RBsp.frontsector.floorheight
                    || RBsp.backsector.floorheight >= RBsp.frontsector.ceilingheight) {
                // closed door
                markceiling = markfloor = true;
            }


            if (worldhigh < worldtop) {
                // top texture
                toptexture = RData.texturetranslation[RBsp.sidedef.toptexture];
                if ((RBsp.linedef.flags & Line.ML_DONTPEGTOP) != 0) {
                    // top of texture at top
                    rwToptexturemid = worldtop;
                } else {
                    vtop = RBsp.backsector.ceilingheight
                            + RData.textureheight[RBsp.sidedef.toptexture];

                    // bottom of texture
                    rwToptexturemid = vtop - RMain.viewz;
                }
            }
            if (worldlow > worldbottom) {
                // bottom texture
                bottomtexture =
                        RData.texturetranslation[RBsp.sidedef.bottomtexture];

                if ((RBsp.linedef.flags & Line.ML_DONTPEGBOTTOM) != 0) {
                    // bottom of texture at bottom
                    // top of texture at top
                    rwBottomtexturemid = worldtop;
                } else {
                    // top of texture at top
                    rwBottomtexturemid = worldlow;
                }
            }
            rwToptexturemid += RBsp.sidedef.rowoffset;
            rwBottomtexturemid += RBsp.sidedef.rowoffset;

            // allocate space for masked texture tables
            if (RBsp.sidedef.midtexture != 0) {
                // masked midtexture
                maskedtexture = true;
                ds.maskedtexturecol = RPlane.openings;
                ds.maskedtexturecolOffset = RPlane.lastopening - rwX;
                maskedtexturecol = RPlane.openings;
                maskedtexturecolOffset = RPlane.lastopening - rwX;
                RPlane.lastopening += rwStopx - rwX;
            }
        }

        // calculate rw_offset (only needed for textured lines)
        segtextured = (midtexture | toptexture | bottomtexture) != 0
                || maskedtexture;

        if (segtextured) {
            offsetangle = rwNormalangle - RBsp.rwAngle1;

            if (Integer.compareUnsigned(offsetangle, Tables.ANG180) > 0) {
                offsetangle = -offsetangle;
            }

            if (Integer.compareUnsigned(offsetangle, Tables.ANG90) > 0) {
                offsetangle = Tables.ANG90;
            }

            sineval = Tables.finesine[(offsetangle >>> Tables.ANGLETOFINESHIFT)
                    & Tables.FINEMASK];
            rwOffset = Fixed.mul(hyp, sineval);

            if (Integer.compareUnsigned(rwNormalangle - RBsp.rwAngle1,
                    Tables.ANG180) < 0) {
                rwOffset = -rwOffset;
            }

            rwOffset += RBsp.sidedef.textureoffset + RBsp.curline.offset;
            rwCenterangle = Tables.ANG90 + RMain.viewangle - rwNormalangle;

            // calculate light table — use different light tables for
            // horizontal / vertical / diagonal walls.
            if (RMain.fixedcolormap < 0) {
                lightnum = (RBsp.frontsector.lightlevel >> RMain.LIGHTSEGSHIFT)
                        + RMain.extralight;

                if (RBsp.curline.v1.y == RBsp.curline.v2.y) {
                    lightnum--;
                } else if (RBsp.curline.v1.x == RBsp.curline.v2.x) {
                    lightnum++;
                }

                if (lightnum < 0) {
                    RMain.walllights = RMain.scalelight[0];
                } else if (lightnum >= RMain.LIGHTLEVELS) {
                    RMain.walllights = RMain.scalelight[RMain.LIGHTLEVELS - 1];
                } else {
                    RMain.walllights = RMain.scalelight[lightnum];
                }
            }
        }

        // if a floor / ceiling plane is on the wrong side of the
        // view plane, it is definitely invisible and doesn't need
        // to be marked.
        if (RBsp.frontsector.floorheight >= RMain.viewz) {
            // above view plane
            markfloor = false;
        }

        if (RBsp.frontsector.ceilingheight <= RMain.viewz
                && RBsp.frontsector.ceilingpic != RSky.skyflatnum) {
            // below view plane
            markceiling = false;
        }


        // calculate incremental stepping values for texture edges
        worldtop >>= 4;
        worldbottom >>= 4;

        topstep = -Fixed.mul(rwScalestep, worldtop);
        topfrac = (RMain.centeryfrac >> 4) - Fixed.mul(worldtop, rwScale);

        bottomstep = -Fixed.mul(rwScalestep, worldbottom);
        bottomfrac = (RMain.centeryfrac >> 4) - Fixed.mul(worldbottom, rwScale);

        if (RBsp.backsector != null) {
            worldhigh >>= 4;
            worldlow >>= 4;

            if (worldhigh < worldtop) {
                pixhigh = (RMain.centeryfrac >> 4)
                        - Fixed.mul(worldhigh, rwScale);
                pixhighstep = -Fixed.mul(rwScalestep, worldhigh);
            }

            if (worldlow > worldbottom) {
                pixlow = (RMain.centeryfrac >> 4)
                        - Fixed.mul(worldlow, rwScale);
                pixlowstep = -Fixed.mul(rwScalestep, worldlow);
            }
        }

        // render it
        if (markceiling) {
            RBsp.ceilingplane = RPlane.rCheckPlane(RBsp.ceilingplane,
                    rwX, rwStopx - 1);
        }

        if (markfloor) {
            RBsp.floorplane = RPlane.rCheckPlane(RBsp.floorplane,
                    rwX, rwStopx - 1);
        }

        rRenderSegLoop();


        // save sprite clipping info
        if (((ds.silhouette & Drawseg.SIL_TOP) != 0 || maskedtexture)
                && ds.sprtopclip == null) {
            // C: memcpy(lastopening, ceilingclip+start, 2*(rw_stopx-start));
            // 2 bytes per short = direct short[] copy in Java.
            System.arraycopy(RPlane.ceilingclip, start,
                    RPlane.openings, RPlane.lastopening,
                    rwStopx - start);
            ds.sprtopclip = RPlane.openings;
            ds.sprtopclipOffset = RPlane.lastopening - start;
            RPlane.lastopening += rwStopx - start;
        }

        if (((ds.silhouette & Drawseg.SIL_BOTTOM) != 0 || maskedtexture)
                && ds.sprbottomclip == null) {
            System.arraycopy(RPlane.floorclip, start,
                    RPlane.openings, RPlane.lastopening,
                    rwStopx - start);
            ds.sprbottomclip = RPlane.openings;
            ds.sprbottomclipOffset = RPlane.lastopening - start;
            RPlane.lastopening += rwStopx - start;
        }

        if (maskedtexture && (ds.silhouette & Drawseg.SIL_TOP) == 0) {
            ds.silhouette |= Drawseg.SIL_TOP;
            ds.tsilheight = Integer.MIN_VALUE;
        }
        if (maskedtexture && (ds.silhouette & Drawseg.SIL_BOTTOM) == 0) {
            ds.silhouette |= Drawseg.SIL_BOTTOM;
            ds.bsilheight = Integer.MAX_VALUE;
        }
        RBsp.dsP++;
    }
}
