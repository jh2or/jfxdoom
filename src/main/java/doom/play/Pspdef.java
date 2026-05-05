// Port of linuxdoom-1.10/p_pspr.h pspdef_t (lines 68–75).
//
// Per-player overlay (held weapon / muzzle flash) drawn directly on
// the view screen at 320×200 coordinates. NUMPSPRITES lives on PPspr;
// each Player carries `psprites[NUMPSPRITES]`.
package doom.play;

public final class Pspdef {

    // null = not active
    public State state;
    public int tics;
    public int sx;        // fixed_t — screen-space x (320×200)
    public int sy;        // fixed_t — screen-space y (offset from top)

    public Pspdef() {
    }
}
