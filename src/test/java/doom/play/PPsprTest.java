package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.render.Sector;
import doom.render.Subsector;
import doom.system.Doomdef;
import doom.system.Doomstat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PPsprTest {

    private Player player;
    private Sector sec;

    @BeforeEach
    void resetWorld() {
        sec = new Sector();
        sec.floorheight = 0;
        sec.ceilingheight = 200 * Fixed.FRACUNIT;
        sec.special = 0;
        sec.thinglist = null;

        Subsector ss = new Subsector();
        ss.sector = sec;

        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;
        PSetup.subsectors = new Subsector[] { ss };
        PSetup.numsubsectors = 1;
        PSetup.numnodes = 0;
        PSetup.nodes = new doom.render.Node[0];
        PSetup.bmaporgx = -64 * Fixed.FRACUNIT;
        PSetup.bmaporgy = -64 * Fixed.FRACUNIT;
        PSetup.bmapwidth = 1;
        PSetup.bmapheight = 1;
        PSetup.blocklinks = new Mobj[1];
        PSetup.blockmaplump = new int[] { -64, -64, 1, 1, 5, -1 };
        PSetup.blockmap = PSetup.blockmaplump;

        PTick.pInitThinkers();
        PMap.changeSectorOverride = true;
        PMap.changeSectorResult = false;
        Doomstat.gameskill = Doomstat.sk_medium;
        Doomstat.gamemode = Doomstat.GameMode.shareware;

        Mobj mo = new Mobj();
        mo.x = 0; mo.y = 0; mo.z = 0;
        mo.angle = 0;
        mo.subsector = ss;
        mo.height = 56 * Fixed.FRACUNIT;
        mo.info = Info.mobjinfo[Info.MT_PLAYER];
        mo.flags = Mobj.MF_SOLID | Mobj.MF_SHOOTABLE | Mobj.MF_PICKUP;
        mo.health = 100;
        mo.state = Info.states[Info.S_PLAY];
        mo.type = Info.MT_PLAYER;

        player = new Player();
        player.mo = mo;
        mo.player = player;
        player.health = 100;
        player.readyweapon = Doomdef.wp_pistol;
        player.pendingweapon = Doomdef.wp_nochange;
        for (int i = 0; i < Doomdef.NUMWEAPONS; i++) {
            player.weaponowned[i] = false;
        }
        player.weaponowned[Doomdef.wp_fist] = true;
        player.weaponowned[Doomdef.wp_pistol] = true;
        for (int i = 0; i < Doomdef.NUMAMMO; i++) {
            player.ammo[i] = 50;
            player.maxammo[i] = PInter.maxammo[i];
        }
    }

    // --- P_SetPsprite ---

    @Test
    void setPspriteToSNullDeactivatesSlot() {
        // Pre-condition: state populated.
        player.psprites[PPspr.ps_weapon].state = Info.states[Info.S_PISTOL];
        PPspr.pSetPsprite(player, PPspr.ps_weapon, Info.S_NULL);
        assertNull(player.psprites[PPspr.ps_weapon].state);
    }

    @Test
    void setPspriteAdvancesToStateAndCopiesTics() {
        PPspr.pSetPsprite(player, PPspr.ps_weapon, Info.S_PISTOL);
        Pspdef psp = player.psprites[PPspr.ps_weapon];
        assertSame(Info.states[Info.S_PISTOL], psp.state);
        assertEquals(Info.states[Info.S_PISTOL].tics, psp.tics);
    }

    // --- P_CheckAmmo ---

    @Test
    void checkAmmoReturnsTrueWhenAmmoSufficient() {
        player.readyweapon = Doomdef.wp_pistol;
        player.ammo[Doomdef.am_clip] = 50;
        assertTrue(PPspr.pCheckAmmo(player));
    }

    @Test
    void checkAmmoFistAlwaysReturnsTrue() {
        player.readyweapon = Doomdef.wp_fist;
        // Fist uses am_noammo → unconditional pass.
        for (int i = 0; i < Doomdef.NUMAMMO; i++) player.ammo[i] = 0;
        assertTrue(PPspr.pCheckAmmo(player));
    }

    @Test
    void checkAmmoOutOfAmmoSwitchesToBackupWeapon() {
        player.readyweapon = Doomdef.wp_pistol;
        player.ammo[Doomdef.am_clip] = 0;
        player.ammo[Doomdef.am_shell] = 4;
        player.weaponowned[Doomdef.wp_shotgun] = true;

        assertFalse(PPspr.pCheckAmmo(player));
        assertEquals(Doomdef.wp_shotgun, player.pendingweapon);
    }

    @Test
    void checkAmmoFallsThroughToFistWhenAllAmmoEmpty() {
        player.readyweapon = Doomdef.wp_pistol;
        for (int i = 0; i < Doomdef.NUMAMMO; i++) player.ammo[i] = 0;
        // Only fist + pistol owned.
        assertFalse(PPspr.pCheckAmmo(player));
        assertEquals(Doomdef.wp_fist, player.pendingweapon);
    }

    // --- P_BringUpWeapon ---

    @Test
    void bringUpWeaponSetsPendingToReadyWhenNochange() {
        player.pendingweapon = Doomdef.wp_nochange;
        player.readyweapon = Doomdef.wp_pistol;

        PPspr.pBringUpWeapon(player);

        // pendingweapon is reset to wp_nochange after consumption.
        assertEquals(Doomdef.wp_nochange, player.pendingweapon);
        // psp.sy snapped to WEAPONBOTTOM.
        assertEquals(PPspr.WEAPONBOTTOM, player.psprites[PPspr.ps_weapon].sy);
    }

    // --- P_FireWeapon ---

    @Test
    void fireWeaponWithAmmoFlipsPlayerToATK1AndSetsPsprite() {
        player.readyweapon = Doomdef.wp_pistol;
        player.ammo[Doomdef.am_clip] = 10;

        PPspr.pFireWeapon(player);

        assertSame(Info.states[Info.S_PLAY_ATK1], player.mo.state);
    }

    @Test
    void fireWeaponWithoutAmmoSkipsAndPicksBackup() {
        player.readyweapon = Doomdef.wp_pistol;
        for (int i = 0; i < Doomdef.NUMAMMO; i++) player.ammo[i] = 0;

        PPspr.pFireWeapon(player);

        // Out-of-ammo path → checkAmmo flipped pending; player mo is
        // still in S_PLAY (not ATK1).
        assertSame(Info.states[Info.S_PLAY], player.mo.state);
    }

    // --- A_Light0 / A_Light1 / A_Light2 ---

    @Test
    void lightActionsSetExtralight() {
        Pspdef psp = player.psprites[PPspr.ps_weapon];
        PPspr.aLight0(player, psp);
        assertEquals(0, player.extralight);
        PPspr.aLight1(player, psp);
        assertEquals(1, player.extralight);
        PPspr.aLight2(player, psp);
        assertEquals(2, player.extralight);
    }

    // --- A_FirePistol: ammo decrement + state change ---

    @Test
    void firePistolDecrementsClipAmmoAndFiresATK2() {
        player.readyweapon = Doomdef.wp_pistol;
        int before = player.ammo[Doomdef.am_clip] = 50;

        Pspdef psp = player.psprites[PPspr.ps_weapon];
        PPspr.aFirePistol(player, psp);

        assertEquals(before - 1, player.ammo[Doomdef.am_clip]);
        assertSame(Info.states[Info.S_PLAY_ATK2], player.mo.state);
    }

    // --- A_FireMissile: rocket spawn + ammo decrement ---

    @Test
    void fireMissileSpawnsRocketAndDecrementsAmmo() {
        player.readyweapon = Doomdef.wp_missile;
        player.weaponowned[Doomdef.wp_missile] = true;
        player.ammo[Doomdef.am_misl] = 5;

        Pspdef psp = player.psprites[PPspr.ps_weapon];
        PPspr.aFireMissile(player, psp);

        assertEquals(4, player.ammo[Doomdef.am_misl]);
        // Most-recent spawned mobj is the rocket.
        Mobj last = (Mobj) PTick.thinkercap.prev;
        assertEquals(Info.MT_ROCKET, last.type);
    }

    // --- A_FireBFG: 40-cell consumption + BFG spawn ---

    @Test
    void fireBFGConsumes40Cells() {
        player.readyweapon = Doomdef.wp_bfg;
        player.weaponowned[Doomdef.wp_bfg] = true;
        player.ammo[Doomdef.am_cell] = 100;

        Pspdef psp = player.psprites[PPspr.ps_weapon];
        PPspr.aFireBFG(player, psp);

        assertEquals(60, player.ammo[Doomdef.am_cell]);
        Mobj last = (Mobj) PTick.thinkercap.prev;
        assertEquals(Info.MT_BFG, last.type);
    }

    // --- A_Lower / A_Raise ---

    @Test
    void aLowerKeepsBelowBottomReturnsEarly() {
        Pspdef psp = player.psprites[PPspr.ps_weapon];
        psp.sy = PPspr.WEAPONTOP; // start at top

        PPspr.aLower(player, psp);

        // sy advanced by LOWERSPEED but still well above bottom.
        assertEquals(PPspr.WEAPONTOP + PPspr.LOWERSPEED, psp.sy);
    }

    @Test
    void aRaiseKeepsAboveTopReturnsEarly() {
        Pspdef psp = player.psprites[PPspr.ps_weapon];
        psp.sy = PPspr.WEAPONBOTTOM;

        PPspr.aRaise(player, psp);
        assertEquals(PPspr.WEAPONBOTTOM - PPspr.RAISESPEED, psp.sy);
    }

    // --- P_SetupPsprites + P_MovePsprites ---

    @Test
    void setupPspritesClearsAndBringsUp() {
        // Pre-populate one slot.
        player.psprites[PPspr.ps_flash].state = Info.states[Info.S_PISTOL];

        PPspr.pSetupPsprites(player);

        // ps_flash got cleared, ps_weapon got pBringUpWeapon → state
        // populated.
        assertNull(player.psprites[PPspr.ps_flash].state);
        assertEquals(PPspr.WEAPONBOTTOM, player.psprites[PPspr.ps_weapon].sy);
    }

    // --- Constants sanity ---

    @Test
    void constantsMatchCSource() {
        assertEquals(0, PPspr.ps_weapon);
        assertEquals(1, PPspr.ps_flash);
        assertEquals(2, PPspr.NUMPSPRITES);
        assertEquals(6 * Fixed.FRACUNIT, PPspr.LOWERSPEED);
        assertEquals(6 * Fixed.FRACUNIT, PPspr.RAISESPEED);
        assertEquals(128 * Fixed.FRACUNIT, PPspr.WEAPONBOTTOM);
        assertEquals(32 * Fixed.FRACUNIT, PPspr.WEAPONTOP);
        assertEquals(40, PPspr.BFGCELLS);
    }
}
