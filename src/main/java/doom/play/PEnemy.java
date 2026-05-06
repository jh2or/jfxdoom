// Port of linuxdoom-1.10/p_enemy.c — full.
//
// AI navigation infrastructure + the entry-point callbacks (A_Look,
// A_Chase, A_FaceTarget, A_KeenDie) and every per-monster attack
// callback bound by the info.c states[] table.
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

    //
    // A_PosAttack — Zombieman pistol.
    // p_enemy.c lines 802–819.
    public static void aPosAttack(Mobj actor) {
        int angle;
        int damage;
        int slope;

        if (actor.target == null) {
            return;
        }

        aFaceTarget(actor);
        angle = actor.angle;
        slope = PMap.pAimLineAttack(actor, angle, PMobj.MISSILERANGE);

        SSound.sStartSound(actor, Sounds.sfx_pistol);
        angle += (MRandom.pRandom() - MRandom.pRandom()) << 20;
        damage = ((MRandom.pRandom() % 5) + 1) * 3;
        PMap.pLineAttack(actor, angle, PMobj.MISSILERANGE, slope, damage);
    }

    //
    // A_SPosAttack — Shotgun guy (3 pellets).
    // p_enemy.c lines 821–843.
    public static void aSPosAttack(Mobj actor) {
        int i;
        int angle;
        int bangle;
        int damage;
        int slope;

        if (actor.target == null) {
            return;
        }

        SSound.sStartSound(actor, Sounds.sfx_shotgn);
        aFaceTarget(actor);
        bangle = actor.angle;
        slope = PMap.pAimLineAttack(actor, bangle, PMobj.MISSILERANGE);

        for (i = 0; i < 3; i++) {
            angle = bangle + ((MRandom.pRandom() - MRandom.pRandom()) << 20);
            damage = ((MRandom.pRandom() % 5) + 1) * 3;
            PMap.pLineAttack(actor, angle, PMobj.MISSILERANGE, slope, damage);
        }
    }

    //
    // A_CPosAttack — Chaingunner.
    // p_enemy.c lines 845–863.
    public static void aCPosAttack(Mobj actor) {
        int angle;
        int bangle;
        int damage;
        int slope;

        if (actor.target == null) {
            return;
        }

        SSound.sStartSound(actor, Sounds.sfx_shotgn);
        aFaceTarget(actor);
        bangle = actor.angle;
        slope = PMap.pAimLineAttack(actor, bangle, PMobj.MISSILERANGE);

        angle = bangle + ((MRandom.pRandom() - MRandom.pRandom()) << 20);
        damage = ((MRandom.pRandom() % 5) + 1) * 3;
        PMap.pLineAttack(actor, angle, PMobj.MISSILERANGE, slope, damage);
    }

    //
    // A_CPosRefire — Chaingunner re-fire decision.
    // p_enemy.c lines 865–879.
    public static void aCPosRefire(Mobj actor) {
        // keep firing unless target got out of sight
        aFaceTarget(actor);

        if (MRandom.pRandom() < 40) {
            return;
        }

        if (actor.target == null
                || actor.target.health <= 0
                || !PSight.pCheckSight(actor, actor.target)) {
            PMobj.pSetMobjState(actor, actor.info.seestate);
        }
    }

    //
    // A_SpidRefire — Spider mastermind re-fire decision.
    // p_enemy.c lines 882–896.
    public static void aSpidRefire(Mobj actor) {
        // keep firing unless target got out of sight
        aFaceTarget(actor);

        if (MRandom.pRandom() < 10) {
            return;
        }

        if (actor.target == null
                || actor.target.health <= 0
                || !PSight.pCheckSight(actor, actor.target)) {
            PMobj.pSetMobjState(actor, actor.info.seestate);
        }
    }

    //
    // A_BspiAttack — Arachnotron plasma.
    // p_enemy.c lines 898–907.
    public static void aBspiAttack(Mobj actor) {
        if (actor.target == null) {
            return;
        }

        aFaceTarget(actor);

        // launch a missile
        PMobj.pSpawnMissile(actor, actor.target, Info.MT_ARACHPLAZ);
    }

    //
    // A_TroopAttack — Imp scratch + fireball.
    // p_enemy.c lines 913–932.
    public static void aTroopAttack(Mobj actor) {
        int damage;

        if (actor.target == null) {
            return;
        }

        aFaceTarget(actor);
        if (pCheckMeleeRange(actor)) {
            SSound.sStartSound(actor, Sounds.sfx_claw);
            damage = (MRandom.pRandom() % 8 + 1) * 3;
            PInter.pDamageMobj(actor.target, actor, actor, damage);
            return;
        }


        // launch a missile
        PMobj.pSpawnMissile(actor, actor.target, Info.MT_TROOPSHOT);
    }

    //
    // A_SargAttack — Demon / Spectre bite.
    // p_enemy.c lines 935–948.
    public static void aSargAttack(Mobj actor) {
        int damage;

        if (actor.target == null) {
            return;
        }

        aFaceTarget(actor);
        if (pCheckMeleeRange(actor)) {
            damage = ((MRandom.pRandom() % 10) + 1) * 4;
            PInter.pDamageMobj(actor.target, actor, actor, damage);
        }
    }

    //
    // A_HeadAttack — Cacodemon bite + ball.
    // p_enemy.c lines 950–967.
    public static void aHeadAttack(Mobj actor) {
        int damage;

        if (actor.target == null) {
            return;
        }

        aFaceTarget(actor);
        if (pCheckMeleeRange(actor)) {
            damage = (MRandom.pRandom() % 6 + 1) * 10;
            PInter.pDamageMobj(actor.target, actor, actor, damage);
            return;
        }

        // launch a missile
        PMobj.pSpawnMissile(actor, actor.target, Info.MT_HEADSHOT);
    }

    //
    // A_CyberAttack — Cyberdemon rocket.
    // p_enemy.c lines 969–976.
    public static void aCyberAttack(Mobj actor) {
        if (actor.target == null) {
            return;
        }

        aFaceTarget(actor);
        PMobj.pSpawnMissile(actor, actor.target, Info.MT_ROCKET);
    }

    //
    // A_BruisAttack — Baron / Hell Knight claw + plasma ball.
    // p_enemy.c lines 979–996.
    public static void aBruisAttack(Mobj actor) {
        int damage;

        if (actor.target == null) {
            return;
        }

        if (pCheckMeleeRange(actor)) {
            SSound.sStartSound(actor, Sounds.sfx_claw);
            damage = (MRandom.pRandom() % 8 + 1) * 10;
            PInter.pDamageMobj(actor.target, actor, actor, damage);
            return;
        }

        // launch a missile
        PMobj.pSpawnMissile(actor, actor.target, Info.MT_BRUISERSHOT);
    }

    //
    // A_SkelMissile — Revenant tracer.
    // p_enemy.c lines 1002–1017. The C raises the actor by 16 fixed
    // before spawning the missile (so the tracer originates above
    // the skeleton's eyes), then drops it back; preserve the round-
    // trip exactly.
    public static void aSkelMissile(Mobj actor) {
        Mobj mo;

        if (actor.target == null) {
            return;
        }

        aFaceTarget(actor);
        actor.z += 16 * Fixed.FRACUNIT;     // so missile spawns higher
        mo = PMobj.pSpawnMissile(actor, actor.target, Info.MT_TRACER);
        actor.z -= 16 * Fixed.FRACUNIT;     // back to normal

        mo.x += mo.momx;
        mo.y += mo.momy;
        mo.tracer = actor.target;
    }

    // p_enemy.c line 1019 — homing-tracer turn rate (~5.6° per tic).
    public static final int TRACEANGLE = 0xc000000;

    //
    // A_Tracer — homing missile heart-beat.
    // p_enemy.c lines 1021–1090. Fires only on every 4th gametic
    // (gametic & 3). Spawns a smoke trail behind the rocket and
    // adjusts angle/momentum toward the tracer target.
    public static void aTracer(Mobj actor) {
        int exact;
        int dist;
        int slope;
        Mobj dest;
        Mobj th;

        if ((PTick.gametic & 3) != 0) {
            return;
        }

        // spawn a puff of smoke behind the rocket
        PMobj.pSpawnPuff(actor.x, actor.y, actor.z);

        th = PMobj.pSpawnMobj(actor.x - actor.momx,
                actor.y - actor.momy,
                actor.z, Info.MT_SMOKE);

        th.momz = Fixed.FRACUNIT;
        th.tics -= MRandom.pRandom() & 3;
        if (th.tics < 1) {
            th.tics = 1;
        }

        // adjust direction
        dest = actor.tracer;

        if (dest == null || dest.health <= 0) {
            return;
        }

        // change angle
        exact = RMain.rPointToAngle2(actor.x,
                actor.y,
                dest.x,
                dest.y);

        if (exact != actor.angle) {
            // C: `exact - actor->angle > 0x80000000` is unsigned 32
            // overflow — Integer.compareUnsigned for the same
            // semantics (the wrap point is exactly 0x80000000 → ANG180).
            if (Integer.compareUnsigned(exact - actor.angle, 0x80000000) > 0) {
                actor.angle -= TRACEANGLE;
                if (Integer.compareUnsigned(exact - actor.angle, 0x80000000) < 0) {
                    actor.angle = exact;
                }
            } else {
                actor.angle += TRACEANGLE;
                if (Integer.compareUnsigned(exact - actor.angle, 0x80000000) > 0) {
                    actor.angle = exact;
                }
            }
        }

        exact = (actor.angle >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;
        actor.momx = Fixed.mul(actor.info.speed, Tables.finecosine(exact));
        actor.momy = Fixed.mul(actor.info.speed, Tables.finesine[exact]);

        // change slope
        dist = PMaputl.pAproxDistance(dest.x - actor.x,
                dest.y - actor.y);

        dist = dist / actor.info.speed;

        if (dist < 1) {
            dist = 1;
        }
        slope = (dest.z + 40 * Fixed.FRACUNIT - actor.z) / dist;

        if (slope < actor.momz) {
            actor.momz -= Fixed.FRACUNIT / 8;
        } else {
            actor.momz += Fixed.FRACUNIT / 8;
        }
    }

    //
    // A_SkelWhoosh — Revenant fist windup.
    // p_enemy.c lines 1093–1099.
    public static void aSkelWhoosh(Mobj actor) {
        if (actor.target == null) {
            return;
        }
        aFaceTarget(actor);
        SSound.sStartSound(actor, Sounds.sfx_skeswg);
    }

    //
    // A_SkelFist — Revenant punch.
    // p_enemy.c lines 1101–1116.
    public static void aSkelFist(Mobj actor) {
        int damage;

        if (actor.target == null) {
            return;
        }

        aFaceTarget(actor);

        if (pCheckMeleeRange(actor)) {
            damage = ((MRandom.pRandom() % 10) + 1) * 6;
            SSound.sStartSound(actor, Sounds.sfx_skepch);
            PInter.pDamageMobj(actor.target, actor, actor, damage);
        }
    }

    //
    // PIT_VileCheck — Detect a corpse that could be raised.
    // p_enemy.c lines 1124–1159.
    public static Mobj corpsehit;
    public static Mobj vileobj;
    public static int viletryx; // fixed_t
    public static int viletryy; // fixed_t

    public static boolean pitVileCheck(Mobj thing) {
        int maxdist;
        boolean check;

        if ((thing.flags & Mobj.MF_CORPSE) == 0) {
            return true;        // not a monster
        }

        if (thing.tics != -1) {
            return true;        // not lying still yet
        }

        if (thing.info.raisestate == Info.S_NULL) {
            return true;        // monster doesn't have a raise state
        }

        maxdist = thing.info.radius + Info.mobjinfo[Info.MT_VILE].radius;

        if (Math.abs(thing.x - viletryx) > maxdist
                || Math.abs(thing.y - viletryy) > maxdist) {
            return true;        // not actually touching
        }

        corpsehit = thing;
        corpsehit.momx = corpsehit.momy = 0;
        corpsehit.height <<= 2;
        check = PMap.pCheckPosition(corpsehit, corpsehit.x, corpsehit.y);
        corpsehit.height >>= 2;

        if (!check) {
            return true;        // doesn't fit here
        }

        return false;           // got one, so stop checking
    }

    //
    // A_VileChase — Archvile chase / resurrect-corpse hunt.
    // p_enemy.c lines 1167–1227.
    public static void aVileChase(Mobj actor) {
        int xl;
        int xh;
        int yl;
        int yh;

        int bx;
        int by;

        MobjInfo info;
        Mobj temp;

        // p_local.h: MAXRADIUS = 32*FRACUNIT, MAPBLOCKSHIFT = FRACBITS+7.
        final int MAXRADIUS = 32 * Fixed.FRACUNIT;
        final int MAPBLOCKSHIFT = Fixed.FRACBITS + 7;

        if (actor.movedir != DI_NODIR) {
            // check for corpses to raise
            viletryx = actor.x + actor.info.speed * xspeed[actor.movedir];
            viletryy = actor.y + actor.info.speed * yspeed[actor.movedir];

            xl = (viletryx - PSetup.bmaporgx - MAXRADIUS * 2) >> MAPBLOCKSHIFT;
            xh = (viletryx - PSetup.bmaporgx + MAXRADIUS * 2) >> MAPBLOCKSHIFT;
            yl = (viletryy - PSetup.bmaporgy - MAXRADIUS * 2) >> MAPBLOCKSHIFT;
            yh = (viletryy - PSetup.bmaporgy + MAXRADIUS * 2) >> MAPBLOCKSHIFT;

            vileobj = actor;
            for (bx = xl; bx <= xh; bx++) {
                for (by = yl; by <= yh; by++) {
                    // Call PIT_VileCheck to check whether object is
                    // a corpse that can be raised.
                    if (!PMaputl.pBlockThingsIterator(bx, by, PEnemy::pitVileCheck)) {
                        // got one!
                        temp = actor.target;
                        actor.target = corpsehit;
                        aFaceTarget(actor);
                        actor.target = temp;

                        PMobj.pSetMobjState(actor, Info.S_VILE_HEAL1);
                        SSound.sStartSound(corpsehit, Sounds.sfx_slop);
                        info = corpsehit.info;

                        PMobj.pSetMobjState(corpsehit, info.raisestate);
                        corpsehit.height <<= 2;
                        corpsehit.flags = info.flags;
                        corpsehit.health = info.spawnhealth;
                        corpsehit.target = null;

                        return;
                    }
                }
            }
        }

        // Return to normal attack.
        aChase(actor);
    }

    //
    // A_VileStart
    // p_enemy.c lines 1233–1236.
    public static void aVileStart(Mobj actor) {
        SSound.sStartSound(actor, Sounds.sfx_vilatk);
    }

    //
    // A_StartFire — initial vile-fire crackle + place.
    // p_enemy.c lines 1245–1249.
    public static void aStartFire(Mobj actor) {
        SSound.sStartSound(actor, Sounds.sfx_flamst);
        aFire(actor);
    }

    //
    // A_FireCrackle — periodic crackle while burning.
    // p_enemy.c lines 1251–1255.
    public static void aFireCrackle(Mobj actor) {
        SSound.sStartSound(actor, Sounds.sfx_flame);
        aFire(actor);
    }

    //
    // A_Fire — keeps the vile-fire mobj 24 fixed-units in front of
    // the target, oriented along target's facing.
    // p_enemy.c lines 1257–1277.
    public static void aFire(Mobj actor) {
        Mobj dest;
        int an;

        dest = actor.tracer;
        if (dest == null) {
            return;
        }

        // don't move it if the vile lost sight
        if (!PSight.pCheckSight(actor.target, dest)) {
            return;
        }

        an = (dest.angle >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;

        PMaputl.pUnsetThingPosition(actor);
        actor.x = dest.x + Fixed.mul(24 * Fixed.FRACUNIT, Tables.finecosine(an));
        actor.y = dest.y + Fixed.mul(24 * Fixed.FRACUNIT, Tables.finesine[an]);
        actor.z = dest.z;
        PMaputl.pSetThingPosition(actor);
    }

    //
    // A_VileTarget — Spawn the hellfire pre-attack.
    // p_enemy.c lines 1285–1302.
    public static void aVileTarget(Mobj actor) {
        Mobj fog;

        if (actor.target == null) {
            return;
        }

        aFaceTarget(actor);

        // C: P_SpawnMobj(actor->target->x, actor->target->x, ...)
        // — the second argument is x not y. It's a long-known typo;
        // preserved verbatim per CLAUDE.md §2 (deviation 29).
        fog = PMobj.pSpawnMobj(actor.target.x,
                actor.target.x,
                actor.target.z, Info.MT_FIRE);

        actor.tracer = fog;
        fog.target = actor;
        fog.tracer = actor.target;
        aFire(fog);
    }

    //
    // A_VileAttack — Hellfire concussion + radius damage.
    // p_enemy.c lines 1310–1338.
    public static void aVileAttack(Mobj actor) {
        Mobj fire;
        int an;

        if (actor.target == null) {
            return;
        }

        aFaceTarget(actor);

        if (!PSight.pCheckSight(actor, actor.target)) {
            return;
        }

        SSound.sStartSound(actor, Sounds.sfx_barexp);
        PInter.pDamageMobj(actor.target, actor, actor, 20);
        actor.target.momz = 1000 * Fixed.FRACUNIT / actor.target.info.mass;

        an = (actor.angle >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;

        fire = actor.tracer;

        if (fire == null) {
            return;
        }

        // move the fire between the vile and the player
        fire.x = actor.target.x - Fixed.mul(24 * Fixed.FRACUNIT, Tables.finecosine(an));
        fire.y = actor.target.y - Fixed.mul(24 * Fixed.FRACUNIT, Tables.finesine[an]);
        PMap.pRadiusAttack(fire, actor, 70);
    }

    // p_enemy.c line 1349 — Mancubus shot spread.
    public static final int FATSPREAD = Tables.ANG90 / 8;

    //
    // A_FatRaise.
    // p_enemy.c lines 1351–1355.
    public static void aFatRaise(Mobj actor) {
        aFaceTarget(actor);
        SSound.sStartSound(actor, Sounds.sfx_manatk);
    }

    //
    // A_FatAttack1 — first of three Mancubus volleys.
    // p_enemy.c lines 1358–1373.
    public static void aFatAttack1(Mobj actor) {
        Mobj mo;
        int an;

        aFaceTarget(actor);
        // Change direction to ...
        actor.angle += FATSPREAD;
        PMobj.pSpawnMissile(actor, actor.target, Info.MT_FATSHOT);

        mo = PMobj.pSpawnMissile(actor, actor.target, Info.MT_FATSHOT);
        mo.angle += FATSPREAD;
        an = (mo.angle >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;
        mo.momx = Fixed.mul(mo.info.speed, Tables.finecosine(an));
        mo.momy = Fixed.mul(mo.info.speed, Tables.finesine[an]);
    }

    //
    // A_FatAttack2.
    // p_enemy.c lines 1375–1390.
    public static void aFatAttack2(Mobj actor) {
        Mobj mo;
        int an;

        aFaceTarget(actor);
        // Now here choose opposite deviation.
        actor.angle -= FATSPREAD;
        PMobj.pSpawnMissile(actor, actor.target, Info.MT_FATSHOT);

        mo = PMobj.pSpawnMissile(actor, actor.target, Info.MT_FATSHOT);
        mo.angle -= FATSPREAD * 2;
        an = (mo.angle >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;
        mo.momx = Fixed.mul(mo.info.speed, Tables.finecosine(an));
        mo.momy = Fixed.mul(mo.info.speed, Tables.finesine[an]);
    }

    //
    // A_FatAttack3.
    // p_enemy.c lines 1392–1410.
    public static void aFatAttack3(Mobj actor) {
        Mobj mo;
        int an;

        aFaceTarget(actor);

        mo = PMobj.pSpawnMissile(actor, actor.target, Info.MT_FATSHOT);
        mo.angle -= FATSPREAD / 2;
        an = (mo.angle >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;
        mo.momx = Fixed.mul(mo.info.speed, Tables.finecosine(an));
        mo.momy = Fixed.mul(mo.info.speed, Tables.finesine[an]);

        mo = PMobj.pSpawnMissile(actor, actor.target, Info.MT_FATSHOT);
        mo.angle += FATSPREAD / 2;
        an = (mo.angle >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;
        mo.momx = Fixed.mul(mo.info.speed, Tables.finecosine(an));
        mo.momy = Fixed.mul(mo.info.speed, Tables.finesine[an]);
    }

    // p_enemy.c line 1417 — Lost Soul charge speed.
    public static final int SKULLSPEED = 20 * Fixed.FRACUNIT;

    //
    // A_SkullAttack — Lost Soul charge.
    // p_enemy.c lines 1419–1442.
    public static void aSkullAttack(Mobj actor) {
        Mobj dest;
        int an;
        int dist;

        if (actor.target == null) {
            return;
        }

        dest = actor.target;
        actor.flags |= Mobj.MF_SKULLFLY;

        SSound.sStartSound(actor, actor.info.attacksound);
        aFaceTarget(actor);
        an = (actor.angle >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;
        actor.momx = Fixed.mul(SKULLSPEED, Tables.finecosine(an));
        actor.momy = Fixed.mul(SKULLSPEED, Tables.finesine[an]);
        dist = PMaputl.pAproxDistance(dest.x - actor.x, dest.y - actor.y);
        dist = dist / SKULLSPEED;

        if (dist < 1) {
            dist = 1;
        }
        actor.momz = (dest.z + (dest.height >> 1) - actor.z) / dist;
    }

    //
    // A_PainShootSkull — Pain Elemental shoots a Lost Soul.
    // p_enemy.c lines 1450–1505. The C limits the level to 20 active
    // skulls; the cap here matches.
    public static void aPainShootSkull(Mobj actor, int angle) {
        int x;
        int y;
        int z;

        Mobj newmobj;
        int an;
        int prestep;
        int count;
        Thinker currentthinker;

        // count total number of skull currently on the level
        count = 0;

        currentthinker = PTick.thinkercap.next;
        while (currentthinker != PTick.thinkercap) {
            if (currentthinker instanceof Mobj
                    && ((Mobj) currentthinker).type == Info.MT_SKULL) {
                count++;
            }
            currentthinker = currentthinker.next;
        }

        // if there are allready 20 skulls on the level,
        // don't spit another one
        if (count > 20) {
            return;
        }


        // okay, there's playe for another one
        an = (angle >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;

        prestep =
                4 * Fixed.FRACUNIT
                + 3 * (actor.info.radius + Info.mobjinfo[Info.MT_SKULL].radius) / 2;

        x = actor.x + Fixed.mul(prestep, Tables.finecosine(an));
        y = actor.y + Fixed.mul(prestep, Tables.finesine[an]);
        z = actor.z + 8 * Fixed.FRACUNIT;

        newmobj = PMobj.pSpawnMobj(x, y, z, Info.MT_SKULL);

        // Check for movements.
        if (!PMap.pTryMove(newmobj, newmobj.x, newmobj.y)) {
            // kill it immediately
            PInter.pDamageMobj(newmobj, actor, actor, 10000);
            return;
        }

        newmobj.target = actor.target;
        aSkullAttack(newmobj);
    }

    //
    // A_PainAttack — Pain Elemental shoots a single skull.
    // p_enemy.c lines 1512–1519.
    public static void aPainAttack(Mobj actor) {
        if (actor.target == null) {
            return;
        }

        aFaceTarget(actor);
        aPainShootSkull(actor, actor.angle);
    }

    //
    // A_PainDie — Pain Elemental death sprays three skulls.
    // p_enemy.c lines 1522–1528.
    public static void aPainDie(Mobj actor) {
        aFall(actor);
        aPainShootSkull(actor, actor.angle + Tables.ANG90);
        aPainShootSkull(actor, actor.angle + Tables.ANG180);
        aPainShootSkull(actor, actor.angle + Tables.ANG270);
    }

    //
    // A_Scream — Shared monster death scream with sound randomization.
    // p_enemy.c lines 1535–1569.
    public static void aScream(Mobj actor) {
        int sound;

        switch (actor.info.deathsound) {
            case 0:
                return;

            case Sounds.sfx_podth1:
            case Sounds.sfx_podth2:
            case Sounds.sfx_podth3:
                sound = Sounds.sfx_podth1 + MRandom.pRandom() % 3;
                break;

            case Sounds.sfx_bgdth1:
            case Sounds.sfx_bgdth2:
                sound = Sounds.sfx_bgdth1 + MRandom.pRandom() % 2;
                break;

            default:
                sound = actor.info.deathsound;
                break;
        }

        // Check for bosses.
        if (actor.type == Info.MT_SPIDER
                || actor.type == Info.MT_CYBORG) {
            // full volume
            SSound.sStartSound(null, sound);
        } else {
            SSound.sStartSound(actor, sound);
        }
    }

    //
    // A_XScream — Gibbed-death sound.
    // p_enemy.c lines 1572–1575.
    public static void aXScream(Mobj actor) {
        SSound.sStartSound(actor, Sounds.sfx_slop);
    }

    //
    // A_Pain — Pain sound.
    // p_enemy.c lines 1577–1581.
    public static void aPain(Mobj actor) {
        if (actor.info.painsound != 0) {
            SSound.sStartSound(actor, actor.info.painsound);
        }
    }

    //
    // A_Explode — Barrel / rocket / mancubus radius blast.
    // p_enemy.c lines 1598–1601.
    public static void aExplode(Mobj thingy) {
        PMap.pRadiusAttack(thingy, thingy.target, 128);
    }

    //
    // A_BossDeath — End-of-level trigger when the last boss of the
    // matched type dies on the matching map.
    //
    // p_enemy.c lines 1609–1756. The C uses local `junk` line_t
    // initialized to zero except for tag.
    public static void aBossDeath(Mobj mo) {
        Thinker th;
        Mobj mo2;
        Line junk;
        int i;

        if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
            if (Doomstat.gamemap != 7) {
                return;
            }

            if (mo.type != Info.MT_FATSO
                    && mo.type != Info.MT_BABY) {
                return;
            }
        } else {
            switch (Doomstat.gameepisode) {
                case 1:
                    if (Doomstat.gamemap != 8) {
                        return;
                    }
                    if (mo.type != Info.MT_BRUISER) {
                        return;
                    }
                    break;

                case 2:
                    if (Doomstat.gamemap != 8) {
                        return;
                    }
                    if (mo.type != Info.MT_CYBORG) {
                        return;
                    }
                    break;

                case 3:
                    if (Doomstat.gamemap != 8) {
                        return;
                    }
                    if (mo.type != Info.MT_SPIDER) {
                        return;
                    }
                    break;

                case 4:
                    switch (Doomstat.gamemap) {
                        case 6:
                            if (mo.type != Info.MT_CYBORG) {
                                return;
                            }
                            break;

                        case 8:
                            if (mo.type != Info.MT_SPIDER) {
                                return;
                            }
                            break;

                        default:
                            return;
                    }
                    break;

                default:
                    if (Doomstat.gamemap != 8) {
                        return;
                    }
                    break;
            }

        }


        // make sure there is a player alive for victory
        for (i = 0; i < Doomdef.MAXPLAYERS; i++) {
            if (GGame.playeringame[i] && GGame.players[i].health > 0) {
                break;
            }
        }

        if (i == Doomdef.MAXPLAYERS) {
            return; // no one left alive, so do not end game
        }

        // scan the remaining thinkers to see
        // if all bosses are dead
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
                // other boss not dead
                return;
            }
        }

        // victory!
        if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
            if (Doomstat.gamemap == 7) {
                if (mo.type == Info.MT_FATSO) {
                    junk = new Line();
                    junk.tag = 666;
                    PFloor.evDoFloor(junk, PFloor.lowerFloorToLowest);
                    return;
                }

                if (mo.type == Info.MT_BABY) {
                    junk = new Line();
                    junk.tag = 667;
                    PFloor.evDoFloor(junk, PFloor.raiseToTexture);
                    return;
                }
            }
        } else {
            switch (Doomstat.gameepisode) {
                case 1:
                    junk = new Line();
                    junk.tag = 666;
                    PFloor.evDoFloor(junk, PFloor.lowerFloorToLowest);
                    return;

                case 4:
                    switch (Doomstat.gamemap) {
                        case 6:
                            junk = new Line();
                            junk.tag = 666;
                            PDoors.evDoDoor(junk, PDoors.blazeOpen);
                            return;

                        case 8:
                            junk = new Line();
                            junk.tag = 666;
                            PFloor.evDoFloor(junk, PFloor.lowerFloorToLowest);
                            return;
                    }
            }
        }

        doom.system.GGame.gExitLevel();
    }

    //
    // A_Hoof — Cyberdemon footstep + chase.
    // p_enemy.c lines 1759–1763.
    public static void aHoof(Mobj mo) {
        SSound.sStartSound(mo, Sounds.sfx_hoof);
        aChase(mo);
    }

    //
    // A_Metal — Spider mastermind footstep + chase.
    // p_enemy.c lines 1765–1769.
    public static void aMetal(Mobj mo) {
        SSound.sStartSound(mo, Sounds.sfx_metal);
        aChase(mo);
    }

    //
    // A_BabyMetal — Arachnotron footstep + chase.
    // p_enemy.c lines 1771–1775.
    public static void aBabyMetal(Mobj mo) {
        SSound.sStartSound(mo, Sounds.sfx_bspwlk);
        aChase(mo);
    }

    //
    // A_OpenShotgun2 — Super shotgun open sound (player psprite).
    // p_enemy.c lines 1778–1783. C signature is (player_t*, pspdef_t*).
    public static void aOpenShotgun2(Player player, Pspdef psp) {
        SSound.sStartSound(player.mo, Sounds.sfx_dbopn);
    }

    //
    // A_LoadShotgun2 — Super shotgun reload sound.
    // p_enemy.c lines 1786–1791.
    public static void aLoadShotgun2(Player player, Pspdef psp) {
        SSound.sStartSound(player.mo, Sounds.sfx_dbload);
    }

    //
    // A_CloseShotgun2 — Super shotgun close + re-fire.
    // p_enemy.c lines 1799–1805.
    public static void aCloseShotgun2(Player player, Pspdef psp) {
        SSound.sStartSound(player.mo, Sounds.sfx_dbcls);
        PPspr.aReFire(player, psp);
    }

    // p_enemy.c lines 1809–1811 — Brain spitter target table.
    public static final Mobj[] braintargets = new Mobj[32];
    public static int numbraintargets;
    public static int braintargeton;

    //
    // A_BrainAwake — Builds the braintargets[] list of MT_BOSSTARGET
    // mobjs and emits the wake-up sound.
    // p_enemy.c lines 1813–1840.
    public static void aBrainAwake(Mobj mo) {
        Thinker thinker;
        Mobj m;

        // find all the target spots
        numbraintargets = 0;
        braintargeton = 0;

        for (thinker = PTick.thinkercap.next;
                thinker != PTick.thinkercap;
                thinker = thinker.next) {
            if (!(thinker instanceof Mobj)) {
                continue;       // not a mobj
            }

            m = (Mobj) thinker;

            if (m.type == Info.MT_BOSSTARGET) {
                braintargets[numbraintargets] = m;
                numbraintargets++;
            }
        }

        SSound.sStartSound(null, Sounds.sfx_bossit);
    }

    //
    // A_BrainPain — Brain pain sound.
    // p_enemy.c lines 1843–1846.
    public static void aBrainPain(Mobj mo) {
        SSound.sStartSound(null, Sounds.sfx_bospn);
    }

    //
    // A_BrainScream — Brain death-explosion ribbon.
    // p_enemy.c lines 1849–1871.
    public static void aBrainScream(Mobj mo) {
        int x;
        int y;
        int z;
        Mobj th;

        for (x = mo.x - 196 * Fixed.FRACUNIT;
                x < mo.x + 320 * Fixed.FRACUNIT;
                x += Fixed.FRACUNIT * 8) {
            y = mo.y - 320 * Fixed.FRACUNIT;
            z = 128 + MRandom.pRandom() * 2 * Fixed.FRACUNIT;
            th = PMobj.pSpawnMobj(x, y, z, Info.MT_ROCKET);
            th.momz = MRandom.pRandom() * 512;

            PMobj.pSetMobjState(th, Info.S_BRAINEXPLODE1);

            th.tics -= MRandom.pRandom() & 7;
            if (th.tics < 1) {
                th.tics = 1;
            }
        }

        SSound.sStartSound(null, Sounds.sfx_bosdth);
    }

    //
    // A_BrainExplode — Per-tic puff during the death ribbon.
    // p_enemy.c lines 1875–1893.
    public static void aBrainExplode(Mobj mo) {
        int x;
        int y;
        int z;
        Mobj th;

        x = mo.x + (MRandom.pRandom() - MRandom.pRandom()) * 2048;
        y = mo.y;
        z = 128 + MRandom.pRandom() * 2 * Fixed.FRACUNIT;
        th = PMobj.pSpawnMobj(x, y, z, Info.MT_ROCKET);
        th.momz = MRandom.pRandom() * 512;

        PMobj.pSetMobjState(th, Info.S_BRAINEXPLODE1);

        th.tics -= MRandom.pRandom() & 7;
        if (th.tics < 1) {
            th.tics = 1;
        }
    }

    //
    // A_BrainDie — Triggers level exit.
    // p_enemy.c lines 1896–1899.
    public static void aBrainDie(Mobj mo) {
        doom.system.GGame.gExitLevel();
    }

    // p_enemy.c line 1906 — A_BrainSpit alternation flag.
    private static int brainSpitEasy = 0;

    //
    // A_BrainSpit — Romero head spits a cube at the next target.
    // p_enemy.c lines 1901–1923. The C `static int easy = 0;` flag
    // alternates each call so on sk_easy / sk_baby the spitter only
    // fires every other tic; preserved as a class-level int.
    public static void aBrainSpit(Mobj mo) {
        Mobj targ;
        Mobj newmobj;

        brainSpitEasy ^= 1;
        if (Doomstat.gameskill <= Doomstat.sk_easy && brainSpitEasy == 0) {
            return;
        }

        // shoot a cube at current target
        targ = braintargets[braintargeton];
        braintargeton = (braintargeton + 1) % numbraintargets;

        // spawn brain missile
        newmobj = PMobj.pSpawnMissile(mo, targ, Info.MT_SPAWNSHOT);
        newmobj.target = targ;
        newmobj.reactiontime =
                ((targ.y - mo.y) / newmobj.momy) / newmobj.state.tics;

        SSound.sStartSound(null, Sounds.sfx_bospit);
    }

    //
    // A_SpawnSound — Travelling cube ambient sound + delegate.
    // p_enemy.c lines 1930–1934.
    public static void aSpawnSound(Mobj mo) {
        SSound.sStartSound(mo, Sounds.sfx_boscub);
        aSpawnFly(mo);
    }

    //
    // A_SpawnFly — Cube reaches target → randomized monster spawn.
    // p_enemy.c lines 1936–1990.
    public static void aSpawnFly(Mobj mo) {
        Mobj newmobj;
        Mobj fog;
        Mobj targ;
        int r;
        int type;

        if (--mo.reactiontime != 0) {
            return; // still flying
        }

        targ = mo.target;

        // First spawn teleport fog.
        fog = PMobj.pSpawnMobj(targ.x, targ.y, targ.z, Info.MT_SPAWNFIRE);
        SSound.sStartSound(fog, Sounds.sfx_telept);

        // Randomly select monster to spawn.
        r = MRandom.pRandom();

        // Probability distribution (kind of :),
        // decreasing likelihood.
        if (r < 50) {
            type = Info.MT_TROOP;
        } else if (r < 90) {
            type = Info.MT_SERGEANT;
        } else if (r < 120) {
            type = Info.MT_SHADOWS;
        } else if (r < 130) {
            type = Info.MT_PAIN;
        } else if (r < 160) {
            type = Info.MT_HEAD;
        } else if (r < 162) {
            type = Info.MT_VILE;
        } else if (r < 172) {
            type = Info.MT_UNDEAD;
        } else if (r < 192) {
            type = Info.MT_BABY;
        } else if (r < 222) {
            type = Info.MT_FATSO;
        } else if (r < 246) {
            type = Info.MT_KNIGHT;
        } else {
            type = Info.MT_BRUISER;
        }

        newmobj = PMobj.pSpawnMobj(targ.x, targ.y, targ.z, type);
        if (pLookForPlayers(newmobj, true)) {
            PMobj.pSetMobjState(newmobj, newmobj.info.seestate);
        }

        // telefrag anything in this spot
        PMap.pTeleportMove(newmobj, newmobj.x, newmobj.y);

        // remove self (i.e., cube).
        PMobj.pRemoveMobj(mo);
    }

    //
    // A_PlayerScream — Player death scream.
    // p_enemy.c lines 1994–2007.
    public static void aPlayerScream(Mobj mo) {
        // Default death sound.
        int sound = Sounds.sfx_pldeth;

        if (Doomstat.gamemode == Doomstat.GameMode.commercial
                && mo.health < -50) {
            // IF THE PLAYER DIES
            // LESS THAN -50% WITHOUT GIBBING
            sound = Sounds.sfx_pdiehi;
        }

        SSound.sStartSound(mo, sound);
    }
}
