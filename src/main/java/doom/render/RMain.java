// Port of linuxdoom-1.10/r_main.c — partial (full setup, no
// per-frame R_RenderPlayerView body until r_segs / r_things port).
//
// BSP-descent helpers (R_PointOnSide, R_PointOnSegSide,
// R_PointInSubsector) used by p_maputl / p_mobj / p_telept, plus the
// renderer scalars and tables built at level start (viewangletox /
// xtoviewangle / yslope / distscale / scalelight / zlight) and the
// per-frame setup (R_SetupFrame).
//
// R_ScaleFromGlobalAngle is provided so r_segs's R_StoreWallRange
// can scale wall textures once that ports.
//
// R_Init / R_RenderPlayerView orchestrate everything; both bodies
// are full but skip the deferred R_DrawPlanes pixel-fill and
// R_DrawMasked sprite-paint until those ports complete.
package doom.render;

import doom.fixed.Fixed;
import doom.play.Player;
import doom.play.PSetup;
import doom.system.Doomdef;

public final class RMain {

    // r_main.c line 48 — view-cone half-angle. The view cone covers
    // FIELDOFVIEW finescale units (= 90° at FIELDOFVIEW=2048,
    // FINEANGLES=8192).
    public static final int FIELDOFVIEW = 2048;

    // r_main.c — light bands.
    public static final int LIGHTLEVELS    = 16;
    public static final int LIGHTSEGSHIFT  = 4;
    public static final int MAXLIGHTSCALE  = 48;
    public static final int LIGHTSCALESHIFT = 12;
    public static final int MAXLIGHTZ      = 128;
    public static final int LIGHTZSHIFT    = 20;

    // r_main.c — colormap depth. matches r_data.c.
    public static final int NUMCOLORMAPS = 32;

    // r_main.c — per-distance light damping factor.
    public static final int DISTMAP = 2;

    // r_main.c line 103 — vertical centre of the screen (viewheight / 2).
    // Set by R_ExecuteSetViewSize; read by R_DrawColumn/R_DrawFuzzColumn.
    public static int centery;

    // r_main.c line 104 — horizontal centre. Referenced by segs/bsp (not r_draw).
    public static int centerx;

    // Whether the current player is underwater / in an invuln sphere, etc.
    // Used by r_draw (nope, by r_bsp/segs/things via walllights). Kept here
    // so later renderer ports have a home.
    public static int extralight;

    // r_main.c line 55 — frame-and-trace counter. Bumped before each
    // BSP traversal (R_RenderPlayerView) and before each PathTraverse
    // (PMaputl). Lines/sectors stamped with the current value have
    // already been visited in this pass. Initialized to 1 in the C so
    // the first walk always sees fresh state (validcount == 0).
    public static int validcount = 1;

    // r_main.c — view origin used by R_PointToAngle / R_PointToDist.
    // Set by R_SetupFrame each frame from the player mobj; also
    // overwritten temporarily by R_PointToAngle2 to anchor a relative
    // angle computation at an arbitrary point.
    public static int viewx; // fixed_t
    public static int viewy; // fixed_t

    // r_main.c — eye z (player's view height above floor) and the
    // facing angle. Both set by R_SetupFrame; read by r_bsp /
    // r_segs / r_plane / r_things.
    public static int viewz;     // fixed_t
    public static int viewangle; // angle_t

    // r_main.c — view-cone half-angle (FIELDOFVIEW edge). Set by
    // R_InitTextureMapping at startup, fixed for the life of the
    // process. r_bsp's R_AddLine / R_CheckBBox use it to clip
    // off-screen segments before angle-to-x mapping.
    public static int clipangle; // angle_t

    // r_main.c — pixel-width of the rendered view (set by
    // R_ExecuteSetViewSize). r_bsp.c R_ClearClipSegs seeds the
    // initial clip list to ±viewwidth.
    public static int viewwidth;

    // r_main.c — per-frame subsector counter, bumped by R_Subsector.
    // Diagnostic only; the C uses it for the IDDQD-era counters.
    public static int sscount;

    // r_main.c — angle-to-screen-x lookup table. Indexed by an
    // angle_t (>>>ANGLETOFINESHIFT and rotated by ANG90), it returns
    // the screen-x at which a ray at that angle from viewx/y projects.
    // Built by R_InitTextureMapping; r_bsp reads it to map seg-end
    // angles to pixel columns.
    public static final int[] viewangletox = new int[Tables.FINEANGLES / 2];

    // r_main.c — inverse of viewangletox. Indexed by screen-x, returns
    // the smallest viewangle that maps back to that x. Built by
    // R_InitTextureMapping.
    public static final int[] xtoviewangle = new int[Doomdef.RENDER_WIDTH + 1];

    // r_main.c — fixed-point centre + projection. Set by
    // R_ExecuteSetViewSize.
    public static int centerxfrac; // fixed_t
    public static int centeryfrac; // fixed_t
    public static int projection;  // fixed_t

    // r_main.c — view-vector cosine / sine, set by R_SetupFrame.
    // Read by r_segs / r_things.
    public static int viewcos; // fixed_t
    public static int viewsin; // fixed_t

    // r_main.c — current player whose view is rendering.
    public static Player viewplayer;

    // r_main.c — 0 = high detail, 1 = low detail. Set by
    // R_ExecuteSetViewSize from setdetail.
    public static int detailshift;

    // r_main.c — frame counter, bumped by R_SetupFrame each tic.
    // Diagnostic only.
    public static int framecount;

    // r_main.c — per-tic angle nudge, set by HOM debug overlay.
    public static int viewangleoffset;

    // r_main.c — light-table-of-tables. scalelight / scalelightfixed
    // map (light-band, screen-row-distance) → (colormap byte[]).
    // The C stores them as `lighttable_t**`; the Java port keeps the
    // colormap base array (RData.colormaps) and stores the offset
    // into it as an int per slot.
    public static final int[][] scalelight     = new int[LIGHTLEVELS][MAXLIGHTSCALE];
    public static final int[]   scalelightfixed = new int[MAXLIGHTSCALE];
    public static final int[][] zlight          = new int[LIGHTLEVELS][MAXLIGHTZ];

    // r_main.c — viewer's screen-tint colormap offset (>= 0 →
    // forced to that map, e.g. invul/light-amp; -1 = use scalelight
    // / zlight). The C stores `lighttable_t*` (NULL = -1); Java
    // tracks the integer offset into RData.colormaps + a "active"
    // flag.
    public static int fixedcolormap; // offset into RData.colormaps, -1 = inactive

    // r_main.c — current wall-light row pointer, set by r_segs's
    // R_RenderSegLoop based on lighting band + scaledviewwidth.
    // Stored as the int[] row from scalelight (or scalelightfixed).
    public static int[] walllights;

    // r_main.c — psprite scaling, computed by R_ExecuteSetViewSize.
    public static int pspritescale;  // fixed_t
    public static int pspriteiscale; // fixed_t

    // r_main.c — per-row inverse scale and per-column distance scale.
    // R_MapPlane uses them to compute texture xfrac/yfrac per row.
    public static final int[] yslope    = new int[Doomdef.RENDER_HEIGHT];
    public static final int[] distscale = new int[Doomdef.RENDER_WIDTH];

    // r_main.c — per-column initial sprite-clip ceiling (= viewheight
    // at default). Read by r_things during sprite drawing.
    public static final short[] screenheightarray = new short[Doomdef.RENDER_WIDTH];

    // r_main.c — view-size queue. R_SetViewSize sets these so the
    // change applies at the next R_ExecuteSetViewSize.
    public static boolean setsizeneeded;
    public static int     setblocks;
    public static int     setdetail;

    //
    // R_PointOnSide
    // Traverse BSP (sub) tree, check point against partition plane.
    // Returns side 0 (front) or 1 (back).
    //
    // r_main.c lines 161–211. Identical structure to
    // PMaputl.pPointOnLineSide but operates on a node_t (whose
    // partition is (x,y) + (dx,dy)) instead of a line_t.
    public static int rPointOnSide(int x, int y, Node node) {
        int dx;
        int dy;
        int left;
        int right;

        if (node.dx == 0) {
            if (x <= node.x) {
                return node.dy > 0 ? 1 : 0;
            }
            return node.dy < 0 ? 1 : 0;
        }
        if (node.dy == 0) {
            if (y <= node.y) {
                return node.dx < 0 ? 1 : 0;
            }
            return node.dx > 0 ? 1 : 0;
        }

        dx = (x - node.x);
        dy = (y - node.y);

        // Try to quickly decide by looking at sign bits.
        if (((node.dy ^ node.dx ^ dx ^ dy) & 0x80000000) != 0) {
            if (((node.dy ^ dx) & 0x80000000) != 0) {
                // (left is negative)
                return 1;
            }
            return 0;
        }

        left = Fixed.mul(node.dy >> Fixed.FRACBITS, dx);
        right = Fixed.mul(dy, node.dx >> Fixed.FRACBITS);

        if (right < left) {
            // front side
            return 0;
        }
        // back side
        return 1;
    }

    //
    // R_PointOnSegSide
    // Same shape as R_PointOnSide but operates on a seg.
    //
    // r_main.c lines 214–274.
    public static int rPointOnSegSide(int x, int y, Seg line) {
        int lx;
        int ly;
        int ldx;
        int ldy;
        int dx;
        int dy;
        int left;
        int right;

        lx = line.v1.x;
        ly = line.v1.y;

        ldx = line.v2.x - lx;
        ldy = line.v2.y - ly;

        if (ldx == 0) {
            if (x <= lx) {
                return ldy > 0 ? 1 : 0;
            }
            return ldy < 0 ? 1 : 0;
        }
        if (ldy == 0) {
            if (y <= ly) {
                return ldx < 0 ? 1 : 0;
            }
            return ldx > 0 ? 1 : 0;
        }

        dx = (x - lx);
        dy = (y - ly);

        // Try to quickly decide by looking at sign bits.
        if (((ldy ^ ldx ^ dx ^ dy) & 0x80000000) != 0) {
            if (((ldy ^ dx) & 0x80000000) != 0) {
                // (left is negative)
                return 1;
            }
            return 0;
        }

        left = Fixed.mul(ldy >> Fixed.FRACBITS, dx);
        right = Fixed.mul(dy, ldx >> Fixed.FRACBITS);

        if (right < left) {
            // front side
            return 0;
        }
        // back side
        return 1;
    }

    //
    // R_PointToAngle
    // To get a global angle from cartesian coordinates, the
    // coordinates are flipped until they are in the first octant of
    // the coordinate system, then the y (<=x) is scaled and divided
    // by x to get a tangent (slope) value which is looked up in the
    // tantoangle[] table.
    //
    // r_main.c lines 291–374. The C `angle_t` is unsigned 32-bit; we
    // use signed int with bit-pattern equivalence (CLAUDE.md §3) and
    // rely on overflow wrapping for the additions.
    public static int rPointToAngle(int x, int y) {
        x -= viewx;
        y -= viewy;

        if (x == 0 && y == 0) {
            return 0;
        }

        if (x >= 0) {
            // x >= 0
            if (y >= 0) {
                // y >= 0
                if (x > y) {
                    // octant 0
                    return Tables.tantoangle[Tables.slopeDiv(y, x)];
                } else {
                    // octant 1
                    return Tables.ANG90 - 1 - Tables.tantoangle[Tables.slopeDiv(x, y)];
                }
            } else {
                // y < 0
                y = -y;
                if (x > y) {
                    // octant 8
                    return -Tables.tantoangle[Tables.slopeDiv(y, x)];
                } else {
                    // octant 7
                    return Tables.ANG270 + Tables.tantoangle[Tables.slopeDiv(x, y)];
                }
            }
        } else {
            // x < 0
            x = -x;
            if (y >= 0) {
                if (x > y) {
                    // octant 3
                    return Tables.ANG180 - 1 - Tables.tantoangle[Tables.slopeDiv(y, x)];
                } else {
                    // octant 2
                    return Tables.ANG90 + Tables.tantoangle[Tables.slopeDiv(x, y)];
                }
            } else {
                y = -y;
                if (x > y) {
                    // octant 4
                    return Tables.ANG180 + Tables.tantoangle[Tables.slopeDiv(y, x)];
                } else {
                    // octant 5
                    return Tables.ANG270 - 1 - Tables.tantoangle[Tables.slopeDiv(x, y)];
                }
            }
        }
    }

    //
    // R_PointToAngle2 — angle from (x1,y1) to (x2,y2) by anchoring
    // viewx/viewy at the source. Note the C side-effect: viewx/viewy
    // get clobbered. Callers (P_HitSlideLine, P_AimLineAttack, etc.)
    // are written to either restore them or not care.
    //
    // r_main.c lines 377–388.
    public static int rPointToAngle2(int x1, int y1, int x2, int y2) {
        viewx = x1;
        viewy = y1;
        return rPointToAngle(x2, y2);
    }

    //
    // R_PointToDist
    // Distance from (viewx, viewy) to (x, y), via the
    // tantoangle table + finesine.
    //
    // r_main.c lines 392–418.
    public static int rPointToDist(int x, int y) {
        int angle;
        int dx;
        int dy;
        int temp;
        int dist;

        dx = Math.abs(x - viewx);
        dy = Math.abs(y - viewy);

        if (dy > dx) {
            temp = dx;
            dx = dy;
            dy = temp;
        }

        angle = (Tables.tantoangle[doom.fixed.Fixed.div(dy, dx) >> Tables.DBITS]
                + Tables.ANG90) >>> Tables.ANGLETOFINESHIFT;

        // use as cosine
        dist = doom.fixed.Fixed.div(dx, Tables.finesine[angle]);

        return dist;
    }

    //
    // R_PointInSubsector
    // Descends the BSP from the root (always at numnodes-1) until it
    // hits a leaf (NF_SUBSECTOR bit set) and returns the subsector.
    //
    // r_main.c lines 800–823.
    public static Subsector rPointInSubsector(int x, int y) {
        Node node;
        int side;
        int nodenum;

        // single subsector is a special case
        if (doom.play.PSetup.numnodes == 0) {
            return doom.play.PSetup.subsectors[0];
        }

        nodenum = doom.play.PSetup.numnodes - 1;

        while ((nodenum & Node.NF_SUBSECTOR) == 0) {
            node = doom.play.PSetup.nodes[nodenum];
            side = rPointOnSide(x, y, node);
            nodenum = node.children[side];
        }

        return doom.play.PSetup.subsectors[nodenum & ~Node.NF_SUBSECTOR];
    }

    //
    // R_AddPointToBox
    // Expand a given bbox so that it encloses a given point.
    //
    // r_main.c lines 138–152.
    public static void rAddPointToBox(int x, int y, int[] box) {
        if (x < box[doom.play.MBbox.BOXLEFT]) {
            box[doom.play.MBbox.BOXLEFT] = x;
        }
        if (x > box[doom.play.MBbox.BOXRIGHT]) {
            box[doom.play.MBbox.BOXRIGHT] = x;
        }
        if (y < box[doom.play.MBbox.BOXBOTTOM]) {
            box[doom.play.MBbox.BOXBOTTOM] = y;
        }
        if (y > box[doom.play.MBbox.BOXTOP]) {
            box[doom.play.MBbox.BOXTOP] = y;
        }
    }

    //
    // R_InitTables — no-op in the C (the inline computation is
    // `#if 0`'d out and tables.c provides the actual data, loaded
    // here via Tables.dat). Kept as an empty stub so R_Init's
    // orchestration shape matches the C.
    public static void rInitTables() {
        // tables already loaded from Tables.dat at class-init time
    }

    //
    // R_InitPointToAngle — also no-op (`#if 0` in the C).
    public static void rInitPointToAngle() {
        // tantoangle[] already loaded from Tables.dat
    }

    //
    // R_InitTextureMapping
    //
    // r_main.c lines 544–603. Builds viewangletox[] and xtoviewangle[]
    // tables that map between view angles and screen-x columns. Uses
    // the per-class tan() lookup (finetangent) to do a flat-projection
    // mapping with focal length set so FIELDOFVIEW spans the full
    // screen width.
    public static void rInitTextureMapping() {
        int i;
        int x;
        int t;
        int focallength;

        // Calc focallength so FIELDOFVIEW angles covers SCREENWIDTH.
        focallength = Fixed.div(centerxfrac,
                Tables.finetangent[Tables.FINEANGLES / 4 + FIELDOFVIEW / 2]);

        for (i = 0; i < Tables.FINEANGLES / 2; i++) {
            if (Tables.finetangent[i] > Fixed.FRACUNIT * 2) {
                t = -1;
            } else if (Tables.finetangent[i] < -Fixed.FRACUNIT * 2) {
                t = viewwidth + 1;
            } else {
                t = Fixed.mul(Tables.finetangent[i], focallength);
                t = (centerxfrac - t + Fixed.FRACUNIT - 1) >> Fixed.FRACBITS;

                if (t < -1) {
                    t = -1;
                } else if (t > viewwidth + 1) {
                    t = viewwidth + 1;
                }
            }
            viewangletox[i] = t;
        }

        // Scan viewangletox[] to generate xtoviewangle[]:
        //  xtoviewangle will give the smallest view angle
        //  that maps to x.
        for (x = 0; x <= viewwidth; x++) {
            i = 0;
            while (viewangletox[i] > x) {
                i++;
            }
            xtoviewangle[x] = (i << Tables.ANGLETOFINESHIFT) - Tables.ANG90;
        }

        // Take out the fencepost cases from viewangletox.
        for (i = 0; i < Tables.FINEANGLES / 2; i++) {
            // C: t = FixedMul(...) ; t = centerx - t; — computed but
            // never read. The post-fixup just clamps the -1 / +1
            // sentinels back to the valid range.
            if (viewangletox[i] == -1) {
                viewangletox[i] = 0;
            } else if (viewangletox[i] == viewwidth + 1) {
                viewangletox[i] = viewwidth;
            }
        }

        clipangle = xtoviewangle[0];
    }

    //
    // R_InitLightTables
    // Initializes zlight[][]. scalelight[][] is built per view-size
    // change in R_ExecuteSetViewSize.
    //
    // r_main.c lines 614–642.
    public static void rInitLightTables() {
        int i;
        int j;
        int level;
        int startmap;
        int scale;

        for (i = 0; i < LIGHTLEVELS; i++) {
            startmap = ((LIGHTLEVELS - 1 - i) * 2) * NUMCOLORMAPS / LIGHTLEVELS;
            for (j = 0; j < MAXLIGHTZ; j++) {
                scale = Fixed.div((Doomdef.RENDER_WIDTH / 2) * Fixed.FRACUNIT,
                        (j + 1) << LIGHTZSHIFT);
                scale >>= LIGHTSCALESHIFT;
                level = startmap - scale / DISTMAP;

                if (level < 0) {
                    level = 0;
                }
                if (level >= NUMCOLORMAPS) {
                    level = NUMCOLORMAPS - 1;
                }

                // C: zlight[i][j] = colormaps + level*256.
                // Java: store the byte-offset into RData.colormaps.
                zlight[i][j] = level * 256;
            }
        }
    }

    //
    // R_SetViewSize
    // Queue a view-size change for the next refresh.
    //
    // r_main.c lines 657–665.
    public static void rSetViewSize(int blocks, int detail) {
        setsizeneeded = true;
        setblocks = blocks;
        setdetail = detail;
    }

    //
    // R_ExecuteSetViewSize
    //
    // r_main.c lines 671–761. Big setup step: derives every per-
    // view-size global from setblocks/setdetail. Calls
    // R_InitTextureMapping and R_InitBuffer; rebuilds scalelight[][]
    // (zlight[][] is independent of view size, built once in
    // R_InitLightTables).
    public static void rExecuteSetViewSize() {
        int cosadj;
        int dy;
        int i;
        int j;
        int level;
        int startmap;

        setsizeneeded = false;

        if (setblocks == 11) {
            RDraw.scaledviewwidth = Doomdef.RENDER_WIDTH;
            RDraw.viewheight = Doomdef.RENDER_HEIGHT;
        } else {
            // Per-block step is 32px wide / 16.8px tall on the
            // 320×200 design grid; scale by RENDER_MUL so the
            // screen-size slider produces the same fraction of
            // the framebuffer at any RENDER_MUL.
            RDraw.scaledviewwidth = setblocks * 32 * Doomdef.RENDER_MUL;
            RDraw.viewheight = (setblocks * 168 / 10 * Doomdef.RENDER_MUL) & ~7;
        }

        detailshift = setdetail;
        viewwidth = RDraw.scaledviewwidth >> detailshift;

        centery = RDraw.viewheight / 2;
        centerx = viewwidth / 2;
        centerxfrac = centerx << Fixed.FRACBITS;
        centeryfrac = centery << Fixed.FRACBITS;
        projection = centerxfrac;

        // colfunc / spanfunc dispatch: r_draw has both detailshift
        // variants. The Java port doesn't use a function-pointer
        // trampoline since the renderer hot path is not a
        // bottleneck — RDraw.rDrawColumn / rDrawSpan are picked at
        // call sites. Bind viewwidth + scaledviewwidth so RDraw can
        // see them.
        RDraw.viewwidth = viewwidth;

        RDraw.rInitBuffer(RDraw.scaledviewwidth, RDraw.viewheight);

        rInitTextureMapping();

        // psprite scales. Divide by BASE_WIDTH (the 320-pixel
        // logical grid) rather than RENDER_WIDTH so the weapon
        // sprite stays the right on-screen size at RENDER_MUL > 1
        // — otherwise the scale collapses to 1.0 = native sprite
        // pixels = half-size at 640 wide.
        pspritescale = Fixed.FRACUNIT * viewwidth / Doomdef.BASE_WIDTH;
        pspriteiscale = Fixed.FRACUNIT * Doomdef.BASE_WIDTH / viewwidth;

        // thing clipping
        for (i = 0; i < viewwidth; i++) {
            screenheightarray[i] = (short) RDraw.viewheight;
        }

        // planes
        for (i = 0; i < RDraw.viewheight; i++) {
            dy = ((i - RDraw.viewheight / 2) << Fixed.FRACBITS) + Fixed.FRACUNIT / 2;
            dy = Math.abs(dy);
            yslope[i] = Fixed.div(
                    (viewwidth << detailshift) / 2 * Fixed.FRACUNIT, dy);
        }

        for (i = 0; i < viewwidth; i++) {
            cosadj = Math.abs(Tables.finecosine(
                    xtoviewangle[i] >>> Tables.ANGLETOFINESHIFT));
            distscale[i] = Fixed.div(Fixed.FRACUNIT, cosadj);
        }

        // Calculate the light levels to use for each level / scale
        // combination.
        for (i = 0; i < LIGHTLEVELS; i++) {
            startmap = ((LIGHTLEVELS - 1 - i) * 2) * NUMCOLORMAPS / LIGHTLEVELS;
            for (j = 0; j < MAXLIGHTSCALE; j++) {
                level = startmap - j * Doomdef.RENDER_WIDTH
                        / (viewwidth << detailshift) / DISTMAP;

                if (level < 0) {
                    level = 0;
                }
                if (level >= NUMCOLORMAPS) {
                    level = NUMCOLORMAPS - 1;
                }

                scalelight[i][j] = level * 256;
            }
        }
    }

    //
    // R_Init
    //
    // r_main.c lines 773–794. The C orchestrator. The Java port
    // skips R_InitData (already done from RData.rInitData when the
    // WAD loads) and the printf chatter.
    //
    // detailLevel / screenblocks come from m_misc default settings
    // (mLoadDefaults runs ahead of rInit in dDoomMain). screenblocks
    // 10 = play view sized to leave a status-bar margin; 11 = full
    // screen. The C reads the globals directly; the Java port pulls
    // them from MMenu where mLoadDefaults wires them.
    public static void rInit() {
        rInitPointToAngle();
        rInitTables();
        rSetViewSize(doom.ui.MMenu.screenblocks, doom.ui.MMenu.detailLevel);
        rExecuteSetViewSize();
        RPlane.rInitPlanes();
        rInitLightTables();
        RSky.rInitSkyMap();
        RDraw.rInitTranslationTables();

        framecount = 0;
    }

    //
    // R_SetupFrame
    //
    // r_main.c lines 830–863. Per-tic eye-vector update. The C
    // sets walllights = scalelightfixed when fixedcolormap is
    // active; the Java port stores the colormap-offset table.
    public static void rSetupFrame(Player player) {
        int i;

        viewplayer = player;
        viewx = player.mo.x;
        viewy = player.mo.y;
        viewangle = player.mo.angle + viewangleoffset;
        extralight = player.extralight;

        viewz = player.viewz;

        viewsin = Tables.finesine[(viewangle >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK];
        viewcos = Tables.finecosine((viewangle >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK);

        sscount = 0;

        if (player.fixedcolormap != 0) {
            // C: fixedcolormap = colormaps + player.fixedcolormap * 256.
            fixedcolormap = player.fixedcolormap * 256;
            walllights = scalelightfixed;
            for (i = 0; i < MAXLIGHTSCALE; i++) {
                scalelightfixed[i] = fixedcolormap;
            }
        } else {
            fixedcolormap = -1;
        }

        framecount++;
        validcount++;
    }

    //
    // R_RenderPlayerView
    //
    // r_main.c lines 870–898.
    public static void rRenderPlayerView(Player player) {
        rSetupFrame(player);

        // Clear buffers.
        RBsp.rClearClipSegs();
        RBsp.rClearDrawSegs();
        RPlane.rClearPlanes();
        RThings.rClearSprites();

        // C: NetUpdate () — defer until d_net ports.

        // The head node is the last node output.
        RBsp.rRenderBSPNode(PSetup.numnodes - 1);

        RPlane.rDrawPlanes();
        RThings.rDrawMasked();
    }

    //
    // R_ScaleFromGlobalAngle
    // Returns the texture mapping scale for the current line at the
    // given angle. rw_distance must be calculated first.
    //
    // r_main.c lines 453–502. r_segs is currently a stub, but
    // R_ScaleFromGlobalAngle is referenced through the C as a public
    // r_main entry — keep it on RMain so the eventual r_segs port
    // can call into it without restructuring.
    public static int rScaleFromGlobalAngle(int visangle) {
        int scale;
        int anglea;
        int angleb;
        int sinea;
        int sineb;
        int num;
        int den;

        anglea = Tables.ANG90 + (visangle - viewangle);
        angleb = Tables.ANG90 + (visangle - RSegs.rwNormalangle);

        // both sines are allways positive
        sinea = Tables.finesine[(anglea >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK];
        sineb = Tables.finesine[(angleb >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK];
        num = Fixed.mul(projection, sineb) << detailshift;
        den = Fixed.mul(RSegs.rwDistance, sinea);

        if (den > num >> 16) {
            scale = Fixed.div(num, den);

            if (scale > 64 * Fixed.FRACUNIT) {
                scale = 64 * Fixed.FRACUNIT;
            } else if (scale < 256) {
                scale = 256;
            }
        } else {
            scale = 64 * Fixed.FRACUNIT;
        }

        return scale;
    }

    private RMain() {
    }
}
