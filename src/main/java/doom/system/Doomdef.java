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

    // doomdef.h lines 162–173 — card_t (key cards).
    public static final int it_bluecard    = 0;
    public static final int it_yellowcard  = 1;
    public static final int it_redcard     = 2;
    public static final int it_blueskull   = 3;
    public static final int it_yellowskull = 4;
    public static final int it_redskull    = 5;
    public static final int NUMCARDS       = 6;

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
    public static final int KEY_ENTER     = 13;
    public static final int KEY_BACKSPACE = 127;
    public static final int KEY_TAB       = 9;

    // d_event.h key codes consumed by G_Responder.
    public static final int KEY_RIGHTARROW = 0xae;
    public static final int KEY_LEFTARROW  = 0xac;
    public static final int KEY_UPARROW    = 0xad;
    public static final int KEY_DOWNARROW  = 0xaf;
    public static final int KEY_ESCAPE     = 27;
    public static final int KEY_F1         = (0x80 + 0x3b);
    public static final int KEY_F2         = (0x80 + 0x3c);
    public static final int KEY_F3         = (0x80 + 0x3d);
    public static final int KEY_F4         = (0x80 + 0x3e);
    public static final int KEY_F5         = (0x80 + 0x3f);
    public static final int KEY_F6         = (0x80 + 0x40);
    public static final int KEY_F7         = (0x80 + 0x41);
    public static final int KEY_F8         = (0x80 + 0x42);
    public static final int KEY_F9         = (0x80 + 0x43);
    public static final int KEY_F10        = (0x80 + 0x44);
    public static final int KEY_F11        = (0x80 + 0x57);
    public static final int KEY_F12        = (0x80 + 0x58);
    public static final int KEY_MINUS      = 0x2d;
    public static final int KEY_EQUALS     = 0x3d;
    public static final int KEY_PAUSE      = 0xff;
    public static final int KEY_RSHIFT     = (0x80 + 0x36);
    public static final int KEY_RCTRL      = (0x80 + 0x1d);
    public static final int KEY_RALT       = (0x80 + 0x38);
    public static final int KEY_SPACE      = ' ';
    public static final int KEY_COMMA      = ',';
    public static final int KEY_PERIOD     = '.';

    // g_game.c — key-state table size.
    public static final int NUMKEYS = 256;

    // doomdef.h lines 215–224 — powertype_t. Indexed by Player.powers[].
    public static final int pw_invulnerability = 0;
    public static final int pw_strength        = 1;
    public static final int pw_invisibility    = 2;
    public static final int pw_ironfeet        = 3;
    public static final int pw_allmap          = 4;
    public static final int pw_infrared        = 5;
    public static final int NUMPOWERS          = 6;

    // doomdef.h lines 234–238 — powerduration_t.
    public static final int INVULNTICS = (30 * TICRATE);
    public static final int INVISTICS  = (60 * TICRATE);
    public static final int INFRATICS  = (120 * TICRATE);
    public static final int IRONTICS   = (60 * TICRATE);

    // p_local.h line 33 — player base / pickup-clamp health.
    public static final int MAXHEALTH = 100;

    // p_local.h line 61 — chase-commitment baseline. P_DamageMobj
    // sets target.threshold to this when the target acquires a new
    // chase target.
    public static final int BASETHRESHOLD = 100;

    // d_event.h lines 73–98 — buttoncode_t bits on ticcmd_t.buttons.
    public static final int BT_ATTACK       = 1;
    public static final int BT_USE          = 2;
    public static final int BT_SPECIAL      = 128;
    public static final int BT_SPECIALMASK  = 3;
    public static final int BT_CHANGE       = 4;
    public static final int BT_WEAPONMASK   = (8 + 16 + 32);
    public static final int BT_WEAPONSHIFT  = 3;

    // d_event.h lines 91–98 — buttoncode_t special-event sub-codes
    // when BT_SPECIAL is set.
    public static final int BTS_PAUSE       = 1;
    public static final int BTS_SAVEGAME    = 2;
    public static final int BTS_SAVEMASK    = (4 + 8 + 16);
    public static final int BTS_SAVESHIFT   = 2;

    private Doomdef() {
    }
}
