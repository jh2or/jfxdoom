// Port of linuxdoom-1.10/r_defs.h slopetype_t (lines 168–175).
// Move-clipping hint for line_t.
package doom.render;

public enum Slopetype {
    ST_HORIZONTAL,
    ST_VERTICAL,
    ST_POSITIVE,
    ST_NEGATIVE
}
