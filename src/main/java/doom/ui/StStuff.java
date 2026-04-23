// Port of linuxdoom-1.10/st_stuff.c — stub carrying the status-bar
// layout constants st_lib.c's bounds-check reads.
//
// st_stuff.c itself (weapons pickups, face animation, palette flash,
// ST_Responder, ...) lands later. This class grows in place as that
// port happens.
package doom.ui;

import doom.system.Doomdef;

public final class StStuff {

    // st_stuff.h lines 32–34.
    // SCREEN_MUL is 1 in the port (see Doomdef), so the product folds
    // down to 32 without losing the C macro shape.
    public static final int ST_HEIGHT = 32 * Doomdef.SCREEN_MUL;
    public static final int ST_WIDTH = Doomdef.SCREENWIDTH;
    public static final int ST_Y = Doomdef.SCREENHEIGHT - ST_HEIGHT;

    private StStuff() {
    }
}
