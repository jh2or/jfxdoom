// Port of linuxdoom-1.10/am_map.c — stub carrying the cross-module
// `automapactive` flag until the full automap lands.
//
// hu_lib.c's erase path reads it; a fuller port of am_map.c replaces
// this class in place.
package doom.ui;

public final class AmMap {

    // am_map.c — set by AM_Start / cleared by AM_Stop. Other modules
    // (hu_lib, st_stuff, r_main) read it as a pure external.
    public static boolean automapactive;

    private AmMap() {
    }
}
