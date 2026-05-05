// Port of linuxdoom-1.10/p_enemy.c — partial.
//
// Pass 1: AI navigation infrastructure + the four entry-point
// callbacks (A_Look / A_Chase / A_FaceTarget / A_KeenDie). Per-
// monster A_* attack callbacks (A_PosAttack, A_TroopAttack,
// A_HeadAttack, A_BruisAttack, A_FatAttack*, A_Tracer, A_VileChase,
// A_BrainSpit, A_SpawnFly, etc.) land in the second pass.
//
// Sound flooding: P_NoiseAlert seeds, P_RecursiveSound walks the
// sector graph (REJECT-aware via the Sector.validcount stamp +
// soundtraversed counter), writing soundtarget on every reachable
// sector. A_Look reads the sector's soundtarget to wake up.
//
// All deps are now ported: PMaputl (pAproxDistance, pLineOpening,
// openrange), PSight (pCheckSight), PMap (pTryMove, floatok,
// spechit, numspechit, tmfloorz, pUseSpecialLine), PMobj
// (pSetMobjState, MF_*, FLOATSPEED, MELEERANGE), Tables (ANG90 /
// ANG270), MRandom, GGame (playeringame, players), Doomstat
// (gameskill, fastparm).
package doom.play;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.RMain;
import doom.render.Sector;
import doom.render.Tables;
import doom.sound.SSound;
import doom.sound.Sounds;
import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.GGame;
import doom.system.ISystem;

public final class PEnemy {

    // p_enemy.c lines 51–64 — dirtype_t.
    public static final int DI_EAST      = 0;
    public static final int DI_NORTHEAST = 1;
    public static final int DI_NORTH     = 2;
    public static final int DI_NORTHWEST = 3;
    public static final int DI_WEST      = 4;
    public static final int DI_SOUTHWEST = 5;
    public static final int DI_SOUTH     = 6;
    public static final int DI_SOUTHEAST = 7;
    public static final int DI_NODIR     = 8;
    public static final int NUMDIRS      = 9;

    // p_enemy.c lines 70–74 — opposite-direction LUT.
    public static final int[] opposite = {
        DI_WEST, DI_SOUTHWEST, DI_SOUTH, DI_SOUTHEAST,
        DI_EAST, DI_NORTHEAST, DI_NORTH, DI_NORTHWEST, DI_NODIR
    };

    // p_enemy.c lines 76–79 — diagonal-direction LUT, indexed
    // [(deltay<0)<<1 | (deltax>0)].
    public static final int[] diags = {
        DI_NORTHWEST, DI_NORTHEAST, DI_SOUTHWEST, DI_SOUTHEAST
    };

    // p_enemy.c lines 264–265 — per-direction unit movement.
    public static final int[] xspeed = {
        Fixed.FRACUNIT, 47000, 0, -47000,
        -Fixed.FRACUNIT, -47000, 0, 47000
    };
    public static final int[] yspeed = {
        0, 47000, Fixed.FRACUNIT, 47000,
        0, -47000, -Fixed.FRACUNIT, -47000
    };

    // p_enemy.c line 103 — set by P_NoiseAlert before recursion;
    // read by P_RecursiveSound to write into each sector.
    public static Mobj soundtarget;

    private PEnemy() {
    }

    //
    // P_RecursiveSound
    // Recursively traverse adjacent sectors; sound-blocking lines
    // cut off traversal.
    //
    // p_enemy.c lines 105–149.
    public static void pRecursiveSound(Sector sec, int soundblocks) {
        int i;
        Line check;
        Sector other;

        // wake up all monsters in this sector
        if (sec.validcount == RMain.validcount
                && sec.soundtraversed <= soundblocks + 1) {
            return; // already flooded
        }

        sec.validcount = RMain.validcount;
        sec.soundtraversed = soundblocks + 1;
        sec.soundtarget = soundtarget;

        for (i = 0; i < sec.linecount; i++) {
            check = sec.lines[i];
            if ((check.flags & Line.ML_TWOSIDED) == 0) {
                continue;
            }

            PMaputl.pLineOpening(check);

            if (PMaputl.openrange <= 0) {
                continue;       // closed door
            }

            if (PSetup.sides[check.sidenum[0]].sector == sec) {
                other = PSetup.sides[check.sidenum[1]].sector;
            } else {
                other = PSetup.sides[check.sidenum[0]].sector;
            }

            if ((check.flags & Line.ML_SOUNDBLOCK) != 0) {
                if (soundblocks == 0) {
                    pRecursiveSound(other, 1);
                }
            } else {
                pRecursiveSound(other, soundblocks);
            }
        }
    }

    //
    // P_NoiseAlert
    // If a monster yells at a player, it will alert other monsters
    // to the player.
    //
    // p_enemy.c lines 158–166.
    public static void pNoiseAlert(Mobj target, Mobj emmiter) {
        soundtarget = target;
        RMain.validcount++;
        pRecursiveSound(emmiter.subsector.sector, 0);
    }

    //
    // P_CheckMeleeRange
    //
    // p_enemy.c lines 174–192.
    public static boolean pCheckMeleeRange(Mobj actor) {
        Mobj pl;
        int dist;

        if (actor.target == null) {
            return false;
        }

        pl = actor.target;
        dist = PMaputl.pAproxDistance(pl.x - actor.x, pl.y - actor.y);

        if (dist >= PMobj.MELEERANGE - 20 * Fixed.FRACUNIT + pl.info.radius) {
            return false;
        }

        if (!PSight.pCheckSight(actor, actor.target)) {
            return false;
        }

        return true;
    }

    //
    // P_CheckMissileRange
    //
    // p_enemy.c lines 197–256.
    public static boolean pCheckMissileRange(Mobj actor) {
        int dist;

        if (!PSight.pCheckSight(actor, actor.target)) {
            return false;
        }

        if ((actor.flags & Mobj.MF_JUSTHIT) != 0) {
            // the target just hit the enemy,
            // so fight back!
            actor.flags &= ~Mobj.MF_JUSTHIT;
            return true;
        }

        if (actor.reactiontime != 0) {
            return false;       // do not attack yet
        }

        // OPTIMIZE: get this from a global checksight
        dist = PMaputl.pAproxDistance(actor.x - actor.target.x,
                actor.y - actor.target.y) - 64 * Fixed.FRACUNIT;

        if (actor.info.meleestate == 0) {
            dist -= 128 * Fixed.FRACUNIT;   // no melee attack, so fire more
        }

        dist >>= 16;

        if (actor.type == Info.MT_VILE) {
            if (dist > 14 * 64) {
                return false;   // too far away
            }
        }


        if (actor.type == Info.MT_UNDEAD) {
            if (dist < 196) {
                return false;   // close for fist attack
            }
            dist >>= 1;
        }


        if (actor.type == Info.MT_CYBORG
                || actor.type == Info.MT_SPIDER
                || actor.type == Info.MT_SKULL) {
            dist >>= 1;
        }

        if (dist > 200) {
            dist = 200;
        }

        if (actor.type == Info.MT_CYBORG && dist > 160) {
            dist = 160;
        }

        if (MRandom.pRandom() < dist) {
            return false;
        }

        return true;
    }

    //
    // P_Move
    // Move in the current direction. Returns false if blocked.
    //
    // p_enemy.c lines 272–335.
    public static boolean pMove(Mobj actor) {
        int tryx;
        int tryy;

        Line ld;

        // warning: 'catch', 'throw', and 'try'
        // are all C++ reserved words
        boolean try_ok;
        boolean good;

        if (actor.movedir == DI_NODIR) {
            return false;
        }

        // C: (unsigned)actor->movedir >= 8
        if (actor.movedir < 0 || actor.movedir >= 8) {
            ISystem.iError("Weird actor->movedir!");
        }

        tryx = actor.x + actor.info.speed * xspeed[actor.movedir];
        tryy = actor.y + actor.info.speed * yspeed[actor.movedir];

        try_ok = PMap.pTryMove(actor, tryx, tryy);

        if (!try_ok) {
            // open any specials
            if ((actor.flags & Mobj.MF_FLOAT) != 0 && PMap.floatok) {
                // must adjust height
                if (actor.z < PMap.tmfloorz) {
                    actor.z += PMobj.FLOATSPEED;
                } else {
                    actor.z -= PMobj.FLOATSPEED;
                }

                actor.flags |= Mobj.MF_INFLOAT;
                return true;
            }

            if (PMap.numspechit == 0) {
                return false;
            }

            actor.movedir = DI_NODIR;
            good = false;
            while (PMap.numspechit-- != 0) {
                ld = PMap.spechit[PMap.numspechit];
                // if the special is not a door
                // that can be opened,
                // return false
                if (PSwitch.pUseSpecialLine(actor, ld, 0)) {
                    good = true;
                }
            }
            return good;
        } else {
            actor.flags &= ~Mobj.MF_INFLOAT;
        }


        if ((actor.flags & Mobj.MF_FLOAT) == 0) {
            actor.z = actor.floorz;
        }
        return true;
    }

    //
    // P_TryWalk
    // Attempts to move actor in its current direction.
    //
    // p_enemy.c lines 349–358.
    public static boolean pTryWalk(Mobj actor) {
        if (!pMove(actor)) {
            return false;
        }

        actor.movecount = MRandom.pRandom() & 15;
        return true;
    }

    //
    // P_NewChaseDir
    //
    // p_enemy.c lines 363–489. Picks the next movedir based on the
    // target's relative position. Falls through several attempts
    // (direct route, two-axis pick, prior direction, full sweep,
    // turnaround) until one isn't blocked.
    public static void pNewChaseDir(Mobj actor) {
        int deltax;
        int deltay;

        int[] d = new int[3];

        int tdir;
        int olddir;

        int turnaround;

        if (actor.target == null) {
            ISystem.iError("P_NewChaseDir: called with no target");
        }

        olddir = actor.movedir;
        turnaround = opposite[olddir];

        deltax = actor.target.x - actor.x;
        deltay = actor.target.y - actor.y;

        if (deltax > 10 * Fixed.FRACUNIT) {
            d[1] = DI_EAST;
        } else if (deltax < -10 * Fixed.FRACUNIT) {
            d[1] = DI_WEST;
        } else {
            d[1] = DI_NODIR;
        }

        if (deltay < -10 * Fixed.FRACUNIT) {
            d[2] = DI_SOUTH;
        } else if (deltay > 10 * Fixed.FRACUNIT) {
            d[2] = DI_NORTH;
        } else {
            d[2] = DI_NODIR;
        }

        // try direct route
        if (d[1] != DI_NODIR && d[2] != DI_NODIR) {
            // C: diags[((deltay<0)<<1)+(deltax>0)]
            int idx = ((deltay < 0 ? 1 : 0) << 1) | (deltax > 0 ? 1 : 0);
            actor.movedir = diags[idx];
            if (actor.movedir != turnaround && pTryWalk(actor)) {
                return;
            }
        }

        // try other directions
        if (MRandom.pRandom() > 200
                || Math.abs(deltay) > Math.abs(deltax)) {
            tdir = d[1];
            d[1] = d[2];
            d[2] = tdir;
        }

        if (d[1] == turnaround) {
            d[1] = DI_NODIR;
        }
        if (d[2] == turnaround) {
            d[2] = DI_NODIR;
        }

        if (d[1] != DI_NODIR) {
            actor.movedir = d[1];
            if (pTryWalk(actor)) {
                // either moved forward or attacked
                return;
            }
        }

        if (d[2] != DI_NODIR) {
            actor.movedir = d[2];

            if (pTryWalk(actor)) {
                return;
            }
        }

        // there is no direct path to the player,
        // so pick another direction.
        if (olddir != DI_NODIR) {
            actor.movedir = olddir;

            if (pTryWalk(actor)) {
                return;
            }
        }

        // randomly determine direction of search
        if ((MRandom.pRandom() & 1) != 0) {
            for (tdir = DI_EAST;
                    tdir <= DI_SOUTHEAST;
                    tdir++) {
                if (tdir != turnaround) {
                    actor.movedir = tdir;

                    if (pTryWalk(actor)) {
                        return;
                    }
                }
            }
        } else {
            for (tdir = DI_SOUTHEAST;
                    tdir != (DI_EAST - 1);
                    tdir--) {
                if (tdir != turnaround) {
                    actor.movedir = tdir;

                    if (pTryWalk(actor)) {
                        return;
                    }
                }
            }
        }

        if (turnaround != DI_NODIR) {
            actor.movedir = turnaround;
            if (pTryWalk(actor)) {
                return;
            }
        }

        actor.movedir = DI_NODIR;       // can not move
    }

    //
    // P_LookForPlayers
    // If allaround is false, only look 180 degrees in front.
    // Returns true if a player is targeted.
    //
    // p_enemy.c lines 498–558.
    //
    // INVARIANT: at least one seat in `playeringame[]` must be true
    // before calling. The termination condition (`c++ == 2 ||
    // lastlook == stop`) only fires inside the body, which runs only
    // for active seats. With every seat false the loop spins
    // forever. The C runtime always has playeringame[0]=true via
    // D_DoomMain; tests that exercise this function must seed the
    // same way.
    public static boolean pLookForPlayers(Mobj actor, boolean allaround) {
        int c;
        int stop;
        Player player;
        int an;
        int dist;

        c = 0;
        stop = (actor.lastlook - 1) & 3;

        for (; ; actor.lastlook = (actor.lastlook + 1) & 3) {
            if (!GGame.playeringame[actor.lastlook]) {
                continue;
            }

            if (c++ == 2 || actor.lastlook == stop) {
                // done looking
                return false;
            }

            player = GGame.players[actor.lastlook];

            if (player.health <= 0) {
                continue;       // dead
            }

            if (!PSight.pCheckSight(actor, player.mo)) {
                continue;       // out of sight
            }

            if (!allaround) {
                an = RMain.rPointToAngle2(actor.x,
                        actor.y,
                        player.mo.x,
                        player.mo.y)
                        - actor.angle;

                // C: an > ANG90 && an < ANG270 — angle_t comparisons
                // are unsigned 32-bit. ANG270 = 0xc0000000 sits high
                // in the unsigned range; use Integer.compareUnsigned.
                if (Integer.compareUnsigned(an, Tables.ANG90) > 0
                        && Integer.compareUnsigned(an, Tables.ANG270) < 0) {
                    dist = PMaputl.pAproxDistance(player.mo.x - actor.x,
                            player.mo.y - actor.y);
                    // if real close, react anyway
                    if (dist > PMobj.MELEERANGE) {
                        continue;       // behind back
                    }
                }
            }

            actor.target = player.mo;
            return true;
        }
    }

    //
    // A_KeenDie
    // DOOM II special, map 32. Uses special tag 666.
    //
    // p_enemy.c lines 566–593.
    public static void aKeenDie(Mobj mo) {
        Thinker th;
        Mobj mo2;
        Line junk;

        aFall(mo);

        // scan the remaining thinkers
        // to see if all Keens are dead
        for (th = PTick.thinkercap.next;
                th != PTick.thinkercap;
                th = th.next) {
            if (!(th instanceof Mobj)) {
                continue;
            }

            mo2 = (Mobj) th;
            if (mo2 != mo
                    && mo2.type == mo.type
                    && mo2.health > 0) {
                // other Keen not dead
                return;
            }
        }

        junk = new Line();
        junk.tag = 666;
        PDoors.evDoDoor(junk, PDoors.open);
    }


    //
    // ACTION ROUTINES
    //

    //
    // A_Fall
    //
    // p_enemy.c lines 1585–1596. Lifted up here as a forward
    // declaration in the C; lands as a tiny single-line callback in
    // the per-monster pass. Provided here as a tentative
    // implementation so A_KeenDie can call it.
    public static void aFall(Mobj actor) {
        // actor is on ground, it can be walked over
        actor.flags &= ~Mobj.MF_SOLID;

        // So change this if corpse objects
        // are meant to be obstacles.
    }

    //
    // A_Look
    // Stay in state until a player is sighted.
    //
    // p_enemy.c lines 604–664.
    public static void aLook(Mobj actor) {
        Mobj targ;
        boolean seeyou;

        actor.threshold = 0;        // any shot will wake up
        targ = actor.subsector.sector.soundtarget;

        seeyou = false;
        if (targ != null
                && (targ.flags & Mobj.MF_SHOOTABLE) != 0) {
            actor.target = targ;

            if ((actor.flags & Mobj.MF_AMBUSH) != 0) {
                if (PSight.pCheckSight(actor, actor.target)) {
                    seeyou = true;
                }
            } else {
                seeyou = true;
            }
        }


        if (!seeyou) {
            if (!pLookForPlayers(actor, false)) {
                return;
            }
        }

        // go into chase state
        if (actor.info.seesound != 0) {
            int sound;

            switch (actor.info.seesound) {
                case Sounds.sfx_posit1:
                case Sounds.sfx_posit2:
                case Sounds.sfx_posit3:
                    sound = Sounds.sfx_posit1 + MRandom.pRandom() % 3;
                    break;

                case Sounds.sfx_bgsit1:
                case Sounds.sfx_bgsit2:
                    sound = Sounds.sfx_bgsit1 + MRandom.pRandom() % 2;
                    break;

                default:
                    sound = actor.info.seesound;
                    break;
            }

            if (actor.type == Info.MT_SPIDER
                    || actor.type == Info.MT_CYBORG) {
                // full volume
                SSound.sStartSound(null, sound);
            } else {
                SSound.sStartSound(actor, sound);
            }
        }

        PMobj.pSetMobjState(actor, actor.info.seestate);
    }

    //
    // A_Chase
    // Actor has a melee attack, so it tries to close as fast as
    // possible.
    //
    // p_enemy.c lines 672–776. The C uses a `goto nomissile;` to
    // share the post-missile-attack-check tail with the no-missile-
    // state branch — a labeled break-out wraps the missile guard,
    // both paths fall through to the same continuation.
    public static void aChase(Mobj actor) {
        int delta;

        if (actor.reactiontime != 0) {
            actor.reactiontime--;
        }


        // modify target threshold
        if (actor.threshold != 0) {
            if (actor.target == null
                    || actor.target.health <= 0) {
                actor.threshold = 0;
            } else {
                actor.threshold--;
            }
        }

        // turn towards movement direction if not there yet
        if (actor.movedir < 8) {
            actor.angle &= (7 << 29);
            delta = actor.angle - (actor.movedir << 29);

            if (delta > 0) {
                actor.angle -= Tables.ANG90 / 2;
            } else if (delta < 0) {
                actor.angle += Tables.ANG90 / 2;
            }
        }

        if (actor.target == null
                || (actor.target.flags & Mobj.MF_SHOOTABLE) == 0) {
            // look for a new target
            if (pLookForPlayers(actor, true)) {
                return; // got a new target
            }

            PMobj.pSetMobjState(actor, actor.info.spawnstate);
            return;
        }

        // do not attack twice in a row
        if ((actor.flags & Mobj.MF_JUSTATTACKED) != 0) {
            actor.flags &= ~Mobj.MF_JUSTATTACKED;
            if (Doomstat.gameskill != Doomstat.sk_nightmare && !Doomstat.fastparm) {
                pNewChaseDir(actor);
            }
            return;
        }

        // check for melee attack
        if (actor.info.meleestate != 0
                && pCheckMeleeRange(actor)) {
            if (actor.info.attacksound != 0) {
                SSound.sStartSound(actor, actor.info.attacksound);
            }

            PMobj.pSetMobjState(actor, actor.info.meleestate);
            return;
        }

        // check for missile attack — wrapped in a flag so the C's
        // `goto nomissile;` can still fall through to the post-
        // attack tail.
        boolean nomissile = false;
        if (actor.info.missilestate != 0) {
            if (Doomstat.gameskill < Doomstat.sk_nightmare
                    && !Doomstat.fastparm && actor.movecount != 0) {
                nomissile = true;
            }

            if (!nomissile && !pCheckMissileRange(actor)) {
                nomissile = true;
            }

            if (!nomissile) {
                PMobj.pSetMobjState(actor, actor.info.missilestate);
                actor.flags |= Mobj.MF_JUSTATTACKED;
                return;
            }
        }

        // possibly choose another target
        if (Doomstat.netgame
                && actor.threshold == 0
                && !PSight.pCheckSight(actor, actor.target)) {
            if (pLookForPlayers(actor, true)) {
                return; // got a new target
            }
        }

        // chase towards player
        if (--actor.movecount < 0
                || !pMove(actor)) {
            pNewChaseDir(actor);
        }

        // make active sound
        if (actor.info.activesound != 0
                && MRandom.pRandom() < 3) {
            SSound.sStartSound(actor, actor.info.activesound);
        }
    }

    //
    // A_FaceTarget
    //
    // p_enemy.c lines 782–796.
    public static void aFaceTarget(Mobj actor) {
        if (actor.target == null) {
            return;
        }

        actor.flags &= ~Mobj.MF_AMBUSH;

        actor.angle = RMain.rPointToAngle2(actor.x,
                actor.y,
                actor.target.x,
                actor.target.y);

        if ((actor.target.flags & Mobj.MF_SHADOW) != 0) {
            actor.angle += (MRandom.pRandom() - MRandom.pRandom()) << 21;
        }
    }

    // Suppress: Doomdef import will be live once the per-monster A_*
    // pass adds card / weapon checks.
    @SuppressWarnings("unused")
    private static final int _keepDoomdef = Doomdef.MAXPLAYERS;
}
