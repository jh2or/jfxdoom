// Port of linuxdoom-1.10/info.h state_t (lines 1147–1156).
// One row of the global states[] table — the source-of-truth for
// sprite frame, tic duration, action callback, and successor state.
//
// The C `action` field is a union of three function-pointer
// signatures (`acv()`, `acp1(mobj_t*)`, `acp2(player_t*, pspdef_t*)`).
// Java has no union, so the port uses two parallel typed fields:
//   - `action`:    Consumer<Mobj>                — for monster-AI A_*
//   - `actionPsp`: BiConsumer<Player, Pspdef>    — for weapon-action A_*
// Only one is ever populated for a given state. P_SetMobjState reads
// `action`; P_SetPsprite reads `actionPsp`. See deviation 25.
package doom.play;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class State {

    public final int sprite;       // spritenum_t (Info.SPR_*)
    public final int frame;        // bits + FF_FULLBRIGHT optional flag
    public final int tics;         // dwell time before nextstate
    public final Consumer<Mobj> action;
    public final BiConsumer<Player, Pspdef> actionPsp;
    public final int nextstate;    // statenum_t (Info.S_*)
    public final int misc1;
    public final int misc2;

    // Mobj-action constructor (used by InfoData rows generated for
    // monster AI states). actionPsp defaults to null.
    public State(int sprite, int frame, int tics, Consumer<Mobj> action,
                 int nextstate, int misc1, int misc2) {
        this(sprite, frame, tics, action, null, nextstate, misc1, misc2);
    }

    // Full constructor — the InfoData regenerator uses this for
    // weapon-action rows once p_pspr A_* bindings are wired.
    public State(int sprite, int frame, int tics, Consumer<Mobj> action,
                 BiConsumer<Player, Pspdef> actionPsp,
                 int nextstate, int misc1, int misc2) {
        this.sprite = sprite;
        this.frame = frame;
        this.tics = tics;
        this.action = action;
        this.actionPsp = actionPsp;
        this.nextstate = nextstate;
        this.misc1 = misc1;
        this.misc2 = misc2;
    }
}
