// Port of linuxdoom-1.10/p_local.h intercept_t (lines 144–152).
// One entry in the trace-intercept list. The C uses an `isaline`
// boolean + a `union { mobj_t* thing; line_t* line; } d` to hold
// either pointer in the same word; Java has no unions, so each slot
// gets its own typed field. `isaline` selects which one is meaningful.
package doom.play;

import doom.render.Line;

public final class Intercept {

    public int frac;       // fixed_t — fraction along the trace
    public boolean isaline;

    // Set when isaline == true. Null otherwise.
    public Line line;

    // Set when isaline == false. Null otherwise.
    public Mobj thing;
}
