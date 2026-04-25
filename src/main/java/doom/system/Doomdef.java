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

    // doomdef.h lines 180–197 — weapontype_t.
    public static final int wp_fist          = 0;
    public static final int wp_pistol        = 1;
    public static final int wp_shotgun       = 2;
    public static final int wp_chaingun      = 3;
    public static final int wp_missile       = 4;
    public static final int wp_plasma        = 5;
    public static final int wp_bfg           = 6;
    public static final int wp_chainsaw      = 7;
    public static final int wp_supershotgun  = 8;
    public static final int NUMWEAPONS       = 9;
    // No pending weapon change.
    public static final int wp_nochange      = 10;

    // doomdef.h lines 200–210 — ammotype_t.
    public static final int am_clip   = 0; // Pistol / chaingun ammo.
    public static final int am_shell  = 1; // Shotgun / double barreled shotgun.
    public static final int am_cell   = 2; // Plasma rifle, BFG.
    public static final int am_misl   = 3; // Missile launcher.
    public static final int NUMAMMO   = 4;
    public static final int am_noammo = 5; // Unlimited for chainsaw / fist.

    // doomdef.h lines 253–271 — key codes. More land as their
    // consumers port (m_menu, hu_stuff, g_game). Only the keys already
    // referenced by ported modules live here.
    public static final int KEY_ENTER = 13;
    public static final int KEY_BACKSPACE = 127;

    private Doomdef() {
    }
}
