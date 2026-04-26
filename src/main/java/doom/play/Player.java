// Port of linuxdoom-1.10/d_player.h player_t — partial.
// Fields are added as their consumers port. The full player_t carries
// 30+ fields (powers, ammo inventory, weapon flags, intermission stats,
// pspr overlays, etc.) — the rest land alongside p_user.c, p_inter.c,
// and p_pspr.c.
package doom.play;

import doom.system.Doomdef;

public class Player {

    // d_player.h — the player's mobj (position + orientation source).
    public Mobj mo;

    // d_player.h — camera height above feet.
    public int viewz; // fixed_t

    // d_player.h — additive brightness from gun flash.
    public int extralight;

    // d_player.h — -1 = normal, otherwise a fixed colormap index
    // (invulnerability sphere, light amp goggles).
    public int fixedcolormap;

    // d_player.h line 109 — keycard / skull-key inventory.
    // Indexed by card_t (it_bluecard ... it_redskull).
    public boolean[] cards = new boolean[Doomdef.NUMCARDS];

    // d_player.h line 140 — short HUD message (locked-door denial,
    // pickup acknowledgement, etc.). null when no message active.
    public String message;
}
