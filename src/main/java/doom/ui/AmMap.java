// Port of linuxdoom-1.10/am_map.c — stub carrying the cross-module
// `automapactive` flag until the full automap lands.
//
// hu_lib.c's erase path reads it; a fuller port of am_map.c replaces
// this class in place.
package doom.ui;

public final class AmMap {

    // am_map.h lines 26–28 — st_stuff's ST_Responder filters automap
    // mode-flip events by checking these tagged event_t.data1 values.
    public static final int AM_MSGHEADER  = ('a' << 24) + ('m' << 16);
    public static final int AM_MSGENTERED = AM_MSGHEADER | ('e' << 8);
    public static final int AM_MSGEXITED  = AM_MSGHEADER | ('x' << 8);

    // am_map.c — set by AM_Start / cleared by AM_Stop. Other modules
    // (hu_lib, st_stuff, r_main) read it as a pure external.
    public static boolean automapactive;

    // am_map.c — AM_Stop. Stub: clears `automapactive` so the renderer
    // returns to first-person view. The full body unbinds keys and
    // tears down the automap event hook; the stub keeps the call sites
    // (P_KillMobj's "switch view prior to dying" branch) live.
    public static void amStop() {
        automapactive = false;
    }

    private AmMap() {
    }
}
