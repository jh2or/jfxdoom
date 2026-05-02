// Port of linuxdoom-1.10/d_player.h player_t — partial.
// Fields are added as their consumers port. The full player_t carries
// 30+ fields (powers, ammo inventory, weapon flags, intermission stats,
// pspr overlays, etc.) — the rest land alongside p_user.c, p_inter.c,
// and p_pspr.c.
package doom.play;

import doom.system.Doomdef;

public class Player {

    // d_player.h lines 53–62 — playerstate_t.
    public static final int PST_LIVE   = 0;
    public static final int PST_DEAD   = 1;
    public static final int PST_REBORN = 2;

    // d_player.h — the player's mobj (position + orientation source).
    public Mobj mo;

    // d_player.h line 86 — current life-cycle state. P_SpawnPlayer
    // sets PST_LIVE on a fresh spawn; G_PlayerReborn flips to LIVE
    // after a death.
    public int playerstate;

    // d_player.h — camera height above feet.
    public int viewz; // fixed_t

    // d_player.h line 94 — base height above floor for viewz.
    public int viewheight; // fixed_t

    // d_player.h — additive brightness from gun flash.
    public int extralight;

    // d_player.h — -1 = normal, otherwise a fixed colormap index
    // (invulnerability sphere, light amp goggles).
    public int fixedcolormap;

    // d_player.h line 102 — between-levels health snapshot. During a
    // level mo->health is authoritative.
    public int health;

    // d_player.h line 109 — keycard / skull-key inventory.
    // Indexed by card_t (it_bluecard ... it_redskull).
    public boolean[] cards = new boolean[Doomdef.NUMCARDS];

    // d_player.h line 132 — refire counter. Repeated firing reduces
    // accuracy; reset to 0 on a fresh spawn.
    public int refire;

    // d_player.h line 140 — short HUD message (locked-door denial,
    // pickup acknowledgement, etc.). null when no message active.
    public String message;

    // d_player.h lines 143–144 — screen flash counters (red after
    // damage, gold after pickup). Decremented by p_user / st_stuff.
    public int damagecount;
    public int bonuscount;
}
