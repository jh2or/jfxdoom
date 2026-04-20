// Port of linuxdoom-1.10/r_defs.h degenmobj_t (lines 88–95).
// Each sector has one of these at its geometric centre, used as a
// sound origin. The embedded thinker_t is never actually wired into
// the thinker list — the comment in the C says "not used for anything".
package doom.render;

import doom.play.Thinker;

public final class Degenmobj {

    // The C embeds thinker_t as the first field so callers can cast
    // degenmobj_t* back to thinker_t*. Java uses composition.
    public Thinker thinker = new Thinker();

    public int x; // fixed_t
    public int y; // fixed_t
    public int z; // fixed_t
}
