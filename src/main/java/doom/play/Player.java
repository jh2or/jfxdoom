// Port of linuxdoom-1.10/d_player.h player_t — stub placeholder.
// The renderer only reads `player->mo`, `player->viewz`, `player->extralight`,
// `player->fixedcolormap`. The full player_t lives alongside the rest of
// the playsim port. Only the fields R_SetupFrame touches are declared here
// so RMain can compile cleanly; everything else will be added when
// d_player.h gets its own port.
package doom.play;

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
}
