// Port of linuxdoom-1.10/r_plane.c — partial.
//
// Floor / ceiling rendering. Drawn as horizontal "spans" — one
// per scan-line per visible flat — that step the texture-space
// xfrac/yfrac across the row. Visplanes are per-frame allocations
// (sliced from a fixed pool) that group adjacent screen columns
// at the same height/picnum/lightlevel.
//
// Ported in this pass:
//   - Visplane (visplane_t)
//   - visplanes[] pool + lastvisplane / floorplane / ceilingplane
//   - openings[] pool + lastopening (used by r_segs / r_things)
//   - floorclip / ceilingclip (per-column upper / lower clip)
//   - R_FindPlane  (visplane allocation + sky-flat collapse)
//   - R_CheckPlane (visplane split or extend)
//   - R_ClearPlanes (per-frame state reset)
//   - R_MakeSpans  (column-walk → span dispatch)
//
// Deferred until r_main R_InitTextureMapping / R_InitLightTables
// land:
//   - R_MapPlane (needs xtoviewangle / yslope / distscale / zlight /
//     fixedcolormap)
//   - R_DrawPlanes' rendering body (needs xtoviewangle / colormaps[]
//     / R_GetColumn / pspriteiscale / detailshift / centerxfrac)
//
// Until then the visplane allocation produces correct lists; only
// the actual pixel writes are no-ops.
package doom.render;

import doom.system.Doomdef;
import doom.system.ISystem;

public final class RPlane {

    // r_plane.c line 52 — visplane pool cap.
    public static final int MAXVISPLANES = 128;

    // r_plane.c line 59 — opening list cap. Used by RSegs /
    // RThings for per-column sprite/wall clipping.
    public static final int MAXOPENINGS = Doomdef.SCREENWIDTH * 64;

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
        // sentinel writes at top[minx-1] / top[maxx+1].
        public final byte[] top    = new byte[Doomdef.SCREENWIDTH + 2];
        public final byte[] bottom = new byte[Doomdef.SCREENWIDTH + 2];
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
    public static final short[] openings = new short[MAXOPENINGS];
    public static int lastopening;      // index = next-free short

    // r_plane.c lines 69–70 — per-column upper/lower clip ranges.
    // floorclip starts at SCREENHEIGHT (no floor visible);
    // ceilingclip starts at -1 (no ceiling visible).
    public static final short[] floorclip   = new short[Doomdef.SCREENWIDTH];
    public static final short[] ceilingclip = new short[Doomdef.SCREENWIDTH];

    // r_plane.c lines 76–77 — R_MakeSpans scratch.
    public static final int[] spanstart = new int[Doomdef.SCREENHEIGHT];
    public static final int[] spanstop  = new int[Doomdef.SCREENHEIGHT];

    // r_plane.c lines 82–93 — texture-mapping cache.
    public static int planeheight;       // fixed_t
    public static final int[] cachedheight   = new int[Doomdef.SCREENHEIGHT];
    public static final int[] cacheddistance = new int[Doomdef.SCREENHEIGHT];
    public static final int[] cachedxstep    = new int[Doomdef.SCREENHEIGHT];
    public static final int[] cachedystep    = new int[Doomdef.SCREENHEIGHT];

    public static int basexscale;        // fixed_t
    public static int baseyscale;        // fixed_t

    private RPlane() {
    }

    //
    // R_InitPlanes — no-op in the C ("// Doh!"). Lifted verbatim.
    //
    public static void rInitPlanes() {
        // intentionally empty
    }

    //
    // R_MapPlane (stub).
    //
    // p_plane.c lines 121–178. Per-row span dispatcher: cache
    // distance / xstep / ystep for this y, derive xfrac/yfrac from
    // viewangle + xtoviewangle[x1], pick the colormap, hand off to
    // RDraw.spanfunc. Implementation deferred until r_main provides
    // xtoviewangle / yslope / distscale / zlight / fixedcolormap.
    public static void rMapPlane(int y, int x1, int x2) {
        // Intentionally empty until r_main scaffolding lands.
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

        // basexscale / baseyscale derivation needs centerxfrac (set
        // by R_InitTextureMapping). Until r_main lands, leave the
        // scales at zero — R_MapPlane is stubbed too, so they're
        // unused.
        // C: angle = (viewangle - ANG90) >> ANGLETOFINESHIFT;
        //    basexscale =  FixedDiv(finecosine[angle], centerxfrac);
        //    baseyscale = -FixedDiv(finesine[angle],   centerxfrac);
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
        fresh.minx = Doomdef.SCREENWIDTH;
        fresh.maxx = -1;

        // Reset top[] to 0xff sentinel (no fragment yet).
        for (int i = 0; i < fresh.top.length; i++) {
            fresh.top[i] = (byte) 0xff;
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
            if ((pl.top[x + 1] & 0xff) != 0xff) {
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
            fresh.top[i] = (byte) 0xff;
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
    // R_DrawPlanes (stub).
    //
    // r_plane.c lines 367–453. Walks the visplane list and either
    // dispatches sky columns via R_GetColumn / colfunc or builds
    // span stacks via R_MakeSpans. Defers until r_main scaffolding
    // (xtoviewangle / pspriteiscale / detailshift / colormaps /
    // skytexturemid / zlight / extralight gating) lands.
    public static void rDrawPlanes() {
        // Intentionally empty until r_main scaffolding lands.
    }
}
