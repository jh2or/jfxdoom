// Port of linuxdoom-1.10/info.h state_t (lines 1147–1156).
// One row of the global states[] table — the source-of-truth for
// sprite frame, tic duration, action callback, and successor state.
//
// The action field is a callback into p_pspr.c / p_enemy.c. Until
// those modules port, all entries in InfoData.states[] hold null
// here and P_SetMobjState / P_SetPsprite skip the dispatch.
package doom.play;

import java.util.function.Consumer;

public final class State {

    public final int sprite;       // spritenum_t (Info.SPR_*)
    public final int frame;        // bits + FF_FULLBRIGHT optional flag
    public final int tics;         // dwell time before nextstate
    public final Consumer<Mobj> action;
    public final int nextstate;    // statenum_t (Info.S_*)
    public final int misc1;
    public final int misc2;

    public State(int sprite, int frame, int tics, Consumer<Mobj> action,
                 int nextstate, int misc1, int misc2) {
        this.sprite = sprite;
        this.frame = frame;
        this.tics = tics;
        this.action = action;
        this.nextstate = nextstate;
        this.misc1 = misc1;
        this.misc2 = misc2;
    }
}
