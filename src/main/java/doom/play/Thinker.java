// Port of linuxdoom-1.10/d_think.h thinker_t — stub.
// Doom's doubly-linked list node for every "actor" (anything that ticks).
// The full body is filled in by p_tick.c / p_mobj.c; for now this class
// exists only so r_defs.h types (degenmobj_t) and the renderer compile
// against a stable typename.
package doom.play;

public class Thinker {

    // thinker_t.prev — d_think.h line 66.
    public Thinker prev;

    // thinker_t.next — d_think.h line 67.
    public Thinker next;

    // thinker_t.function — d_think.h line 68. The C uses a union of
    // `actionf_v`, `actionf_p1`, `actionf_p2` function pointers. In Java
    // the callsite will cast or switch on a concrete subtype; for now
    // the field is an opaque `Object` placeholder.
    public Object function;
}
