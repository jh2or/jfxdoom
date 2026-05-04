// Port of linuxdoom-1.10/p_mobj.c — partial.
//
// Core mobj lifecycle: state machine + spawn/remove + per-tic
// X/Y/Z movement + helper spawners (puff, blood, missile-spawn
// commit) + level-load placement (P_SpawnPlayer, P_SpawnMapThing).
// Drives the playsim's per-tic mobj tick from PTick once P_Ticker
// lands.
//
// All sub-functions now ported. PIT_CheckThing's skull/missile/
// pickup branches are wired through PInter; P_SpawnMissile and
// P_SpawnPlayerMissile use PMap.pAimLineAttack now that combat is
// online.
package doom.play;

import doom.fixed.Fixed;
import doom.render.RMain;
import doom.render.RSky;
import doom.render.Tables;
import doom.sound.SSound;
import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.GGame;
import doom.system.ISystem;
import doom.ui.HuStuff;
import doom.ui.StStuff;

public final class PMobj {

    // p_local.h timing/limit constants used by the lifecycle.
    public static final int FLOATSPEED   = Fixed.FRACUNIT * 4;
    public static final int GRAVITY      = Fixed.FRACUNIT;
    public static final int MAXMOVE      = 30 * Fixed.FRACUNIT;
    public static final int MELEERANGE   = 64 * Fixed.FRACUNIT;
    public static final int MISSILERANGE = 32 * 64 * Fixed.FRACUNIT;

    // p_local.h ONFLOORZ / ONCEILINGZ — sentinel z values for
    // P_SpawnMobj telling it to snap to the sector floor or ceiling.
    public static final int ONFLOORZ   = Integer.MIN_VALUE;
    public static final int ONCEILINGZ = Integer.MAX_VALUE;

    // p_mobj.c line 111–112 — XY-movement deceleration tuning.
    public static final int STOPSPEED = 0x1000;
    public static final int FRICTION  = 0xe800;

    private PMobj() {
    }

    //
    // P_SetMobjState
    // Returns true if the mobj is still present.
    //
    // p_mobj.c lines 53–84.
    public static boolean pSetMobjState(Mobj mobj, int state) {
        State st;

        do {
            if (state == Info.S_NULL) {
                mobj.state = null; // C: (state_t*) S_NULL
                pRemoveMobj(mobj);
                return false;
            }

            st = Info.states[state];
            mobj.state = st;
            mobj.tics = st.tics;
            mobj.sprite = st.sprite;
            mobj.frame = st.frame;

            // Modified handling. Call action functions when the
            // state is set.
            if (st.action != null) {
                st.action.accept(mobj);
            }

            state = st.nextstate;
        } while (mobj.tics == 0);

        return true;
    }

    //
    // P_ExplodeMissile
    //
    // p_mobj.c lines 90–105.
    public static void pExplodeMissile(Mobj mo) {
        mo.momx = mo.momy = mo.momz = 0;

        pSetMobjState(mo, mo.info.deathstate);

        mo.tics -= MRandom.pRandom() & 3;

        if (mo.tics < 1) {
            mo.tics = 1;
        }

        mo.flags &= ~Mobj.MF_MISSILE;

        if (mo.info.deathsound != 0) {
            SSound.sStartSound(mo, mo.info.deathsound);
        }
    }

    //
    // P_XYMovement
    //
    // p_mobj.c lines 114–241.
    public static void pXYMovement(Mobj mo) {
        int ptryx;
        int ptryy;
        Player player;
        int xmove;
        int ymove;

        if (mo.momx == 0 && mo.momy == 0) {
            if ((mo.flags & Mobj.MF_SKULLFLY) != 0) {
                // the skull slammed into something
                mo.flags &= ~Mobj.MF_SKULLFLY;
                mo.momx = mo.momy = mo.momz = 0;

                pSetMobjState(mo, mo.info.spawnstate);
            }
            return;
        }

        player = mo.player;

        if (mo.momx > MAXMOVE) {
            mo.momx = MAXMOVE;
        } else if (mo.momx < -MAXMOVE) {
            mo.momx = -MAXMOVE;
        }

        if (mo.momy > MAXMOVE) {
            mo.momy = MAXMOVE;
        } else if (mo.momy < -MAXMOVE) {
            mo.momy = -MAXMOVE;
        }

        xmove = mo.momx;
        ymove = mo.momy;

        do {
            if (xmove > MAXMOVE / 2 || ymove > MAXMOVE / 2) {
                ptryx = mo.x + xmove / 2;
                ptryy = mo.y + ymove / 2;
                xmove >>= 1;
                ymove >>= 1;
            } else {
                ptryx = mo.x + xmove;
                ptryy = mo.y + ymove;
                xmove = ymove = 0;
            }

            if (!PMap.pTryMove(mo, ptryx, ptryy)) {
                // blocked move
                if (mo.player != null) {
                    // try to slide along it
                    PMap.pSlideMove(mo);
                } else if ((mo.flags & Mobj.MF_MISSILE) != 0) {
                    // explode a missile
                    if (PMap.ceilingline != null
                            && PMap.ceilingline.backsector != null
                            && PMap.ceilingline.backsector.ceilingpic == RSky.skyflatnum) {
                        // Hack to prevent missiles exploding against
                        // the sky. Does not handle sky floors.
                        pRemoveMobj(mo);
                        return;
                    }
                    pExplodeMissile(mo);
                } else {
                    mo.momx = mo.momy = 0;
                }
            }
        } while (xmove != 0 || ymove != 0);

        // slow down
        // CF_NOMOMENTUM is a player cheat — Player.cheats not yet
        // populated, so the cheat short-circuit lands when p_user does.

        if ((mo.flags & (Mobj.MF_MISSILE | Mobj.MF_SKULLFLY)) != 0) {
            return; // no friction for missiles ever
        }

        if (mo.z > mo.floorz) {
            return; // no friction when airborne
        }

        if ((mo.flags & Mobj.MF_CORPSE) != 0) {
            // do not stop sliding if halfway off a step with some
            // momentum
            if (mo.momx > Fixed.FRACUNIT / 4
                    || mo.momx < -Fixed.FRACUNIT / 4
                    || mo.momy > Fixed.FRACUNIT / 4
                    || mo.momy < -Fixed.FRACUNIT / 4) {
                if (mo.floorz != mo.subsector.sector.floorheight) {
                    return;
                }
            }
        }

        if (mo.momx > -STOPSPEED && mo.momx < STOPSPEED
                && mo.momy > -STOPSPEED && mo.momy < STOPSPEED
                && (player == null
                    || (/* player.cmd.forwardmove == 0 && player.cmd.sidemove == 0 */
                        true /* TODO(p_user): inspect ticcmd when ported */))) {
            // if in a walking frame, stop moving
            // The states[] arithmetic here (state - states - S_PLAY_RUN1 < 4)
            // needs Mobj.state's index — defer the player-walk-stop
            // sub-branch until p_user lands its full ticcmd handling.
            // The bare momx/momy zeroing below still fires.
            mo.momx = 0;
            mo.momy = 0;
        } else {
            mo.momx = Fixed.mul(mo.momx, FRICTION);
            mo.momy = Fixed.mul(mo.momy, FRICTION);
        }
    }

    //
    // P_ZMovement
    //
    // p_mobj.c lines 246–349.
    public static void pZMovement(Mobj mo) {
        int dist;
        int delta;

        // check for smooth step up
        if (mo.player != null && mo.z < mo.floorz) {
            // The player.viewheight / .deltaviewheight write lands
            // when p_user adds those fields. Skipping this branch's
            // visual side-effect doesn't change physics.
        }

        // adjust height
        mo.z += mo.momz;

        if ((mo.flags & Mobj.MF_FLOAT) != 0
                && mo.target != null) {
            // float down towards target if too close
            if ((mo.flags & Mobj.MF_SKULLFLY) == 0
                    && (mo.flags & Mobj.MF_INFLOAT) == 0) {
                dist = PMaputl.pAproxDistance(mo.x - mo.target.x,
                        mo.y - mo.target.y);

                delta = (mo.target.z + (mo.height >> 1)) - mo.z;

                if (delta < 0 && dist < -(delta * 3)) {
                    mo.z -= FLOATSPEED;
                } else if (delta > 0 && dist < (delta * 3)) {
                    mo.z += FLOATSPEED;
                }
            }
        }

        // clip movement
        if (mo.z <= mo.floorz) {
            // hit the floor

            // Note (id): somebody left this after the setting momz
            // to 0, kinda useless there.
            if ((mo.flags & Mobj.MF_SKULLFLY) != 0) {
                // the skull slammed into something
                mo.momz = -mo.momz;
            }

            if (mo.momz < 0) {
                if (mo.player != null && mo.momz < -GRAVITY * 8) {
                    // Squat down. Decrease viewheight for a moment
                    // after hitting the ground (hard), and utter
                    // appropriate sound. Visual squat lands with p_user.
                    SSound.sStartSound(mo, doom.sound.Sounds.sfx_oof);
                }
                mo.momz = 0;
            }
            mo.z = mo.floorz;

            if ((mo.flags & Mobj.MF_MISSILE) != 0
                    && (mo.flags & Mobj.MF_NOCLIP) == 0) {
                pExplodeMissile(mo);
                return;
            }
        } else if ((mo.flags & Mobj.MF_NOGRAVITY) == 0) {
            if (mo.momz == 0) {
                mo.momz = -GRAVITY * 2;
            } else {
                mo.momz -= GRAVITY;
            }
        }

        if (mo.z + mo.height > mo.ceilingz) {
            // hit the ceiling
            if (mo.momz > 0) {
                mo.momz = 0;
            }
            {
                mo.z = mo.ceilingz - mo.height;
            }

            if ((mo.flags & Mobj.MF_SKULLFLY) != 0) {
                // the skull slammed into something
                mo.momz = -mo.momz;
            }

            if ((mo.flags & Mobj.MF_MISSILE) != 0
                    && (mo.flags & Mobj.MF_NOCLIP) == 0) {
                pExplodeMissile(mo);
                return;
            }
        }
    }

    //
    // P_NightmareRespawn
    //
    // p_mobj.c lines 357–409. Recreates `mobj` at its spawnpoint
    // with full health, leaving a teleport fog at both source and
    // destination.
    public static void pNightmareRespawn(Mobj mobj) {
        int x;
        int y;
        int z;
        doom.render.Subsector ss;
        Mobj mo;
        Mapthing mthing;

        x = mobj.spawnpoint.x << Fixed.FRACBITS;
        y = mobj.spawnpoint.y << Fixed.FRACBITS;

        // somthing is occupying it's position?
        if (!PMap.pCheckPosition(mobj, x, y)) {
            return; // no respwan
        }

        // spawn a teleport fog at old spot because of removal of the body?
        mo = pSpawnMobj(mobj.x,
                mobj.y,
                mobj.subsector.sector.floorheight, Info.MT_TFOG);
        // initiate teleport sound
        SSound.sStartSound(mo, doom.sound.Sounds.sfx_telept);

        // spawn a teleport fog at the new spot
        ss = RMain.rPointInSubsector(x, y);

        mo = pSpawnMobj(x, y, ss.sector.floorheight, Info.MT_TFOG);

        SSound.sStartSound(mo, doom.sound.Sounds.sfx_telept);

        // spawn the new monster
        mthing = mobj.spawnpoint;

        // spawn it
        if ((mobj.info.flags & Mobj.MF_SPAWNCEILING) != 0) {
            z = ONCEILINGZ;
        } else {
            z = ONFLOORZ;
        }

        // inherit attributes from deceased one
        mo = pSpawnMobj(x, y, z, mobj.type);
        mo.spawnpoint = mobj.spawnpoint;
        mo.angle = Tables.ANG45 * (mthing.angle / 45);

        if ((mthing.options & Mapthing.MTF_AMBUSH) != 0) {
            mo.flags |= Mobj.MF_AMBUSH;
        }

        mo.reactiontime = 18;

        // remove the old monster
        pRemoveMobj(mobj);
    }

    //
    // P_MobjThinker
    //
    // p_mobj.c lines 415–473. The C compares
    // `thinker.function.acv == (actionf_v)(-1)` after each movement
    // helper to detect "mobj was removed". Java uses Thinker.removed
    // (deviation 14) — same semantics.
    public static void pMobjThinker(Thinker t) {
        Mobj mobj = (Mobj) t;

        // momentum movement
        if (mobj.momx != 0 || mobj.momy != 0
                || (mobj.flags & Mobj.MF_SKULLFLY) != 0) {
            pXYMovement(mobj);

            if (mobj.removed) {
                return; // mobj was removed
            }
        }
        if (mobj.z != mobj.floorz || mobj.momz != 0) {
            pZMovement(mobj);

            if (mobj.removed) {
                return;
            }
        }

        // cycle through states, calling action functions at transitions
        if (mobj.tics != -1) {
            mobj.tics--;

            // you can cycle through multiple states in a tic
            if (mobj.tics == 0) {
                if (!pSetMobjState(mobj, mobj.state.nextstate)) {
                    return; // freed itself
                }
            }
        } else {
            // check for nightmare respawn
            if ((mobj.flags & Mobj.MF_COUNTKILL) == 0) {
                return;
            }

            if (!Doomstat.respawnmonsters) {
                return;
            }

            mobj.movecount++;

            if (mobj.movecount < 12 * 35) {
                return;
            }

            if ((PTick.leveltime & 31) != 0) {
                return;
            }

            if (MRandom.pRandom() > 4) {
                return;
            }

            pNightmareRespawn(mobj);
        }
    }

    //
    // P_SpawnMobj
    //
    // p_mobj.c lines 479–534.
    public static Mobj pSpawnMobj(int x, int y, int z, int type) {
        Mobj mobj;
        State st;
        MobjInfo info;

        mobj = new Mobj();
        info = Info.mobjinfo[type];

        mobj.type = type;
        mobj.info = info;
        mobj.x = x;
        mobj.y = y;
        mobj.radius = info.radius;
        mobj.height = info.height;
        mobj.flags = info.flags;
        mobj.health = info.spawnhealth;

        if (Doomstat.gameskill != Doomstat.sk_nightmare) {
            mobj.reactiontime = info.reactiontime;
        }

        mobj.lastlook = MRandom.pRandom() % Doomdef.MAXPLAYERS;
        // do not set the state with P_SetMobjState, because action
        // routines can not be called yet
        st = Info.states[info.spawnstate];

        mobj.state = st;
        mobj.tics = st.tics;
        mobj.sprite = st.sprite;
        mobj.frame = st.frame;

        // set subsector and/or block links
        PMaputl.pSetThingPosition(mobj);

        mobj.floorz = mobj.subsector.sector.floorheight;
        mobj.ceilingz = mobj.subsector.sector.ceilingheight;

        if (z == ONFLOORZ) {
            mobj.z = mobj.floorz;
        } else if (z == ONCEILINGZ) {
            mobj.z = mobj.ceilingz - mobj.info.height;
        } else {
            mobj.z = z;
        }

        mobj.function.acp1 = PMobj::pMobjThinker;

        PTick.pAddThinker(mobj);

        return mobj;
    }

    //
    // p_local.h line 99 — item-respawn queue size (must be a power
    // of two for the (iqueX+1)&(ITEMQUESIZE-1) ring-buffer trick).
    public static final int ITEMQUESIZE = 128;

    // p_mobj.c lines 540–543 — ring buffer of items pending respawn
    // (deathmatch -altdeath). P_RemoveMobj enqueues; P_RespawnSpecials
    // dequeues after 30 seconds.
    public static final Mapthing[] itemrespawnque = new Mapthing[ITEMQUESIZE];
    public static final int[] itemrespawntime = new int[ITEMQUESIZE];
    public static int iquehead;
    public static int iquetail;

    //
    // P_RemoveMobj
    //
    // p_mobj.c lines 546–570.
    public static void pRemoveMobj(Mobj mobj) {
        if ((mobj.flags & Mobj.MF_SPECIAL) != 0
                && (mobj.flags & Mobj.MF_DROPPED) == 0
                && mobj.type != Info.MT_INV
                && mobj.type != Info.MT_INS) {
            itemrespawnque[iquehead] = mobj.spawnpoint;
            itemrespawntime[iquehead] = PTick.leveltime;
            iquehead = (iquehead + 1) & (ITEMQUESIZE - 1);

            // lose one off the end?
            if (iquehead == iquetail) {
                iquetail = (iquetail + 1) & (ITEMQUESIZE - 1);
            }
        }

        // unlink from sector and block lists
        PMaputl.pUnsetThingPosition(mobj);

        // stop any playing sound
        // TODO: SSound.sStopSound(mobj) once the sound channel
        //       table lands.

        // free block — i.e. mark thinker for removal. P_RunThinkers
        // unlinks it on the next pass.
        PTick.pRemoveThinker(mobj);
    }

    //
    // P_RespawnSpecials
    //
    // p_mobj.c lines 578–631. Pulls an item placement off the
    // respawn queue once it has aged 30+ seconds and recreates the
    // mobj. -altdeath only (deathmatch == 2).
    public static void pRespawnSpecials() {
        int x;
        int y;
        int z;

        doom.render.Subsector ss;
        Mobj mo;
        Mapthing mthing;

        int i;

        // only respawn items in deathmatch
        if (Doomstat.deathmatch != 2) {
            return;
        }

        // nothing left to respawn?
        if (iquehead == iquetail) {
            return;
        }

        // wait at least 30 seconds
        if (PTick.leveltime - itemrespawntime[iquetail] < 30 * 35) {
            return;
        }

        mthing = itemrespawnque[iquetail];

        x = mthing.x << Fixed.FRACBITS;
        y = mthing.y << Fixed.FRACBITS;

        // spawn a teleport fog at the new spot
        ss = RMain.rPointInSubsector(x, y);
        mo = pSpawnMobj(x, y, ss.sector.floorheight, Info.MT_IFOG);
        SSound.sStartSound(mo, doom.sound.Sounds.sfx_itmbk);

        // find which type to spawn
        for (i = 0; i < Info.NUMMOBJTYPES; i++) {
            if (mthing.type == Info.mobjinfo[i].doomednum) {
                break;
            }
        }

        // spawn it
        if ((Info.mobjinfo[i].flags & Mobj.MF_SPAWNCEILING) != 0) {
            z = ONCEILINGZ;
        } else {
            z = ONFLOORZ;
        }

        mo = pSpawnMobj(x, y, z, i);
        mo.spawnpoint = mthing;
        mo.angle = Tables.ANG45 * (mthing.angle / 45);

        // pull it from the que
        iquetail = (iquetail + 1) & (ITEMQUESIZE - 1);
    }

    //
    // P_SpawnPlayer
    // Called when a player is spawned on the level.
    // Most of the player structure stays unchanged
    //  between levels.
    //
    // p_mobj.c lines 642–700.
    //
    // P_SetupPsprites is dispatched through PPspr.pSetupPsprites
    // (no-op stub until p_pspr lands). ST_Start / HU_Start dispatch
    // through StStuff.stStart / HuStuff.huStart (likewise stubs).
    public static void pSpawnPlayer(Mapthing mthing) {
        Player p;
        int x;
        int y;
        int z;

        Mobj mobj;

        int i;

        // not playing?
        if (!GGame.playeringame[mthing.type - 1]) {
            return;
        }

        p = GGame.players[mthing.type - 1];

        if (p.playerstate == Player.PST_REBORN) {
            GGame.gPlayerReborn(mthing.type - 1);
        }

        x = mthing.x << Fixed.FRACBITS;
        y = mthing.y << Fixed.FRACBITS;
        z = ONFLOORZ;
        mobj = pSpawnMobj(x, y, z, Info.MT_PLAYER);

        // set color translations for player sprites
        if (mthing.type > 1) {
            mobj.flags |= (mthing.type - 1) << Mobj.MF_TRANSSHIFT;
        }

        mobj.angle = Tables.ANG45 * (mthing.angle / 45);
        mobj.player = p;
        mobj.health = p.health;

        p.mo = mobj;
        p.playerstate = Player.PST_LIVE;
        p.refire = 0;
        p.message = null;
        p.damagecount = 0;
        p.bonuscount = 0;
        p.extralight = 0;
        p.fixedcolormap = 0;
        p.viewheight = VIEWHEIGHT;

        // setup gun psprite
        PPspr.pSetupPsprites(p);

        // give all cards in death match mode
        if (Doomstat.deathmatch != 0) {
            for (i = 0; i < Doomdef.NUMCARDS; i++) {
                p.cards[i] = true;
            }
        }

        if (mthing.type - 1 == GGame.consoleplayer) {
            // wake up the status bar
            StStuff.stStart();
            // wake up the heads up text
            HuStuff.huStart();
        }
    }

    // p_local.h line 34 — VIEWHEIGHT.
    public static final int VIEWHEIGHT = 41 * Fixed.FRACUNIT;

    //
    // P_SpawnMapThing
    // The fields of the mapthing should
    // already be in host byte order.
    //
    // p_mobj.c lines 708–797.
    public static void pSpawnMapThing(Mapthing mthing) {
        int i;
        int bit;
        Mobj mobj;
        int x;
        int y;
        int z;

        // count deathmatch start positions
        if (mthing.type == 11) {
            if (PSetup.deathmatchPIndex < 10) {
                // C: memcpy (deathmatch_p, mthing, sizeof(*mthing));
                // the slot owns its own Mapthing copy so the source
                // entry can be discarded.
                PSetup.deathmatchstarts[PSetup.deathmatchPIndex] = mthing.copy();
                PSetup.deathmatchPIndex++;
            }
            return;
        }

        // check for players specially
        if (mthing.type <= 4) {
            // save spots for respawning in network games
            PSetup.playerstarts[mthing.type - 1] = mthing.copy();
            if (Doomstat.deathmatch == 0) {
                pSpawnPlayer(mthing);
            }

            return;
        }

        // check for apropriate skill level
        if (!Doomstat.netgame && (mthing.options & 16) != 0) {
            return;
        }

        if (Doomstat.gameskill == Doomstat.sk_baby) {
            bit = 1;
        } else if (Doomstat.gameskill == Doomstat.sk_nightmare) {
            bit = 4;
        } else {
            bit = 1 << (Doomstat.gameskill - 1);
        }

        if ((mthing.options & bit) == 0) {
            return;
        }

        // find which type to spawn
        for (i = 0; i < Info.NUMMOBJTYPES; i++) {
            if (mthing.type == Info.mobjinfo[i].doomednum) {
                break;
            }
        }

        if (i == Info.NUMMOBJTYPES) {
            ISystem.iError("P_SpawnMapThing: Unknown type "
                    + mthing.type + " at (" + mthing.x + ", "
                    + mthing.y + ")");
        }

        // don't spawn keycards and players in deathmatch
        if (Doomstat.deathmatch != 0
                && (Info.mobjinfo[i].flags & Mobj.MF_NOTDMATCH) != 0) {
            return;
        }

        // don't spawn any monsters if -nomonsters
        if (Doomstat.nomonsters
                && (i == Info.MT_SKULL
                    || (Info.mobjinfo[i].flags & Mobj.MF_COUNTKILL) != 0)) {
            return;
        }

        // spawn it
        x = mthing.x << Fixed.FRACBITS;
        y = mthing.y << Fixed.FRACBITS;

        if ((Info.mobjinfo[i].flags & Mobj.MF_SPAWNCEILING) != 0) {
            z = ONCEILINGZ;
        } else {
            z = ONFLOORZ;
        }

        mobj = pSpawnMobj(x, y, z, i);
        mobj.spawnpoint = mthing.copy();

        if (mobj.tics > 0) {
            mobj.tics = 1 + (MRandom.pRandom() % mobj.tics);
        }
        if ((mobj.flags & Mobj.MF_COUNTKILL) != 0) {
            Doomstat.totalkills++;
        }
        if ((mobj.flags & Mobj.MF_COUNTITEM) != 0) {
            Doomstat.totalitems++;
        }

        mobj.angle = Tables.ANG45 * (mthing.angle / 45);
        if ((mthing.options & Mapthing.MTF_AMBUSH) != 0) {
            mobj.flags |= Mobj.MF_AMBUSH;
        }
    }

    //
    // P_SpawnPuff
    //
    // p_mobj.c lines 811–831. attackrange is set by P_AimLineAttack
    // / P_LineAttack (p_map combat — deferred); until then it stays
    // at 0 and the punch-spark special case below never triggers.
    public static int attackrange; // fixed_t

    public static void pSpawnPuff(int x, int y, int z) {
        Mobj th;

        z += ((MRandom.pRandom() - MRandom.pRandom()) << 10);

        th = pSpawnMobj(x, y, z, Info.MT_PUFF);
        th.momz = Fixed.FRACUNIT;
        th.tics -= MRandom.pRandom() & 3;

        if (th.tics < 1) {
            th.tics = 1;
        }

        // don't make punches spark on the wall
        if (attackrange == MELEERANGE) {
            pSetMobjState(th, Info.S_PUFF3);
        }
    }

    //
    // P_SpawnBlood
    //
    // p_mobj.c lines 838–859.
    public static void pSpawnBlood(int x, int y, int z, int damage) {
        Mobj th;

        z += ((MRandom.pRandom() - MRandom.pRandom()) << 10);
        th = pSpawnMobj(x, y, z, Info.MT_BLOOD);
        th.momz = Fixed.FRACUNIT * 2;
        th.tics -= MRandom.pRandom() & 3;

        if (th.tics < 1) {
            th.tics = 1;
        }

        if (damage <= 12 && damage >= 9) {
            pSetMobjState(th, Info.S_BLOOD2);
        } else if (damage < 9) {
            pSetMobjState(th, Info.S_BLOOD3);
        }
    }

    //
    // P_CheckMissileSpawn
    // Moves the missile forward a bit and possibly explodes it
    // right there.
    //
    // p_mobj.c lines 868–882.
    public static void pCheckMissileSpawn(Mobj th) {
        th.tics -= MRandom.pRandom() & 3;
        if (th.tics < 1) {
            th.tics = 1;
        }

        // move a little forward so an angle can be computed if it
        // immediately explodes
        th.x += (th.momx >> 1);
        th.y += (th.momy >> 1);
        th.z += (th.momz >> 1);

        if (!PMap.pTryMove(th, th.x, th.y)) {
            pExplodeMissile(th);
        }
    }

    //
    // P_SpawnMissile
    //
    // p_mobj.c lines 888–927.
    public static Mobj pSpawnMissile(Mobj source, Mobj dest, int type) {
        Mobj th;
        int an;
        int dist;

        th = pSpawnMobj(source.x,
                source.y,
                source.z + 4 * 8 * Fixed.FRACUNIT, type);

        if (th.info.seesound != 0) {
            SSound.sStartSound(th, th.info.seesound);
        }

        th.target = source;     // where it came from
        an = doom.render.RMain.rPointToAngle2(source.x, source.y,
                dest.x, dest.y);

        // fuzzy player
        if ((dest.flags & Mobj.MF_SHADOW) != 0) {
            an += (MRandom.pRandom() - MRandom.pRandom()) << 20;
        }

        th.angle = an;
        int aIdx = (an >>> doom.render.Tables.ANGLETOFINESHIFT)
                & doom.render.Tables.FINEMASK;
        th.momx = Fixed.mul(th.info.speed, doom.render.Tables.finecosine(aIdx));
        th.momy = Fixed.mul(th.info.speed, doom.render.Tables.finesine[aIdx]);

        dist = PMaputl.pAproxDistance(dest.x - source.x, dest.y - source.y);
        dist = dist / th.info.speed;

        if (dist < 1) {
            dist = 1;
        }

        th.momz = (dest.z - source.z) / dist;
        pCheckMissileSpawn(th);

        return th;
    }

    //
    // P_SpawnPlayerMissile
    // Tries to aim at a nearby monster.
    //
    // p_mobj.c lines 934–989. The C uses a triple-nested no-target
    // ladder of P_AimLineAttack calls (forward → +1°33' → -1°33');
    // ported verbatim.
    public static void pSpawnPlayerMissile(Mobj source, int type) {
        Mobj th;
        int an;

        int x;
        int y;
        int z;
        int slope;

        // see which target is to be aimed at
        an = source.angle;
        slope = PMap.pAimLineAttack(source, an, 16 * 64 * Fixed.FRACUNIT);

        if (PMap.linetarget == null) {
            an += 1 << 26;
            slope = PMap.pAimLineAttack(source, an, 16 * 64 * Fixed.FRACUNIT);

            if (PMap.linetarget == null) {
                an -= 2 << 26;
                slope = PMap.pAimLineAttack(source, an, 16 * 64 * Fixed.FRACUNIT);
            }

            if (PMap.linetarget == null) {
                an = source.angle;
                slope = 0;
            }
        }

        x = source.x;
        y = source.y;
        z = source.z + 4 * 8 * Fixed.FRACUNIT;

        th = pSpawnMobj(x, y, z, type);

        if (th.info.seesound != 0) {
            SSound.sStartSound(th, th.info.seesound);
        }

        th.target = source;
        th.angle = an;
        int aIdx = (an >>> doom.render.Tables.ANGLETOFINESHIFT)
                & doom.render.Tables.FINEMASK;
        th.momx = Fixed.mul(th.info.speed,
                doom.render.Tables.finecosine(aIdx));
        th.momy = Fixed.mul(th.info.speed,
                doom.render.Tables.finesine[aIdx]);
        th.momz = Fixed.mul(th.info.speed, slope);

        pCheckMissileSpawn(th);
    }

    // Suppress: RMain import needs at least one reference even if
    // future P_NightmareRespawn would be the natural caller. Until
    // it lands, RMain.rPointInSubsector is reached via PMap calls.
    @SuppressWarnings("unused")
    private static final Class<?> _keepRMain = RMain.class;
}
