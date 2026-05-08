// Port of linuxdoom-1.10/r_defs.h vissprite_t (lines 375–409).
//
// One per-frame sprite ready to draw. Allocated by R_NewVisSprite
// from the fixed pool; sorted back-to-front by R_SortVisSprites
// before R_DrawSprite paints them with drawseg-clipping.
package doom.render;

public final class Vissprite {

    // Doubly linked list links (used by R_SortVisSprites).
    public Vissprite prev;
    public Vissprite next;

    public int x1;
    public int x2;

    // for line side calculation
    public int gx;     // fixed_t
    public int gy;     // fixed_t

    // global bottom / top for silhouette clipping
    public int gz;     // fixed_t
    public int gzt;    // fixed_t

    // horizontal position of x1
    public int startfrac; // fixed_t

    public int scale;     // fixed_t

    // negative if flipped
    public int xiscale;   // fixed_t

    public int texturemid; // fixed_t
    public int patch;

    // colormap base + offset (C: lighttable_t*). null colormap +
    // negative offset = shadow draw (fuzzcolfunc).
    public byte[] colormap;
    public int    colormapOfs;

    public int mobjflags;
}
