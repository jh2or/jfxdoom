// Port of linuxdoom-1.10/r_segs.c — stub.
//
// r_segs.c (746 lines, "Hard" tier) draws each visible wall segment
// column-by-column: solid walls go straight to R_DrawColumn, two-
// sided walls compute openings + populate maskedtexturecol so r_things
// can clip sprites against them later. The full body lands once the
// renderer scaffolding is in place — for now this stub carries only
// `R_StoreWallRange`, the entry point r_bsp.c calls for every visible
// seg range.
package doom.render;

public final class RSegs {

    // r_segs.c — R_StoreWallRange (stub).
    // Called by r_bsp's R_ClipSolidWallSegment / R_ClipPassWallSegment
    // for each visible column range of the current `curline` seg.
    // The stub appends a no-fill drawseg so the BSP walk still
    // produces a populated drawsegs[] list — r_things uses it for
    // sprite clipping. Once the full body lands, columns paint into
    // RDraw.dc_colormap / dc_source / RDraw.rDrawColumn.
    public static void rStoreWallRange(int start, int stop) {
        if (RBsp.dsP >= RBsp.MAXDRAWSEGS) {
            return;
        }
        Drawseg ds = RBsp.drawsegs[RBsp.dsP];
        if (ds == null) {
            ds = new Drawseg();
            RBsp.drawsegs[RBsp.dsP] = ds;
        }
        ds.curline = RBsp.curline;
        ds.x1 = start;
        ds.x2 = stop;
        ds.scale1 = 0;
        ds.scale2 = 0;
        ds.scalestep = 0;
        ds.silhouette = 0;
        ds.bsilheight = 0;
        ds.tsilheight = 0;
        ds.sprtopclip = null;
        ds.sprbottomclip = null;
        ds.maskedtexturecol = null;
        RBsp.dsP++;
    }

    private RSegs() {
    }
}
