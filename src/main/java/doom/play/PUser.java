// Port of linuxdoom-1.10/p_user.c — per-tic player update.
// Applies commands to thrust/view bobbing, weapon changes, use-lines and powers.
// PTick calls pPlayerThink for active players; pDeathThink handles dead players.
// Weapon, collision and sector-effect call targets are implemented modules.
package doom.play;

import doom.fixed.Fixed;
import doom.render.RMain;
import doom.render.Tables;
import doom.system.Doomdef;
import doom.system.Doomstat;

public final class PUser {

    // p_user.c line 41 — INVERSECOLORMAP — index of the special-effect
    // (invulnerability inverse) colormap. The renderer reads
    // Player.fixedcolormap to select; PUser writes it.
    public static final int INVERSECOLORMAP = 32;

    // p_user.c line 49 — 16 pixels of bob.
    public static final int MAXBOB = 0x100000; // fixed_t

    // p_user.c line 51 — onground flag scoped to this module.
    // Updated by P_MovePlayer / P_DeathThink at the top of each
    // per-tic dispatch and read by P_CalcHeight to gate the bob
    // computation.
    public static boolean onground;

    // p_user.c line 180 — `ANG5` (defined inside P_DeathThink).
    // Lifted to a class constant — the C macro is local-scope but
    // the value is module-stable.
    public static final int ANG5 = Tables.ANG90 / 18;

    private PUser() {
    }

    //
    // P_Thrust
    // Moves the given origin along a given angle.
    //
    // p_user.c lines 58–68.
    public static void pThrust(Player player, int angle, int move) {
        // C: angle >>= ANGLETOFINESHIFT;
        int a = (angle >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;

        player.mo.momx += Fixed.mul(move, Tables.finecosine(a));
        player.mo.momy += Fixed.mul(move, Tables.finesine[a]);
    }

    //
    // P_CalcHeight
    // Calculate the walking / running height adjustment.
    //
    // p_user.c lines 77–141.
    public static void pCalcHeight(Player player) {
        int angle;
        int bob;

        // Regular movement bobbing
        // (needs to be calculated for gun swing
        // even if not on ground)
        // OPTIMIZE: tablify angle
        // Note: a LUT allows for effects
        //  like a ramp with low health.
        player.bob =
                Fixed.mul(player.mo.momx, player.mo.momx)
                + Fixed.mul(player.mo.momy, player.mo.momy);

        player.bob >>= 2;

        if (player.bob > MAXBOB) {
            player.bob = MAXBOB;
        }

        if ((player.cheats & Player.CF_NOMOMENTUM) != 0 || !onground) {
            player.viewz = player.mo.z + PMobj.VIEWHEIGHT;

            if (player.viewz > player.mo.ceilingz - 4 * Fixed.FRACUNIT) {
                player.viewz = player.mo.ceilingz - 4 * Fixed.FRACUNIT;
            }

            // C: redundant assignment after the clip — preserved.
            player.viewz = player.mo.z + player.viewheight;
            return;
        }

        // C: angle = (FINEANGLES/20*leveltime) & FINEMASK;
        angle = (Tables.FINEANGLES / 20 * PTick.leveltime) & Tables.FINEMASK;
        bob = Fixed.mul(player.bob / 2, Tables.finesine[angle]);


        // move viewheight
        if (player.playerstate == Player.PST_LIVE) {
            player.viewheight += player.deltaviewheight;

            if (player.viewheight > PMobj.VIEWHEIGHT) {
                player.viewheight = PMobj.VIEWHEIGHT;
                player.deltaviewheight = 0;
            }

            if (player.viewheight < PMobj.VIEWHEIGHT / 2) {
                player.viewheight = PMobj.VIEWHEIGHT / 2;
                if (player.deltaviewheight <= 0) {
                    player.deltaviewheight = 1;
                }
            }

            if (player.deltaviewheight != 0) {
                player.deltaviewheight += Fixed.FRACUNIT / 4;
                if (player.deltaviewheight == 0) {
                    player.deltaviewheight = 1;
                }
            }
        }
        player.viewz = player.mo.z + player.viewheight + bob;

        if (player.viewz > player.mo.ceilingz - 4 * Fixed.FRACUNIT) {
            player.viewz = player.mo.ceilingz - 4 * Fixed.FRACUNIT;
        }
    }

    //
    // P_MovePlayer
    //
    // p_user.c lines 148–171.
    public static void pMovePlayer(Player player) {
        Ticcmd cmd;

        cmd = player.cmd;

        player.mo.angle += (cmd.angleturn << 16);

        // Do not let the player control movement
        //  if not onground.
        onground = (player.mo.z <= player.mo.floorz);

        if (cmd.forwardmove != 0 && onground) {
            pThrust(player, player.mo.angle, cmd.forwardmove * 2048);
        }

        if (cmd.sidemove != 0 && onground) {
            pThrust(player, player.mo.angle - Tables.ANG90, cmd.sidemove * 2048);
        }

        if ((cmd.forwardmove != 0 || cmd.sidemove != 0)
                && player.mo.state == Info.states[Info.S_PLAY]) {
            PMobj.pSetMobjState(player.mo, Info.S_PLAY_RUN1);
        }
    }

    //
    // P_DeathThink
    // Fall on your face when dying.
    // Decrease POV height to floor height.
    //
    // p_user.c lines 182–229.
    public static void pDeathThink(Player player) {
        int angle;
        int delta;

        PPspr.pMovePsprites(player);

        // fall to the ground
        if (player.viewheight > 6 * Fixed.FRACUNIT) {
            player.viewheight -= Fixed.FRACUNIT;
        }

        if (player.viewheight < 6 * Fixed.FRACUNIT) {
            player.viewheight = 6 * Fixed.FRACUNIT;
        }

        player.deltaviewheight = 0;
        onground = (player.mo.z <= player.mo.floorz);
        pCalcHeight(player);

        if (player.attacker != null && player.attacker != player.mo) {
            angle = RMain.rPointToAngle2(player.mo.x,
                    player.mo.y,
                    player.attacker.x,
                    player.attacker.y);

            delta = angle - player.mo.angle;

            // C: `delta < ANG5 || delta > (unsigned)-ANG5`. The
            // delta is angle_t (unsigned 32-bit), so `(unsigned)-ANG5`
            // is `0 - ANG5` modulo 2^32 — in Java that's just `-ANG5`
            // compared as unsigned. Use Integer.compareUnsigned.
            if (Integer.compareUnsigned(delta, ANG5) < 0
                    || Integer.compareUnsigned(delta, -ANG5) > 0) {
                // Looking at killer,
                //  so fade damage flash down.
                player.mo.angle = angle;

                if (player.damagecount != 0) {
                    player.damagecount--;
                }
            } else if (Integer.compareUnsigned(delta, Tables.ANG180) < 0) {
                player.mo.angle += ANG5;
            } else {
                player.mo.angle -= ANG5;
            }
        } else if (player.damagecount != 0) {
            player.damagecount--;
        }


        if ((player.cmd.buttons & Doomdef.BT_USE) != 0) {
            player.playerstate = Player.PST_REBORN;
        }
    }

    //
    // P_PlayerThink
    //
    // p_user.c lines 236–384.
    public static void pPlayerThink(Player player) {
        Ticcmd cmd;
        int newweapon;

        // fixme: do this in the cheat code
        if ((player.cheats & Player.CF_NOCLIP) != 0) {
            player.mo.flags |= Mobj.MF_NOCLIP;
        } else {
            player.mo.flags &= ~Mobj.MF_NOCLIP;
        }

        // chain saw run forward
        cmd = player.cmd;
        if ((player.mo.flags & Mobj.MF_JUSTATTACKED) != 0) {
            cmd.angleturn = 0;
            cmd.forwardmove = 0xc800 / 512;
            cmd.sidemove = 0;
            player.mo.flags &= ~Mobj.MF_JUSTATTACKED;
        }


        if (player.playerstate == Player.PST_DEAD) {
            pDeathThink(player);
            return;
        }

        // Move around.
        // Reactiontime is used to prevent movement
        //  for a bit after a teleport.
        if (player.mo.reactiontime != 0) {
            player.mo.reactiontime--;
        } else {
            pMovePlayer(player);
        }

        pCalcHeight(player);

        if (player.mo.subsector.sector.special != 0) {
            PSpec.pPlayerInSpecialSector(player);
        }

        // Check for weapon change.

        // A special event has no other buttons.
        if ((cmd.buttons & Doomdef.BT_SPECIAL) != 0) {
            cmd.buttons = 0;
        }

        if ((cmd.buttons & Doomdef.BT_CHANGE) != 0) {
            // The actual changing of the weapon is done
            //  when the weapon psprite can do it
            //  (read: not in the middle of an attack).
            newweapon = (cmd.buttons & Doomdef.BT_WEAPONMASK) >> Doomdef.BT_WEAPONSHIFT;

            if (newweapon == Doomdef.wp_fist
                    && player.weaponowned[Doomdef.wp_chainsaw]
                    && !(player.readyweapon == Doomdef.wp_chainsaw
                         && player.powers[Doomdef.pw_strength] != 0)) {
                newweapon = Doomdef.wp_chainsaw;
            }

            if ((Doomstat.gamemode == Doomstat.GameMode.commercial)
                    && newweapon == Doomdef.wp_shotgun
                    && player.weaponowned[Doomdef.wp_supershotgun]
                    && player.readyweapon != Doomdef.wp_supershotgun) {
                newweapon = Doomdef.wp_supershotgun;
            }


            if (player.weaponowned[newweapon]
                    && newweapon != player.readyweapon) {
                // Do not go to plasma or BFG in shareware,
                //  even if cheated.
                if ((newweapon != Doomdef.wp_plasma
                     && newweapon != Doomdef.wp_bfg)
                        || (Doomstat.gamemode != Doomstat.GameMode.shareware)) {
                    player.pendingweapon = newweapon;
                }
            }
        }

        // check for use
        if ((cmd.buttons & Doomdef.BT_USE) != 0) {
            if (player.usedown == 0) {
                PMap.pUseLines(player);
                player.usedown = 1;
            }
        } else {
            player.usedown = 0;
        }

        // cycle psprites
        PPspr.pMovePsprites(player);

        // Counters, time dependend power ups.

        // Strength counts up to diminish fade.
        if (player.powers[Doomdef.pw_strength] != 0) {
            player.powers[Doomdef.pw_strength]++;
        }

        if (player.powers[Doomdef.pw_invulnerability] != 0) {
            player.powers[Doomdef.pw_invulnerability]--;
        }

        if (player.powers[Doomdef.pw_invisibility] != 0) {
            if (--player.powers[Doomdef.pw_invisibility] == 0) {
                player.mo.flags &= ~Mobj.MF_SHADOW;
            }
        }

        if (player.powers[Doomdef.pw_infrared] != 0) {
            player.powers[Doomdef.pw_infrared]--;
        }

        if (player.powers[Doomdef.pw_ironfeet] != 0) {
            player.powers[Doomdef.pw_ironfeet]--;
        }

        if (player.damagecount != 0) {
            player.damagecount--;
        }

        if (player.bonuscount != 0) {
            player.bonuscount--;
        }


        // Handling colormaps.
        if (player.powers[Doomdef.pw_invulnerability] != 0) {
            if (player.powers[Doomdef.pw_invulnerability] > 4 * 32
                    || (player.powers[Doomdef.pw_invulnerability] & 8) != 0) {
                player.fixedcolormap = INVERSECOLORMAP;
            } else {
                player.fixedcolormap = 0;
            }
        } else if (player.powers[Doomdef.pw_infrared] != 0) {
            if (player.powers[Doomdef.pw_infrared] > 4 * 32
                    || (player.powers[Doomdef.pw_infrared] & 8) != 0) {
                // almost full bright
                player.fixedcolormap = 1;
            } else {
                player.fixedcolormap = 0;
            }
        } else {
            player.fixedcolormap = 0;
        }
    }
}
