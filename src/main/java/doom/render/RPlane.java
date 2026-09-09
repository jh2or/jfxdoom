// Port of linuxdoom-1.10/r_plane.c — full.
//
// Floor / ceiling rendering. Drawn as horizontal "spans" — one
// per scan-line per visible flat — that step the texture-space
// xfrac/yfrac across the row. Visplanes are per-frame allocations
// (sliced from a fixed pool) that group adjacent screen columns
// at the same height/picnum/lightlevel.
package doom.render;

import doom.fixed.Fixed;
import doom.system.Doomdef;
import doom.system.ISystem;
import doom.wad.WWad;
import doom.wad.ZZone;

import java.io.IOException;

public final class RPlane {

    // r_plane.c line 52 — visplane pool cap.
    public static final int MAXVISPLANES = 128;

    // r_plane.c line 59 — opening list cap. Used by RSegs /
    // RThings for per-column sprite/wall clipping. Non-final so
    // resizeForRender() can track the JavaFX shell's per-session
    // render scale.
    public static int MAXOPENINGS = Doomdef.RENDER_WIDTH * 64;

    // r_defs.h lines 460–480 — visplane_t.
    // top[] and bottom[] track the per-column upper / lower row of
    // each visplane (0xff sentinel = no fragment yet for this
    // column). The C reserves pad bytes so writes at minx-1 and
    // maxx+1 in R_DrawPlanes don't smash adjacent struct fields;
    // the Java port keeps separate top[] / bottom[] arrays sized
    // SCREENWIDTH+2 with the extra slots indexed +1 from the
    // logical column.
    public static final class Visplane {
        public int height;     // fixed_t
        public int picnum;
        public int lightlevel;
        public int minx;
        public int maxx;
        // Indexed [logicalColumn + 1] to leave room for the
        // sentinel writes at top[minx-1] / top[maxx+1]. Widened
        // from byte[] to short[] so y-coords past 255 (any
        // RENDER_MUL > 1 setup) don't wrap around — this was
        // PrBoom's famous hi-res blocker too. Non-final so
        // RPlane.resizeForRender can re-allocate per session.
        public short[] top    = new short[Doomdef.RENDER_WIDTH + 2];
        public short[] bottom = new short[Doomdef.RENDER_WIDTH + 2];
    }

    public static final Visplane[] visplanes = new Visplane[MAXVISPLANES];
    public static int lastvisplane;     // index = next-free slot

    public static Visplane floorplane;
    public static Visplane ceilingplane;

    static {
        for (int i = 0; i < MAXVISPLANES; i++) {
            visplanes[i] = new Visplane();
        }
    }

    // r_plane.c line 60 — shared openings buffer for RSegs / RThings.
    public static short[] openings = new short[MAXOPENINGS];
    public static int lastopening;      // index = next-free short

    // r_plane.c lines 69–70 — per-column upper/lower clip ranges.
    // floorclip starts at SCREENHEIGHT (no floor visible);
    // ceilingclip starts at -1 (no ceiling visible).
    public static short[] floorclip   = new short[Doomdef.RENDER_WIDTH];
    public static short[] ceilingclip = new short[Doomdef.RENDER_WIDTH];

    // r_plane.c lines 76–77 — R_MakeSpans scratch.
    public static int[] spanstart = new int[Doomdef.RENDER_HEIGHT];
    public static int[] spanstop  = new int[Doomdef.RENDER_HEIGHT];

    // r_plane.c lines 82–93 — texture-mapping cache.
    public static int planeheight;       // fixed_t
    public static int[] cachedheight   = new int[Doomdef.RENDER_HEIGHT];
    public static int[] cacheddistance = new int[Doomdef.RENDER_HEIGHT];
    public static int[] cachedxstep    = new int[Doomdef.RENDER_HEIGHT];
    public static int[] cachedystep    = new int[Doomdef.RENDER_HEIGHT];

    // JavaFX-port deviation 41 — re-allocate all RENDER_*-sized
    // arrays so the JavaFX shell can switch render scale per
    // session. Visplane.top/bottom are per-instance, so this
    // walks the pool too.
    public static void resizeForRender() {
        MAXOPENINGS = Doomdef.RENDER_WIDTH * 64;
        if (openings.length != MAXOPENINGS) {
            openings = new short[MAXOPENINGS];
        }
        if (floorclip.length != Doomdef.RENDER_WIDTH) {
            floorclip   = new short[Doomdef.RENDER_WIDTH];
            ceilingclip = new short[Doomdef.RENDER_WIDTH];
        }
        if (spanstart.length != Doomdef.RENDER_HEIGHT) {
            spanstart      = new int[Doomdef.RENDER_HEIGHT];
            spanstop       = new int[Doomdef.RENDER_HEIGHT];
            cachedheight   = new int[Doomdef.RENDER_HEIGHT];
            cacheddistance = new int[Doomdef.RENDER_HEIGHT];
            cachedxstep    = new int[Doomdef.RENDER_HEIGHT];
            cachedystep    = new int[Doomdef.RENDER_HEIGHT];
        }
        if (visplanes[0].top.length != Doomdef.RENDER_WIDTH + 2) {
            for (Visplane vp : visplanes) {
                vp.top    = new short[Doomdef.RENDER_WIDTH + 2];
                vp.bottom = new short[Doomdef.RENDER_WIDTH + 2];
            }
        }
    }

    public static int basexscale;        // fixed_t
    public static int baseyscale;        // fixed_t

    // r_plane.c line 82 — current plane's per-distance-band light row.
    // Set by R_DrawPlanes before each visplane span sweep; read by
    // R_MapPlane. Holds the light-band offset into RData.colormaps.
    public static int[] planezlight;

    private RPlane() {
    }

    //
    // R_InitPlanes — no-op in the C ("// Doh!"). Lifted verbatim.
    //
    public static void rInitPlanes() {
        // intentionally empty
    }

    //
    // R_MapPlane
    //
    // r_plane.c lines 121–178. Per-row span dispatcher: cache
    // distance / xstep / ystep for this y, derive xfrac/yfrac from
    // viewangle + xtoviewangle[x1], pick the colormap, hand off to
    // RDraw.rDrawSpan.
    public static void rMapPlane(int y, int x1, int x2) {
        int angle;
        int distance;
        int length;
        int index;

        if (x2 < x1
                || x1 < 0
                || x2 >= RMain.viewwidth
                || y > RDraw.viewheight) {
            ISystem.iError("R_MapPlane: " + x1 + ", " + x2 + " at " + y);
        }

        if (planeheight != cachedheight[y]) {
            cachedheight[y] = planeheight;
            distance = cacheddistance[y] = Fixed.mul(planeheight, RMain.yslope[y]);
            RDraw.dsXstep = cachedxstep[y] = Fixed.mul(distance, basexscale);
            RDraw.dsYstep = cachedystep[y] = Fixed.mul(distance, baseyscale);
        } else {
            distance = cacheddistance[y];
            RDraw.dsXstep = cachedxstep[y];
            RDraw.dsYstep = cachedystep[y];
        }

        length = Fixed.mul(distance, RMain.distscale[x1]);
        angle = ((RMain.viewangle + RMain.xtoviewangle[x1])
                >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;
        RDraw.dsXfrac =  RMain.viewx + Fixed.mul(Tables.finecosine(angle), length);
        RDraw.dsYfrac = -RMain.viewy - Fixed.mul(Tables.finesine[angle],   length);

        if (RMain.fixedcolormap >= 0) {
            RDraw.dsColormap = RData.colormaps;
            RDraw.dsColormapOfs = RMain.fixedcolormap;
        } else {
            index = distance >>> RMain.LIGHTZSHIFT;

            if (index >= RMain.MAXLIGHTZ) {
                index = RMain.MAXLIGHTZ - 1;
            }

            RDraw.dsColormap = RData.colormaps;
            RDraw.dsColormapOfs = planezlight[index];
        }

        RenderDepth.spanDepth = distance >>> Fixed.FRACBITS;
        RDraw.dsY = y;
        RDraw.dsX1 = x1;
        RDraw.dsX2 = x2;

        // high or low detail — Java port inlines the dispatch
        // (deviation: no spanfunc trampoline).
        if (RMain.detailshift == 0) {
            RDraw.rDrawSpan();
        } else {
            RDraw.rDrawSpanLow();
        }
    }

    //
    // R_ClearPlanes
    //
    // r_plane.c lines 185–209.
    public static void rClearPlanes() {
        int i;

        // opening / clipping determination
        for (i = 0; i < RDraw.viewwidth; i++) {
            floorclip[i] = (short) RDraw.viewheight;
            ceilingclip[i] = -1;
        }

        lastvisplane = 0;
        lastopening = 0;

        // texture calculation
        for (i = 0; i < cachedheight.length; i++) {
            cachedheight[i] = 0;
        }

        // left to right mapping
        int angle = ((RMain.viewangle - Tables.ANG90)
                >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;

        // scale will be unit scale at SCREENWIDTH/2 distance
        basexscale =  Fixed.div(Tables.finecosine(angle), RMain.centerxfrac);
        baseyscale = -Fixed.div(Tables.finesine[angle],   RMain.centerxfrac);
    }


    //
    // R_FindPlane
    //
    // r_plane.c lines 218–259. Sky-flat picnum collapses
    // height/lightlevel so all sky columns share one visplane.
    public static Visplane rFindPlane(int height, int picnum, int lightlevel) {
        if (picnum == RSky.skyflatnum) {
            height = 0;             // all skys map together
            lightlevel = 0;
        }

        int idx;
        for (idx = 0; idx < lastvisplane; idx++) {
            Visplane vp = visplanes[idx];
            if (height == vp.height
                    && picnum == vp.picnum
                    && lightlevel == vp.lightlevel) {
                return vp;
            }
        }


        if (lastvisplane == MAXVISPLANES) {
            ISystem.iError("R_FindPlane: no more visplanes");
        }

        Visplane fresh = visplanes[lastvisplane];
        lastvisplane++;

        fresh.height = height;
        fresh.picnum = picnum;
        fresh.lightlevel = lightlevel;
        fresh.minx = Doomdef.RENDER_WIDTH;
        fresh.maxx = -1;

        // Reset top[] to 0xffff sentinel (no fragment yet).
        for (int i = 0; i < fresh.top.length; i++) {
            fresh.top[i] = (short) 0xffff;
        }

        return fresh;
    }


    //
    // R_CheckPlane
    //
    // r_plane.c lines 266–324. Either extends `pl` to cover
    // [start, stop] or splits off a fresh visplane that does.
    public static Visplane rCheckPlane(Visplane pl, int start, int stop) {
        int intrl;
        int intrh;
        int unionl;
        int unionh;
        int x;

        if (start < pl.minx) {
            intrl = pl.minx;
            unionl = start;
        } else {
            unionl = pl.minx;
            intrl = start;
        }

        if (stop > pl.maxx) {
            intrh = pl.maxx;
            unionh = stop;
        } else {
            unionh = pl.maxx;
            intrh = stop;
        }

        // Walk the intersection: if every column is still 0xff
        // sentinel we can just extend `pl`.
        for (x = intrl; x <= intrh; x++) {
            if ((pl.top[x + 1] & 0xffff) != 0xffff) {
                break;
            }
        }

        if (x > intrh) {
            pl.minx = unionl;
            pl.maxx = unionh;

            // use the same one
            return pl;
        }

        // make a new visplane
        Visplane fresh = visplanes[lastvisplane];
        fresh.height = pl.height;
        fresh.picnum = pl.picnum;
        fresh.lightlevel = pl.lightlevel;
        lastvisplane++;
        fresh.minx = start;
        fresh.maxx = stop;

        for (int i = 0; i < fresh.top.length; i++) {
            fresh.top[i] = (short) 0xffff;
        }

        return fresh;
    }


    //
    // R_MakeSpans
    //
    // r_plane.c lines 331–359. Walks two stacks of spans (top edge,
    // bottom edge) column by column, dispatching closed spans into
    // R_MapPlane and opening fresh ones at `x` for new columns.
    public static void rMakeSpans(int x, int t1, int b1, int t2, int b2) {
        while (t1 < t2 && t1 <= b1) {
            rMapPlane(t1, spanstart[t1], x - 1);
            t1++;
        }
        while (b1 > b2 && b1 >= t1) {
            rMapPlane(b1, spanstart[b1], x - 1);
            b1--;
        }

        while (t2 < t1 && t2 <= b2) {
            spanstart[t2] = x;
            t2++;
        }
        while (b2 > b1 && b2 >= t2) {
            spanstart[b2] = x;
            b2--;
        }
    }


    //
    // R_DrawPlanes
    // Called at the end of each frame.
    //
    // r_plane.c lines 367–453.
    public static void rDrawPlanes() {
        Visplane pl;
        int light;
        int x;
        int stop;
        int angle;

        if (RBsp.dsP > RBsp.MAXDRAWSEGS) {
            ISystem.iError("R_DrawPlanes: drawsegs overflow (" + RBsp.dsP + ")");
        }
        if (lastvisplane > MAXVISPLANES) {
            ISystem.iError("R_DrawPlanes: visplane overflow (" + lastvisplane + ")");
        }
        if (lastopening > MAXOPENINGS) {
            ISystem.iError("R_DrawPlanes: opening overflow (" + lastopening + ")");
        }

        for (int p = 0; p < lastvisplane; p++) {
            pl = visplanes[p];

            if (pl.minx > pl.maxx) {
                continue;
            }


            // sky flat
            if (pl.picnum == RSky.skyflatnum) {
                RenderDepth.sky = true;
                RDraw.dcIscale = RMain.pspriteiscale >> RMain.detailshift;

                // Sky is allways drawn full bright,
                //  i.e. colormaps[0] is used.
                // Because of this hack, sky is not affected
                //  by INVUL inverse mapping.
                RDraw.dcColormap = RData.colormaps;
                RDraw.dcColormapOfs = 0;
                RDraw.dcTexturemid = RSky.skytexturemid;
                for (x = pl.minx; x <= pl.maxx; x++) {
                    RDraw.dcYl = pl.top[x + 1] & 0xffff;
                    RDraw.dcYh = pl.bottom[x + 1] & 0xffff;

                    if (RDraw.dcYl <= RDraw.dcYh) {
                        angle = (RMain.viewangle + RMain.xtoviewangle[x])
                                >>> RSky.ANGLETOSKYSHIFT;
                        RDraw.dcX = x;
                        try {
                            RData.ColumnRef col =
                                    RData.rGetColumn(RSky.skytexture, angle);
                            RDraw.dcSource = col.data;
                            RDraw.dcSourceOfs = col.offset;
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        // colfunc — Java port inlines the dispatch.
                        if (RMain.detailshift == 0) {
                            RDraw.rDrawColumn();
                        } else {
                            RDraw.rDrawColumnLow();
                        }
                    }
                }
                RenderDepth.sky = false;
                continue;
            }

            // regular flat
            int flatLump = RData.firstflat
                    + RData.flattranslation[pl.picnum];
            try {
                RDraw.dsSource = WWad.wCacheLumpNum(flatLump, ZZone.PU_STATIC);
                RDraw.dsSourceOfs = 0;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            planeheight = Math.abs(pl.height - RMain.viewz);
            light = (pl.lightlevel >> RMain.LIGHTSEGSHIFT) + RMain.extralight;

            if (light >= RMain.LIGHTLEVELS) {
                light = RMain.LIGHTLEVELS - 1;
            }

            if (light < 0) {
                light = 0;
            }

            planezlight = RMain.zlight[light];

            // C: pl->top[pl->maxx+1] = 0xff; pl->top[pl->minx-1] = 0xff;
            // The Java port indexes top[] with a +1 bias to absorb the
            // C's pad-byte sentinel writes.
            pl.top[pl.maxx + 1 + 1] = (short) 0xffff;
            pl.top[pl.minx - 1 + 1] = (short) 0xffff;

            stop = pl.maxx + 1;

            for (x = pl.minx; x <= stop; x++) {
                rMakeSpans(x,
                        pl.top[x - 1 + 1] & 0xffff,
                        pl.bottom[x - 1 + 1] & 0xffff,
                        pl.top[x + 1]     & 0xffff,
                        pl.bottom[x + 1]  & 0xffff);
            }

            // C: Z_ChangeTag(ds_source, PU_CACHE) — release the flat
            // back to the cache so it can be evicted. The Java GC
            // already releases unreferenced blocks once dsSource is
            // overwritten on the next frame, so the demote call has
            // no effect; skipped per the same rationale as deviation
            // 7 (the WAD lump cache).
        }
    }
}
