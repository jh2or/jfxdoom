// Port of linuxdoom-1.10/d_think.h — thinker_t and the actionf_t union.
//
// Doom's playsim runs every actor (moving monsters, platforms, doors,
// lights, ...) off a single doubly-linked list of `thinker_t`. Each
// entry carries one function pointer out of three shapes (void,
// one-arg, two-arg), represented in C as a union so the caller can
// pick the right signature for the kind of thinker it spawned.
package doom.play;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class Thinker {

    // d_think.h lines 41–51 — the three actionf typedefs and their
    // tagged union.
    //   typedef void (*actionf_v)();
    //   typedef void (*actionf_p1)(void*);
    //   typedef void (*actionf_p2)(void*, void*);
    //
    // In the C union the three slots share storage; P_RemoveThinker's
    // "acv = (actionf_v)(-1)" sentinel also clobbers the other two.
    // Java has no unions, so each slot lives in its own typed field
    // and the "marked for removal" sentinel moves onto Thinker itself
    // as a boolean (see `removed` below). Only one slot is populated
    // for any given thinker — same contract as the C.
    public static final class Actionf {
        public Runnable acv;                         // actionf_v
        public Consumer<Thinker> acp1;               // actionf_p1
        public BiConsumer<Thinker, Thinker> acp2;    // actionf_p2
    }

    // d_think.h lines 64–70 — the list node. Subtype instances
    // (mobj_t, fireflicker_t, ...) subclass Thinker so the list can
    // hold anything uniformly.
    public Thinker prev;
    public Thinker next;
    public Actionf function = new Actionf();

    // Deviation from C: the C uses `function.acv = (actionf_v)(-1)` as
    // the "marked for removal" sentinel. Java cannot cast an int to a
    // function reference, so we carry a boolean. P_RemoveThinker sets
    // it; P_RunThinkers checks it. Observable semantics are identical
    // — no C code compares acv against arbitrary values, only against
    // the -1 sentinel.
    public boolean removed;
}
