// Port of linuxdoom-1.10/p_pspr.c — full.
//
// Player-sprite (psprite) animation + weapon action callbacks.
// PPspr drives the held-weapon overlay, the gun-flash overlay, and
// the per-weapon A_* hooks bound by info.c's states[] table for
// each weapon-state row (raise / lower / ready / fire / flash /
// recoil).
//
// The C state_t.action union is split across two State fields:
// `action` for monster A_* (Consumer<Mobj>) and `actionPsp` for
// weapon A_* (BiConsumer<Player, Pspdef>). P_SetPsprite reads the
// latter; P_SetMobjState reads the former. See deviation 25.
//
// The InfoData regeneration that wires every weapon-state row's
// `actionPsp` to the appropriate PPspr method-reference is a
// follow-up; with the current null bindings, P_SetPsprite simply
// skips the dispatch and the gun cycles through frames without
// triggering action effects. The A_* callbacks here are still
// directly callable, so unit tests exercise them without going
// through the state machine.
package doom.play;

import doom.fixed.Fixed;
import doom.render.RMain;
import doom.render.Tables;
import doom.sound.SSound;
import doom.sound.Sounds;
import doom.system.Doomdef;
import doom.system.Doomstat;

public final class PPspr {

    // p_pspr.h lines 60–66 — psprnum_t.
    public static final int ps_weapon = 0;
    public static final int ps_flash  = 1;
    public static final int NUMPSPRITES = 2;

    // p_pspr.c lines 44–48 — animation tuning.
    public static final int LOWERSPEED   = Fixed.FRACUNIT * 6;
    public static final int RAISESPEED   = Fixed.FRACUNIT * 6;
    public static final int WEAPONBOTTOM = 128 * Fixed.FRACUNIT;
    public static final int WEAPONTOP    = 32 * Fixed.FRACUNIT;

    // p_pspr.c line 52 — plasma cells consumed per BFG attack.
    public static final int BFGCELLS = 40;

    // p_pspr.c lines 109–110 — view-swing offsets, written by
    // P_CalcSwing each tic. The C never reads these in p_pspr.c;
    // they're consumed by r_main when computing the gun overlay
    // origin.
    public static int swingx; // fixed_t
    public static int swingy; // fixed_t

    // p_pspr.c line 598 — slope to nearest target. P_GunShot reads
    // this on each pellet so a stream of bullets converges on the
    // auto-aimed mobj instead of the literal aim ray.
    public static int bulletslope; // fixed_t

    private PPspr() {
    }

    //
    // P_SetPsprite
    //
    // p_pspr.c lines 58–102.
    public static void pSetPsprite(Player player, int position, int stnum) {
        Pspdef psp;
        State state;

        psp = player.psprites[position];

        do {
            if (stnum == 0 /* S_NULL */) {
                // object removed itself
                psp.state = null;
                break;
            }

            state = Info.states[stnum];
            psp.state = state;
            psp.tics = state.tics;      // could be 0

            if (state.misc1 != 0) {
                // coordinate set
                psp.sx = state.misc1 << Fixed.FRACBITS;
                psp.sy = state.misc2 << Fixed.FRACBITS;
            }

            // Call action routine.
            // Modified handling.
            if (state.actionPsp != null) {
                state.actionPsp.accept(player, psp);
                if (psp.state == null) {
                    break;
                }
            }

            stnum = psp.state.nextstate;

        } while (psp.tics == 0);
        // an initial state of 0 could cycle through
    }

    //
    // P_CalcSwing
    //
    // p_pspr.c lines 112–128.
    public static void pCalcSwing(Player player) {
        int swing;
        int angle;

        // OPTIMIZE: tablify this.
        // A LUT would allow for different modes,
        //  and add flexibility.

        swing = player.bob;

        angle = (Tables.FINEANGLES / 70 * PTick.leveltime) & Tables.FINEMASK;
        swingx = Fixed.mul(swing, Tables.finesine[angle]);

        angle = (Tables.FINEANGLES / 70 * PTick.leveltime
                + Tables.FINEANGLES / 2) & Tables.FINEMASK;
        swingy = -Fixed.mul(swingx, Tables.finesine[angle]);
    }

    //
    // P_BringUpWeapon
    // Starts bringing the pending weapon up from the bottom of the
    // screen.
    //
    // p_pspr.c lines 138–154.
    public static void pBringUpWeapon(Player player) {
        int newstate;

        if (player.pendingweapon == Doomdef.wp_nochange) {
            player.pendingweapon = player.readyweapon;
        }

        if (player.pendingweapon == Doomdef.wp_chainsaw) {
            SSound.sStartSound(player.mo, Sounds.sfx_sawup);
        }

        newstate = DItems.weaponinfo[player.pendingweapon].upstate;

        player.pendingweapon = Doomdef.wp_nochange;
        player.psprites[ps_weapon].sy = WEAPONBOTTOM;

        pSetPsprite(player, ps_weapon, newstate);
    }

    //
    // P_CheckAmmo
    // Returns true if there is enough ammo to shoot. If not, selects
    // the next weapon to use.
    //
    // p_pspr.c lines 161–240.
    public static boolean pCheckAmmo(Player player) {
        int ammo;
        int count;

        ammo = DItems.weaponinfo[player.readyweapon].ammo;

        // Minimal amount for one shot varies.
        if (player.readyweapon == Doomdef.wp_bfg) {
            count = BFGCELLS;
        } else if (player.readyweapon == Doomdef.wp_supershotgun) {
            count = 2; // Double barrel.
        } else {
            count = 1; // Regular.
        }

        // Some do not need ammunition anyway.
        // Return if current ammunition sufficient.
        if (ammo == Doomdef.am_noammo || player.ammo[ammo] >= count) {
            return true;
        }

        // Out of ammo, pick a weapon to change to.
        // Preferences are set here.
        do {
            if (player.weaponowned[Doomdef.wp_plasma]
                    && player.ammo[Doomdef.am_cell] != 0
                    && (Doomstat.gamemode != Doomstat.GameMode.shareware)) {
                player.pendingweapon = Doomdef.wp_plasma;
            } else if (player.weaponowned[Doomdef.wp_supershotgun]
                    && player.ammo[Doomdef.am_shell] > 2
                    && (Doomstat.gamemode == Doomstat.GameMode.commercial)) {
                player.pendingweapon = Doomdef.wp_supershotgun;
            } else if (player.weaponowned[Doomdef.wp_chaingun]
                    && player.ammo[Doomdef.am_clip] != 0) {
                player.pendingweapon = Doomdef.wp_chaingun;
            } else if (player.weaponowned[Doomdef.wp_shotgun]
                    && player.ammo[Doomdef.am_shell] != 0) {
                player.pendingweapon = Doomdef.wp_shotgun;
            } else if (player.ammo[Doomdef.am_clip] != 0) {
                player.pendingweapon = Doomdef.wp_pistol;
            } else if (player.weaponowned[Doomdef.wp_chainsaw]) {
                player.pendingweapon = Doomdef.wp_chainsaw;
            } else if (player.weaponowned[Doomdef.wp_missile]
                    && player.ammo[Doomdef.am_misl] != 0) {
                player.pendingweapon = Doomdef.wp_missile;
            } else if (player.weaponowned[Doomdef.wp_bfg]
                    && player.ammo[Doomdef.am_cell] > 40
                    && (Doomstat.gamemode != Doomstat.GameMode.shareware)) {
                player.pendingweapon = Doomdef.wp_bfg;
            } else {
                // If everything fails.
                player.pendingweapon = Doomdef.wp_fist;
            }

        } while (player.pendingweapon == Doomdef.wp_nochange);

        // Now set appropriate weapon overlay.
        pSetPsprite(player,
                ps_weapon,
                DItems.weaponinfo[player.readyweapon].downstate);

        return false;
    }

    //
    // P_FireWeapon.
    //
    // p_pspr.c lines 246–257.
    public static void pFireWeapon(Player player) {
        int newstate;

        if (!pCheckAmmo(player)) {
            return;
        }

        PMobj.pSetMobjState(player.mo, Info.S_PLAY_ATK1);
        newstate = DItems.weaponinfo[player.readyweapon].atkstate;
        pSetPsprite(player, ps_weapon, newstate);
        PEnemy.pNoiseAlert(player.mo, player.mo);
    }

    //
    // P_DropWeapon
    // Player died, so put the weapon away.
    //
    // p_pspr.c lines 265–270.
    public static void pDropWeapon(Player player) {
        pSetPsprite(player,
                ps_weapon,
                DItems.weaponinfo[player.readyweapon].downstate);
    }

    //
    // A_WeaponReady
    // The player can fire the weapon or change to another weapon at
    // this time.
    //
    // p_pspr.c lines 282–334.
    public static void aWeaponReady(Player player, Pspdef psp) {
        int newstate;
        int angle;

        // get out of attack state
        if (player.mo.state == Info.states[Info.S_PLAY_ATK1]
                || player.mo.state == Info.states[Info.S_PLAY_ATK2]) {
            PMobj.pSetMobjState(player.mo, Info.S_PLAY);
        }

        if (player.readyweapon == Doomdef.wp_chainsaw
                && psp.state == Info.states[Info.S_SAW]) {
            SSound.sStartSound(player.mo, Sounds.sfx_sawidl);
        }

        // check for change
        //  if player is dead, put the weapon away
        if (player.pendingweapon != Doomdef.wp_nochange || player.health == 0) {
            // change weapon
            //  (pending weapon should allready be validated)
            newstate = DItems.weaponinfo[player.readyweapon].downstate;
            pSetPsprite(player, ps_weapon, newstate);
            return;
        }

        // check for fire
        //  the missile launcher and bfg do not auto fire
        if ((player.cmd.buttons & Doomdef.BT_ATTACK) != 0) {
            if (player.attackdown == 0
                    || (player.readyweapon != Doomdef.wp_missile
                        && player.readyweapon != Doomdef.wp_bfg)) {
                player.attackdown = 1;
                pFireWeapon(player);
                return;
            }
        } else {
            player.attackdown = 0;
        }

        // bob the weapon based on movement speed
        angle = (128 * PTick.leveltime) & Tables.FINEMASK;
        psp.sx = Fixed.FRACUNIT + Fixed.mul(player.bob, Tables.finecosine(angle));
        angle &= Tables.FINEANGLES / 2 - 1;
        psp.sy = WEAPONTOP + Fixed.mul(player.bob, Tables.finesine[angle]);
    }

    //
    // A_ReFire
    // The player can re-fire the weapon without lowering it
    // entirely.
    //
    // p_pspr.c lines 343–362.
    public static void aReFire(Player player, Pspdef psp) {
        // check for fire
        //  (if a weaponchange is pending, let it go through instead)
        if ((player.cmd.buttons & Doomdef.BT_ATTACK) != 0
                && player.pendingweapon == Doomdef.wp_nochange
                && player.health != 0) {
            player.refire++;
            pFireWeapon(player);
        } else {
            player.refire = 0;
            pCheckAmmo(player);
        }
    }

    //
    // A_CheckReload
    //
    // p_pspr.c lines 365–375. The C `#if 0` block (super-shotgun
    // empty-snap state) is preserved as a comment.
    public static void aCheckReload(Player player, Pspdef psp) {
        pCheckAmmo(player);
        // #if 0 — disabled in the C:
        // if (player.ammo[am_shell] < 2)
        //     pSetPsprite(player, ps_weapon, S_DSNR1);
    }

    //
    // A_Lower
    // Lowers current weapon, and changes weapon at bottom.
    //
    // p_pspr.c lines 385–416.
    public static void aLower(Player player, Pspdef psp) {
        psp.sy += LOWERSPEED;

        // Is already down.
        if (psp.sy < WEAPONBOTTOM) {
            return;
        }

        // Player is dead.
        if (player.playerstate == Player.PST_DEAD) {
            psp.sy = WEAPONBOTTOM;

            // don't bring weapon back up
            return;
        }

        // The old weapon has been lowered off the screen,
        // so change the weapon and start raising it
        if (player.health == 0) {
            // Player is dead, so keep the weapon off screen.
            pSetPsprite(player, ps_weapon, Info.S_NULL);
            return;
        }

        player.readyweapon = player.pendingweapon;

        pBringUpWeapon(player);
    }

    //
    // A_Raise
    //
    // p_pspr.c lines 423–441.
    public static void aRaise(Player player, Pspdef psp) {
        int newstate;

        psp.sy -= RAISESPEED;

        if (psp.sy > WEAPONTOP) {
            return;
        }

        psp.sy = WEAPONTOP;

        // The weapon has been raised all the way,
        //  so change to the ready state.
        newstate = DItems.weaponinfo[player.readyweapon].readystate;

        pSetPsprite(player, ps_weapon, newstate);
    }

    //
    // A_GunFlash
    //
    // p_pspr.c lines 449–455.
    public static void aGunFlash(Player player, Pspdef psp) {
        PMobj.pSetMobjState(player.mo, Info.S_PLAY_ATK2);
        pSetPsprite(player, ps_flash,
                DItems.weaponinfo[player.readyweapon].flashstate);
    }


    //
    // WEAPON ATTACKS
    //

    //
    // A_Punch
    //
    // p_pspr.c lines 468–495.
    public static void aPunch(Player player, Pspdef psp) {
        int angle;
        int damage;
        int slope;

        damage = (MRandom.pRandom() % 10 + 1) << 1;

        if (player.powers[Doomdef.pw_strength] != 0) {
            damage *= 10;
        }

        angle = player.mo.angle;
        angle += (MRandom.pRandom() - MRandom.pRandom()) << 18;
        slope = PMap.pAimLineAttack(player.mo, angle, PMobj.MELEERANGE);
        PMap.pLineAttack(player.mo, angle, PMobj.MELEERANGE, slope, damage);

        // turn to face target
        if (PMap.linetarget != null) {
            SSound.sStartSound(player.mo, Sounds.sfx_punch);
            player.mo.angle = RMain.rPointToAngle2(player.mo.x,
                    player.mo.y,
                    PMap.linetarget.x,
                    PMap.linetarget.y);
        }
    }

    //
    // A_Saw
    //
    // p_pspr.c lines 502–543.
    public static void aSaw(Player player, Pspdef psp) {
        int angle;
        int damage;
        int slope;

        damage = 2 * (MRandom.pRandom() % 10 + 1);
        angle = player.mo.angle;
        angle += (MRandom.pRandom() - MRandom.pRandom()) << 18;

        // use meleerange + 1 se the puff doesn't skip the flash
        slope = PMap.pAimLineAttack(player.mo, angle, PMobj.MELEERANGE + 1);
        PMap.pLineAttack(player.mo, angle, PMobj.MELEERANGE + 1, slope, damage);

        if (PMap.linetarget == null) {
            SSound.sStartSound(player.mo, Sounds.sfx_sawful);
            return;
        }
        SSound.sStartSound(player.mo, Sounds.sfx_sawhit);

        // turn to face target
        angle = RMain.rPointToAngle2(player.mo.x, player.mo.y,
                PMap.linetarget.x, PMap.linetarget.y);
        // C: subtract via angle_t arithmetic, compare unsigned
        // against ANG180 / -ANG90/20 etc. — use Integer.compareUnsigned
        // for the same wraparound semantics PUser uses.
        int delta = angle - player.mo.angle;
        if (Integer.compareUnsigned(delta, Tables.ANG180) > 0) {
            if (Integer.compareUnsigned(delta, -Tables.ANG90 / 20) < 0) {
                player.mo.angle = angle + Tables.ANG90 / 21;
            } else {
                player.mo.angle -= Tables.ANG90 / 20;
            }
        } else {
            if (Integer.compareUnsigned(delta, Tables.ANG90 / 20) > 0) {
                player.mo.angle = angle - Tables.ANG90 / 21;
            } else {
                player.mo.angle += Tables.ANG90 / 20;
            }
        }
        player.mo.flags |= Mobj.MF_JUSTATTACKED;
    }

    //
    // A_FireMissile
    //
    // p_pspr.c lines 551–557.
    public static void aFireMissile(Player player, Pspdef psp) {
        player.ammo[DItems.weaponinfo[player.readyweapon].ammo]--;
        PMobj.pSpawnPlayerMissile(player.mo, Info.MT_ROCKET);
    }

    //
    // A_FireBFG
    //
    // p_pspr.c lines 564–570.
    public static void aFireBFG(Player player, Pspdef psp) {
        player.ammo[DItems.weaponinfo[player.readyweapon].ammo] -= BFGCELLS;
        PMobj.pSpawnPlayerMissile(player.mo, Info.MT_BFG);
    }

    //
    // A_FirePlasma
    //
    // p_pspr.c lines 578–589.
    public static void aFirePlasma(Player player, Pspdef psp) {
        player.ammo[DItems.weaponinfo[player.readyweapon].ammo]--;

        pSetPsprite(player, ps_flash,
                DItems.weaponinfo[player.readyweapon].flashstate
                        + (MRandom.pRandom() & 1));

        PMobj.pSpawnPlayerMissile(player.mo, Info.MT_PLASMA);
    }

    //
    // P_BulletSlope
    // Sets a slope so a near miss is at aproximately the height of
    // the intended target.
    //
    // p_pspr.c lines 601–619.
    public static void pBulletSlope(Mobj mo) {
        int an;

        // see which target is to be aimed at
        an = mo.angle;
        bulletslope = PMap.pAimLineAttack(mo, an, 16 * 64 * Fixed.FRACUNIT);

        if (PMap.linetarget == null) {
            an += 1 << 26;
            bulletslope = PMap.pAimLineAttack(mo, an, 16 * 64 * Fixed.FRACUNIT);
            if (PMap.linetarget == null) {
                an -= 2 << 26;
                bulletslope = PMap.pAimLineAttack(mo, an, 16 * 64 * Fixed.FRACUNIT);
            }
        }
    }

    //
    // P_GunShot
    //
    // p_pspr.c lines 626–640.
    public static void pGunShot(Mobj mo, boolean accurate) {
        int angle;
        int damage;

        damage = 5 * (MRandom.pRandom() % 3 + 1);
        angle = mo.angle;

        if (!accurate) {
            angle += (MRandom.pRandom() - MRandom.pRandom()) << 18;
        }

        PMap.pLineAttack(mo, angle, PMobj.MISSILERANGE, bulletslope, damage);
    }

    //
    // A_FirePistol
    //
    // p_pspr.c lines 647–662.
    public static void aFirePistol(Player player, Pspdef psp) {
        SSound.sStartSound(player.mo, Sounds.sfx_pistol);

        PMobj.pSetMobjState(player.mo, Info.S_PLAY_ATK2);
        player.ammo[DItems.weaponinfo[player.readyweapon].ammo]--;

        pSetPsprite(player, ps_flash,
                DItems.weaponinfo[player.readyweapon].flashstate);

        pBulletSlope(player.mo);
        pGunShot(player.mo, player.refire == 0);
    }

    //
    // A_FireShotgun
    //
    // p_pspr.c lines 669–688.
    public static void aFireShotgun(Player player, Pspdef psp) {
        int i;

        SSound.sStartSound(player.mo, Sounds.sfx_shotgn);
        PMobj.pSetMobjState(player.mo, Info.S_PLAY_ATK2);

        player.ammo[DItems.weaponinfo[player.readyweapon].ammo]--;

        pSetPsprite(player, ps_flash,
                DItems.weaponinfo[player.readyweapon].flashstate);

        pBulletSlope(player.mo);

        for (i = 0; i < 7; i++) {
            pGunShot(player.mo, false);
        }
    }

    //
    // A_FireShotgun2
    //
    // p_pspr.c lines 696–726.
    public static void aFireShotgun2(Player player, Pspdef psp) {
        int i;
        int angle;
        int damage;


        SSound.sStartSound(player.mo, Sounds.sfx_dshtgn);
        PMobj.pSetMobjState(player.mo, Info.S_PLAY_ATK2);

        player.ammo[DItems.weaponinfo[player.readyweapon].ammo] -= 2;

        pSetPsprite(player, ps_flash,
                DItems.weaponinfo[player.readyweapon].flashstate);

        pBulletSlope(player.mo);

        for (i = 0; i < 20; i++) {
            damage = 5 * (MRandom.pRandom() % 3 + 1);
            angle = player.mo.angle;
            angle += (MRandom.pRandom() - MRandom.pRandom()) << 19;
            PMap.pLineAttack(player.mo,
                    angle,
                    PMobj.MISSILERANGE,
                    bulletslope + ((MRandom.pRandom() - MRandom.pRandom()) << 5),
                    damage);
        }
    }

    //
    // A_FireCGun
    //
    // p_pspr.c lines 733–754. The C reads
    // `weaponinfo[].flashstate + psp->state - &states[S_CHAIN1]`
    // — pointer arithmetic that picks one of two muzzle-flash
    // sub-frames depending on which chaingun fire-frame the psp
    // is in. Java translates the pointer-diff to an index lookup.
    public static void aFireCGun(Player player, Pspdef psp) {
        SSound.sStartSound(player.mo, Sounds.sfx_pistol);

        if (player.ammo[DItems.weaponinfo[player.readyweapon].ammo] == 0) {
            return;
        }

        PMobj.pSetMobjState(player.mo, Info.S_PLAY_ATK2);
        player.ammo[DItems.weaponinfo[player.readyweapon].ammo]--;

        // C: flashstate + psp.state - &states[S_CHAIN1]
        int chainOffset = stateIndex(psp.state) - Info.S_CHAIN1;
        pSetPsprite(player, ps_flash,
                DItems.weaponinfo[player.readyweapon].flashstate
                        + chainOffset);

        pBulletSlope(player.mo);
        pGunShot(player.mo, player.refire == 0);
    }

    //
    // A_Light0 / A_Light1 / A_Light2
    //
    // p_pspr.c lines 761–774. Set the player's screen-flash
    // brightness for muzzle-flash overlays.
    public static void aLight0(Player player, Pspdef psp) {
        player.extralight = 0;
    }

    public static void aLight1(Player player, Pspdef psp) {
        player.extralight = 1;
    }

    public static void aLight2(Player player, Pspdef psp) {
        player.extralight = 2;
    }

    //
    // A_BFGSpray
    // Spawn a BFG explosion on every monster in view.
    //
    // p_pspr.c lines 781–811. The C signature is `(mobj_t* mo)` —
    // it's bound to the projectile's death-state .action.acp1, not
    // a weapon psprite. Lives in PPspr because of its semantic
    // grouping with the BFG fire path.
    public static void aBFGSpray(Mobj mo) {
        int i;
        int j;
        int damage;
        int an;

        // offset angles from its attack angle
        for (i = 0; i < 40; i++) {
            an = mo.angle - Tables.ANG90 / 2 + Tables.ANG90 / 40 * i;

            // mo->target is the originator (player)
            //  of the missile
            PMap.pAimLineAttack(mo.target, an, 16 * 64 * Fixed.FRACUNIT);

            if (PMap.linetarget == null) {
                continue;
            }

            PMobj.pSpawnMobj(PMap.linetarget.x,
                    PMap.linetarget.y,
                    PMap.linetarget.z + (PMap.linetarget.height >> 2),
                    Info.MT_EXTRABFG);

            damage = 0;
            for (j = 0; j < 15; j++) {
                damage += (MRandom.pRandom() & 7) + 1;
            }

            PInter.pDamageMobj(PMap.linetarget, mo.target, mo.target, damage);
        }
    }

    //
    // A_BFGsound
    //
    // p_pspr.c lines 818–823.
    public static void aBFGsound(Player player, Pspdef psp) {
        SSound.sStartSound(player.mo, Sounds.sfx_bfg);
    }

    //
    // P_SetupPsprites
    // Called at start of level for each player.
    //
    // p_pspr.c lines 831–842.
    public static void pSetupPsprites(Player player) {
        int i;

        // remove all psprites
        for (i = 0; i < NUMPSPRITES; i++) {
            player.psprites[i].state = null;
        }

        // spawn the gun
        player.pendingweapon = player.readyweapon;
        pBringUpWeapon(player);
    }

    //
    // P_MovePsprites
    // Called every tic by player thinking routine.
    //
    // p_pspr.c lines 851–877.
    public static void pMovePsprites(Player player) {
        int i;
        Pspdef psp;
        State state;

        for (i = 0; i < NUMPSPRITES; i++) {
            psp = player.psprites[i];
            // a null state means not active
            state = psp.state;
            if (state != null) {
                // drop tic count and possibly change state

                // a -1 tic count never changes
                if (psp.tics != -1) {
                    psp.tics--;
                    if (psp.tics == 0) {
                        pSetPsprite(player, i, psp.state.nextstate);
                    }
                }
            }
        }

        player.psprites[ps_flash].sx = player.psprites[ps_weapon].sx;
        player.psprites[ps_flash].sy = player.psprites[ps_weapon].sy;
    }

    // C uses pointer arithmetic `psp->state - &states[S_CHAIN1]`
    // to convert a state_t pointer back into an Info.S_* index.
    // Java does a linear scan over states[] — the array is 967
    // entries but A_FireCGun fires once per chaingun shot, which is
    // bounded by the chaingun's tic period.
    private static int stateIndex(State s) {
        for (int i = 0; i < Info.NUMSTATES; i++) {
            if (Info.states[i] == s) {
                return i;
            }
        }
        return -1;
    }
}
