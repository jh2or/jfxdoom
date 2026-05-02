// Port of linuxdoom-1.10/p_mobj.c — partial.
//
// Core mobj lifecycle: state machine + spawn/remove + per-tic
// X/Y/Z movement + helper spawners (puff, blood, missile-spawn
// commit). Drives the playsim's per-tic mobj tick from PTick
// once P_Ticker lands.
//
// Deferred (each waits on its named module):
//   - P_NightmareRespawn        → needs Mobj.spawnpoint (mapthing_t)
//   - P_RespawnSpecials         → needs item-respawn queue + spawnpoint
//   - P_SpawnPlayer             → needs player_t expansion + G_PlayerReborn
//   - P_SpawnMapThing           → needs P_SpawnPlayer + deathmatchstarts
//   - P_SpawnMissile            → needs Mobj.target chain + R_PointToAngle2 ✓
//   - P_SpawnPlayerMissile      → needs P_AimLineAttack (combat port)
//
// PIT_CheckThing's missile branch in PMap calls P_ExplodeMissile
// (deferred there); once p_inter ports P_DamageMobj the full
// PIT_CheckThing body lands and PMap can dispatch missile hits
// through here.
package doom.play;

import doom.fixed.Fixed;
import doom.render.RMain;
import doom.render.RSky;
import doom.sound.SSound;
import doom.system.Doomdef;
import doom.system.Doomstat;

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
        }
        // P_NightmareRespawn check is deferred (needs spawnpoint +
        // respawnmonsters wiring through p_setup THINGS load).
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
    // P_RemoveMobj
    //
    // p_mobj.c lines 540–570. The item-respawn queue logic is
    // preserved as a TODO until P_RespawnSpecials lands.
    public static void pRemoveMobj(Mobj mobj) {
        if ((mobj.flags & Mobj.MF_SPECIAL) != 0
                && (mobj.flags & Mobj.MF_DROPPED) == 0
                && mobj.type != Info.MT_INV
                && mobj.type != Info.MT_INS) {
            // TODO(p_mobj rest): item-respawn queue.
            // itemrespawnque[iquehead] = mobj.spawnpoint;
            // itemrespawntime[iquehead] = leveltime;
            // iquehead = (iquehead+1) & (ITEMQUESIZE-1);
            // if (iquehead == iquetail)
            //     iquetail = (iquetail+1) & (ITEMQUESIZE-1);
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

    // Suppress: RMain import needs at least one reference even if
    // future P_NightmareRespawn would be the natural caller. Until
    // it lands, RMain.rPointInSubsector is reached via PMap calls.
    @SuppressWarnings("unused")
    private static final Class<?> _keepRMain = RMain.class;
}
