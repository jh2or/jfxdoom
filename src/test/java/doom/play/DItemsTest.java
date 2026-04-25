package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;

import doom.system.Doomdef;

import org.junit.jupiter.api.Test;

class DItemsTest {

    @Test
    void weaponinfoLengthMatchesNumWeapons() {
        assertEquals(Doomdef.NUMWEAPONS, DItems.weaponinfo.length);
    }

    // Spot-check: fist uses no ammo and routes through the PUNCH frames.
    @Test
    void fistMatchesCSource() {
        DItems.WeaponInfo w = DItems.weaponinfo[Doomdef.wp_fist];
        assertEquals(Doomdef.am_noammo, w.ammo);
        assertEquals(Info.S_PUNCHUP, w.upstate);
        assertEquals(Info.S_PUNCHDOWN, w.downstate);
        assertEquals(Info.S_PUNCH, w.readystate);
        assertEquals(Info.S_PUNCH1, w.atkstate);
        assertEquals(Info.S_NULL, w.flashstate); // fist has no flash
    }

    // Pistol: am_clip, full set including flash.
    @Test
    void pistolMatchesCSource() {
        DItems.WeaponInfo w = DItems.weaponinfo[Doomdef.wp_pistol];
        assertEquals(Doomdef.am_clip, w.ammo);
        assertEquals(Info.S_PISTOL, w.readystate);
        assertEquals(Info.S_PISTOL1, w.atkstate);
        assertEquals(Info.S_PISTOLFLASH, w.flashstate);
    }

    // Shotgun: am_shell. SGUN1 fires, SGUNFLASH1 muzzle.
    @Test
    void shotgunMatchesCSource() {
        DItems.WeaponInfo w = DItems.weaponinfo[Doomdef.wp_shotgun];
        assertEquals(Doomdef.am_shell, w.ammo);
        assertEquals(Info.S_SGUN, w.readystate);
        assertEquals(Info.S_SGUNFLASH1, w.flashstate);
    }

    // Chaingun: am_clip (shares pistol ammo).
    @Test
    void chaingunUsesClipAmmo() {
        DItems.WeaponInfo w = DItems.weaponinfo[Doomdef.wp_chaingun];
        assertEquals(Doomdef.am_clip, w.ammo);
        assertEquals(Info.S_CHAIN1, w.atkstate);
    }

    // Missile launcher: am_misl, dedicated.
    @Test
    void missileLauncherUsesMissileAmmo() {
        DItems.WeaponInfo w = DItems.weaponinfo[Doomdef.wp_missile];
        assertEquals(Doomdef.am_misl, w.ammo);
        assertEquals(Info.S_MISSILE1, w.atkstate);
    }

    // Plasma + BFG share am_cell.
    @Test
    void plasmaAndBfgShareCellAmmo() {
        assertEquals(Doomdef.am_cell, DItems.weaponinfo[Doomdef.wp_plasma].ammo);
        assertEquals(Doomdef.am_cell, DItems.weaponinfo[Doomdef.wp_bfg].ammo);
    }

    // Chainsaw: noammo, no flash (S_NULL).
    @Test
    void chainsawHasNoAmmoNoFlash() {
        DItems.WeaponInfo w = DItems.weaponinfo[Doomdef.wp_chainsaw];
        assertEquals(Doomdef.am_noammo, w.ammo);
        assertEquals(Info.S_NULL, w.flashstate);
        assertEquals(Info.S_SAW1, w.atkstate);
    }

    // Super shotgun: am_shell.
    @Test
    void superShotgunMatchesCSource() {
        DItems.WeaponInfo w = DItems.weaponinfo[Doomdef.wp_supershotgun];
        assertEquals(Doomdef.am_shell, w.ammo);
        assertEquals(Info.S_DSGUN1, w.atkstate);
        assertEquals(Info.S_DSGUNFLASH1, w.flashstate);
    }

    // Sanity-check that the enum constants from doomdef.h match the
    // C order — wp_fist=0 ... wp_supershotgun=8, NUMWEAPONS=9.
    @Test
    void weapontypeOrderMatchesCEnum() {
        assertEquals(0, Doomdef.wp_fist);
        assertEquals(1, Doomdef.wp_pistol);
        assertEquals(2, Doomdef.wp_shotgun);
        assertEquals(3, Doomdef.wp_chaingun);
        assertEquals(4, Doomdef.wp_missile);
        assertEquals(5, Doomdef.wp_plasma);
        assertEquals(6, Doomdef.wp_bfg);
        assertEquals(7, Doomdef.wp_chainsaw);
        assertEquals(8, Doomdef.wp_supershotgun);
        assertEquals(9, Doomdef.NUMWEAPONS);
        assertEquals(10, Doomdef.wp_nochange);
    }

    @Test
    void ammotypeOrderMatchesCEnum() {
        assertEquals(0, Doomdef.am_clip);
        assertEquals(1, Doomdef.am_shell);
        assertEquals(2, Doomdef.am_cell);
        assertEquals(3, Doomdef.am_misl);
        assertEquals(4, Doomdef.NUMAMMO);
        assertEquals(5, Doomdef.am_noammo);
    }

    // Info.* constants — verify the enum boundaries that DItems depends on.
    @Test
    void infoEnumBoundariesMatchCSource() {
        assertEquals(0, Info.S_NULL);                  // first state
        assertEquals(966, Info.S_TECH2LAMP4);          // last state
        assertEquals(967, Info.NUMSTATES);

        assertEquals(0, Info.SPR_TROO);                // first sprite
        assertEquals(137, Info.SPR_TLP2);              // last sprite
        assertEquals(138, Info.NUMSPRITES);

        assertEquals(0, Info.MT_PLAYER);               // first mobjtype
        assertEquals(136, Info.MT_MISC86);             // last mobjtype
        assertEquals(137, Info.NUMMOBJTYPES);
    }
}
