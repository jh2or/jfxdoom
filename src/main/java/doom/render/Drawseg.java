// Port of linuxdoom-1.10/r_defs.h drawseg_t (lines 322–347).
//
// One entry in the per-frame drawsegs[] list — a clipped span of one
// linedef's wall, with the per-column clip lists for sprite occlusion.
// r_bsp.c populates each drawseg via R_StoreWallRange (in r_segs.c);
// r_things.c reads them when drawing sprites.
package doom.render;

public final class Drawseg {

    // r_segs.c — silhouette codes packed into Drawseg.silhouette.
    public static final int SIL_NONE   = 0;
    public static final int SIL_BOTTOM = 1;
    public static final int SIL_TOP    = 2;
    public static final int SIL_BOTH   = 3;

    public Seg curline;
    public int x1;
    public int x2;

    public int scale1;     // fixed_t
    public int scale2;     // fixed_t
    public int scalestep;  // fixed_t

    // 0=none, 1=bottom, 2=top, 3=both
    public int silhouette;

    // do not clip sprites above this
    public int bsilheight; // fixed_t

    // do not clip sprites below this
    public int tsilheight; // fixed_t

    // Pointers to lists for sprite clipping. The C aliases these
    // into a shared `openings[MAXOPENINGS]` short[] buffer with
    // (x1)-offset pointer base; the Java port stores the buffer +
    // base offset as a (short[], int) pair, set by R_StoreWallRange.
    public short[] sprtopclip;
    public int     sprtopclipOffset;

    public short[] sprbottomclip;
    public int     sprbottomclipOffset;

    public short[] maskedtexturecol;
    public int     maskedtexturecolOffset;
}
