// Port of linuxdoom-1.10/doomdef.h — global constants only.
// The enums and game-state structures are left for later passes; this class
// exists to hold the handful of screen / timing constants that many lower
// modules depend on (V_Video, R_Data, etc.).
package doom.system;

public final class Doomdef {

    // doomdef.h line 98
    public static final int BASE_WIDTH = 320;

    // doomdef.h lines 104–105
    public static final int SCREEN_MUL = 1;
    public static final double INV_ASPECT_RATIO = 0.625;

    // doomdef.h lines 110–112
    public static final int SCREENWIDTH = 320;
    public static final int SCREENHEIGHT = 200;

    // doomdef.h line 119
    public static final int MAXPLAYERS = 4;

    // doomdef.h line 122 — game tick rate.
    public static final int TICRATE = 35;

    private Doomdef() {
    }
}
