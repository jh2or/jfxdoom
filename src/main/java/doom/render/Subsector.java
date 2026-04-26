// Port of linuxdoom-1.10/r_defs.h subsector_t (lines 227–233).
// A convex BSP leaf — a contiguous run of segs in `segs[]` that
// together bound a portion of a sector. P_GroupLines fills in
// `sector` after the segs have been loaded.
package doom.render;

public final class Subsector {

    public Sector sector;
    public int numlines;  // short — count of segs
    public int firstline; // short — first seg index in segs[]
}
