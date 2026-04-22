// Port of linuxdoom-1.10/p_tick.c — Thinker list management + the
// per-tic playsim entry point.
//
// Every moving/animating actor in the game lives on a single doubly
// linked list anchored by `thinkercap`, a self-referential sentinel.
// Subsystems (p_mobj, p_lights, p_doors, ...) allocate their own
// extended thinker structs via Z_Malloc, set an action function, and
// hand them to `P_AddThinker`. Each tic, `P_RunThinkers` walks the
// list, calls each function, and lazily unlinks anything flagged for
// removal.
//
// Partial: P_Ticker is not yet translated — it calls P_PlayerThink,
// P_UpdateSpecials, P_RespawnSpecials, and references the paused /
// menuactive / netgame globals and the players[] array, all of which
// belong to modules still ahead of us in the dependency order.
package doom.play;

public final class PTick {

    // p_tick.c line 34 — int leveltime;
    public static int leveltime;

    // p_tick.c line 47 — thinker_t thinkercap;
    // Both head and tail of the thinker list. Sentinel node: its
    // `function` slot is never invoked.
    public static Thinker thinkercap = new Thinker();

    // p_tick.c lines 53–56 — P_InitThinkers.
    public static void pInitThinkers() {
        thinkercap.prev = thinkercap.next = thinkercap;
    }

    // p_tick.c lines 65–71 — P_AddThinker. Append at the tail.
    public static void pAddThinker(Thinker thinker) {
        thinkercap.prev.next = thinker;
        thinker.next = thinkercap;
        thinker.prev = thinkercap.prev;
        thinkercap.prev = thinker;
    }

    // p_tick.c lines 80–84 — P_RemoveThinker.
    // Deallocation is lazy — it will not actually be freed until its
    // thinking turn comes up. The C stamps `function.acv = -1`; the
    // Java port flips the `removed` flag (see Thinker.java note).
    public static void pRemoveThinker(Thinker thinker) {
        // FIXME: NOP.  -- (comment from the C source, preserved)
        thinker.removed = true;
    }

    // p_tick.c lines 92–94 — P_AllocateThinker. No-op in the C; the
    // comment block declares it allocates memory, but the body is
    // empty.
    public static void pAllocateThinker(Thinker thinker) {
    }

    // p_tick.c lines 101–122 — P_RunThinkers.
    public static void pRunThinkers() {
        Thinker currentthinker = thinkercap.next;
        while (currentthinker != thinkercap) {
            if (currentthinker.removed) {
                // time to remove it
                currentthinker.next.prev = currentthinker.prev;
                currentthinker.prev.next = currentthinker.next;
                // C: Z_Free(currentthinker). Thinkers are Java objects
                // not Z_Malloc'd byte[], so the GC reclaims them once
                // we drop the reference by unlinking above. We do
                // tell the zone to release any payload-wrapping byte[]
                // that might still be tracked under the thinker — a
                // no-op until subclasses actually allocate through
                // the zone.
                currentthinker = currentthinker.next;
                continue;
            }

            if (currentthinker.function.acp1 != null) {
                currentthinker.function.acp1.accept(currentthinker);
            }
            currentthinker = currentthinker.next;
        }
    }

    // p_tick.c lines 130–158 — P_Ticker.
    // Deferred: depends on P_PlayerThink, P_UpdateSpecials,
    // P_RespawnSpecials and the players[]/paused/menuactive globals
    // that still live in not-yet-ported modules. When those land the
    // body moves here verbatim.

    private PTick() {
    }
}
