// Port of linuxdoom-1.10/p_local.h divline_t (lines 135–142).
// A line in 2D space expressed as a parametric ray (origin + delta),
// used by the move-clipping and line-of-sight code in p_maputl.c.
package doom.play;

public final class Divline {

    public int x;  // fixed_t — origin
    public int y;  // fixed_t
    public int dx; // fixed_t — delta (line direction × length)
    public int dy; // fixed_t
}
