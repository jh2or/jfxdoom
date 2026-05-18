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

    // d_player.h lines 68–77 — cheat_t bits on Player.cheats.
    public static final int CF_NOCLIP     = 1;
    public static final int CF_GODMODE    = 2;
    public static final int CF_NOMOMENTUM = 4;

    // d_player.h — the player's mobj (position + orientation source).
    public Mobj mo;

    // d_player.h line 86 — current life-cycle state. P_SpawnPlayer
    // sets PST_LIVE on a fresh spawn; G_PlayerReborn flips to LIVE
    // after a death.
    public int playerstate;

    // d_player.h line 87 — per-tic input sample. G_BuildTiccmd writes
    // each tick; PUser consumes for movement / weapon-change / use.
    public Ticcmd cmd = new Ticcmd();

    // d_player.h — camera height above feet.
    public int viewz; // fixed_t

    // d_player.h line 94 — base height above floor for viewz.
    public int viewheight; // fixed_t

    // d_player.h line 96 — bob/squat speed delta accumulated each
    // tic by P_CalcHeight.
    public int deltaviewheight; // fixed_t

    // d_player.h line 98 — bounded/scaled total momentum used to
    // drive view bob amplitude.
    public int bob; // fixed_t

    // d_player.h — additive brightness from gun flash.
    public int extralight;

    // d_player.h — -1 = normal, otherwise a fixed colormap index
    // (invulnerability sphere, light amp goggles).
    public int fixedcolormap;

    // d_player.h line 102 — between-levels health snapshot. During a
    // level mo->health is authoritative.
    public int health;

    // d_player.h line 103–105 — armor (between-levels snapshot for
    // health, armorpoints absorbs damage, armortype gates absorption
    // ratio).
    public int armorpoints;
    public int armortype;

    // d_player.h line 108 — power-up tic counters. Indexed by
    // Doomdef.pw_*. Strength counts up; the rest count down.
    public int[] powers = new int[Doomdef.NUMPOWERS];

    // d_player.h line 109 — keycard / skull-key inventory.
    // Indexed by card_t (it_bluecard ... it_redskull).
    public boolean[] cards = new boolean[Doomdef.NUMCARDS];

    // d_player.h line 110 — backpack pickup doubles maxammo.
    public boolean backpack;

    // d_player.h line 113 — frag count (kills of other players in
    // deathmatch).
    public int[] frags = new int[Doomdef.MAXPLAYERS];

    // d_player.h lines 114–117 — currently-held / pending weapon
    // (wp_* from Doomdef). pendingweapon is wp_nochange when the
    // player isn't switching.
    public int readyweapon;
    public int pendingweapon;

    // d_player.h lines 119–121 — weapon ownership + ammo inventory.
    public boolean[] weaponowned = new boolean[Doomdef.NUMWEAPONS];
    public int[] ammo = new int[Doomdef.NUMAMMO];
    public int[] maxammo = new int[Doomdef.NUMAMMO];

    // d_player.h lines 124–125 — debounce flags so holding a button
    // doesn't fire it every tic. P_PlayerThink rises the flag on
    // first press, clears on release.
    public int attackdown;
    public int usedown;

    // d_player.h line 129 — bit flags for cheats / debug aids
    // (CF_* on Player).
    public int cheats;

    // d_player.h line 132 — refire counter. Repeated firing reduces
    // accuracy; reset to 0 on a fresh spawn.
    public int refire;

    // d_player.h lines 135–137 — intermission stats.
    public int killcount;
    public int itemcount;
    public int secretcount;

    // d_player.h line 140 — short HUD message (locked-door denial,
    // pickup acknowledgement, etc.). null when no message active.
    public String message;

    // d_player.h lines 143–144 — screen flash counters (red after
    // damage, gold after pickup). Decremented by p_user / st_stuff.
    public int damagecount;
    public int bonuscount;

    // d_player.h line 147 — last attacker (NULL for floors/ceilings).
    // P_DeathThink turns the corpse to face it.
    public Mobj attacker;

    // d_player.h line 158 — overlay sprites (held weapon + muzzle
    // flash). Indexed by PPspr.ps_weapon / ps_flash.
    public Pspdef[] psprites = makePsprites();

    // d_player.h line 164 — true after exiting a secret level.
    // G_DoCompleted reads this to splat the secret-level node on
    // the Doom 1 world map.
    public boolean didsecret;

    private static Pspdef[] makePsprites() {
        Pspdef[] arr = new Pspdef[2 /* PPspr.NUMPSPRITES */];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = new Pspdef();
        }
        return arr;
    }
}
