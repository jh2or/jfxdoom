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
// P_Ticker is the per-tic playsim entry point — D_DoomLoop calls it
// once per game tic. It pumps player input → P_PlayerThink, advances
// every thinker, runs the sector-special updates, and ages the item-
// respawn queue.
package doom.play;

import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.GGame;

public final class PTick {

    // p_tick.c line 34 — int leveltime;
    public static int leveltime;

    // d_main.c line 78 — gametic. Total tic counter across all
    // levels (vs. leveltime, which resets each level). p_enemy.c
    // A_Tracer reads `gametic & 3` to fire its smoke trail every
    // 4th tic. Lives here next to leveltime; the d_main port will
    // re-export from there if needed.
    public static int gametic;

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
    public static void pTicker() {
        int i;

        // run the tic
        if (Doomstat.paused) {
            return;
        }

        // pause if in menu and at least one tic has been run
        if (!Doomstat.netgame
                && doom.ui.MMenu.menuactive
                && !Doomstat.demoplayback
                && GGame.players[GGame.consoleplayer].viewz != 1) {
            return;
        }


        for (i = 0; i < Doomdef.MAXPLAYERS; i++) {
            if (GGame.playeringame[i]) {
                PUser.pPlayerThink(GGame.players[i]);
            }
        }

        pRunThinkers();
        PSpec.pUpdateSpecials();
        PMobj.pRespawnSpecials();

        // for par times
        leveltime++;
    }

    private PTick() {
    }
}
