// Port of linuxdoom-1.10/d_items.c.
// Static psprite (player-sprite) animation table indexed by weapontype_t:
// each entry is (ammo, upstate, downstate, readystate, atkstate, flashstate)
// — the state IDs that drive the weapon's draw/raise/fire animations
// in p_pspr.c.
package doom.play;

import doom.system.Doomdef;

public final class DItems {

    // d_items.h lines 33–43 — weaponinfo_t.
    // Made static because it lives in the array literal below; one
    // per weapontype_t slot.
    public static final class WeaponInfo {
        public final int ammo;
        public final int upstate;
        public final int downstate;
        public final int readystate;
        public final int atkstate;
        public final int flashstate;

        public WeaponInfo(int ammo, int upstate, int downstate,
                          int readystate, int atkstate, int flashstate) {
            this.ammo = ammo;
            this.upstate = upstate;
            this.downstate = downstate;
            this.readystate = readystate;
            this.atkstate = atkstate;
            this.flashstate = flashstate;
        }
    }

    //
    // PSPRITE ACTIONS for weapons.
    // This struct controls the weapon animations.
    //
    // Each entry is:
    //   ammo/amunition type
    //   upstate
    //   downstate
    //  readystate
    //  atkstate, i.e. attack/fire/hit frame
    //  flashstate, muzzle flash
    //
    public static final WeaponInfo[] weaponinfo = {
        // fist
        new WeaponInfo(Doomdef.am_noammo,
                Info.S_PUNCHUP, Info.S_PUNCHDOWN, Info.S_PUNCH,
                Info.S_PUNCH1, Info.S_NULL),
        // pistol
        new WeaponInfo(Doomdef.am_clip,
                Info.S_PISTOLUP, Info.S_PISTOLDOWN, Info.S_PISTOL,
                Info.S_PISTOL1, Info.S_PISTOLFLASH),
        // shotgun
        new WeaponInfo(Doomdef.am_shell,
                Info.S_SGUNUP, Info.S_SGUNDOWN, Info.S_SGUN,
                Info.S_SGUN1, Info.S_SGUNFLASH1),
        // chaingun
        new WeaponInfo(Doomdef.am_clip,
                Info.S_CHAINUP, Info.S_CHAINDOWN, Info.S_CHAIN,
                Info.S_CHAIN1, Info.S_CHAINFLASH1),
        // missile launcher
        new WeaponInfo(Doomdef.am_misl,
                Info.S_MISSILEUP, Info.S_MISSILEDOWN, Info.S_MISSILE,
                Info.S_MISSILE1, Info.S_MISSILEFLASH1),
        // plasma rifle
        new WeaponInfo(Doomdef.am_cell,
                Info.S_PLASMAUP, Info.S_PLASMADOWN, Info.S_PLASMA,
                Info.S_PLASMA1, Info.S_PLASMAFLASH1),
        // bfg 9000
        new WeaponInfo(Doomdef.am_cell,
                Info.S_BFGUP, Info.S_BFGDOWN, Info.S_BFG,
                Info.S_BFG1, Info.S_BFGFLASH1),
        // chainsaw
        new WeaponInfo(Doomdef.am_noammo,
                Info.S_SAWUP, Info.S_SAWDOWN, Info.S_SAW,
                Info.S_SAW1, Info.S_NULL),
        // super shotgun
        new WeaponInfo(Doomdef.am_shell,
                Info.S_DSGUNUP, Info.S_DSGUNDOWN, Info.S_DSGUN,
                Info.S_DSGUN1, Info.S_DSGUNFLASH1),
    };

    private DItems() {
    }
}
