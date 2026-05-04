// Port of linuxdoom-1.10/p_map.c — partial.
//
// Movement-and-collision core: P_CheckPosition + P_TryMove + the two
// PIT_* iterator callbacks they drive (PIT_CheckLine + PIT_CheckThing
// — the latter now full with skull-fly slam, missile damage, and
// special-pickup branches all wired through PInter).
//
// All previously-deferred sub-functions are now in: PIT_StompThing /
// P_TeleportMove (telefrag), the four combat helpers, and the real
// P_ChangeSector / PIT_ChangeSector (the deviation-21 stub still
// exists as `changeSectorOverride` for tests that drive plane-mover
// crush paths without populating a full world).
//
// The C `tmthing`, `tmflags`, `tmx`, `tmy`, `tmbbox[4]`, `tmfloorz`,
// `tmceilingz`, `tmdropoffz`, `floatok`, `ceilingline`, `spechit[8]`,
// `numspechit` globals all live as `public static` fields here. The
// playsim only ever has one P_CheckPosition / P_TryMove call in
// flight, so the global-state pattern carries over verbatim.
package doom.play;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.RMain;
import doom.render.RSky;
import doom.render.Sector;
import doom.render.Slopetype;
import doom.render.Subsector;
import doom.render.Tables;
import doom.sound.SSound;
import doom.sound.Sounds;
import doom.system.Doomstat;
import doom.system.ISystem;

public final class PMap {

    // p_map.c lines 46–70 — movement-clip globals.
    public static int[] tmbbox = new int[4];
    public static Mobj tmthing;
    public static int tmflags;
    public static int tmx; // fixed_t
    public static int tmy; // fixed_t

    // If `floatok` true, move would be ok if within
    // `tmfloorz - tmceilingz`.
    public static boolean floatok;

    public static int tmfloorz;   // fixed_t
    public static int tmceilingz; // fixed_t
    public static int tmdropoffz; // fixed_t

    // keep track of the line that lowers the ceiling, so missiles
    // don't explode against sky hack walls.
    public static Line ceilingline;

    // p_map.c line 67 — keep track of special lines as they are hit,
    // but don't process them until the move is proven valid.
    public static final int MAXSPECIALCROSS = 8;
    public static final Line[] spechit = new Line[MAXSPECIALCROSS];
    public static int numspechit;

    // p_map.c lines 1250–1251 — PIT_ChangeSector / P_ChangeSector
    // shared scratch.
    public static boolean crushchange;
    public static boolean nofit;

    // Test-only override: when true, pChangeSector returns this value
    // instead of running the real body. Lets unit tests for the plane
    // movers (PFloor, PCeilng, PDoors, PPlats) drive the crush /
    // pastdest path without spinning up a fully populated world.
    // Production callers leave it false (the default), and the real
    // P_ChangeSector body runs.
    public static boolean changeSectorOverride;
    public static boolean changeSectorResult = false;

    private PMap() {
    }

    //
    // PIT_CheckLine
    // Adjusts tmfloorz and tmceilingz as lines are contacted.
    //
    // p_map.c lines 189–247.
    public static boolean pitCheckLine(Line ld) {
        if (tmbbox[MBbox.BOXRIGHT] <= ld.bbox[MBbox.BOXLEFT]
                || tmbbox[MBbox.BOXLEFT] >= ld.bbox[MBbox.BOXRIGHT]
                || tmbbox[MBbox.BOXTOP] <= ld.bbox[MBbox.BOXBOTTOM]
                || tmbbox[MBbox.BOXBOTTOM] >= ld.bbox[MBbox.BOXTOP]) {
            return true;
        }

        if (PMaputl.pBoxOnLineSide(tmbbox, ld) != -1) {
            return true;
        }

        // A line has been hit.
        // The moving thing's destination position will cross the
        // given line. If this should not be allowed, return false.
        // If the line is special, keep track of it to process later
        // if the move is proven ok.
        // NOTE: specials are NOT sorted by order, so two special
        // lines that are only 8 pixels apart could be crossed in
        // either order.

        if (ld.backsector == null) {
            return false; // one sided line
        }

        if ((tmthing.flags & Mobj.MF_MISSILE) == 0) {
            if ((ld.flags & Line.ML_BLOCKING) != 0) {
                return false; // explicitly blocking everything
            }

            if (tmthing.player == null
                    && (ld.flags & Line.ML_BLOCKMONSTERS) != 0) {
                return false; // block monsters only
            }
        }

        // set openrange, opentop, openbottom
        PMaputl.pLineOpening(ld);

        // adjust floor / ceiling heights
        if (PMaputl.opentop < tmceilingz) {
            tmceilingz = PMaputl.opentop;
            ceilingline = ld;
        }

        if (PMaputl.openbottom > tmfloorz) {
            tmfloorz = PMaputl.openbottom;
        }

        if (PMaputl.lowfloor < tmdropoffz) {
            tmdropoffz = PMaputl.lowfloor;
        }

        // if contacted a special line, add it to the list
        if (ld.special != 0) {
            spechit[numspechit] = ld;
            numspechit++;
        }

        return true;
    }

    //
    // PIT_CheckThing
    //
    // p_map.c lines 252–343. Full port now that p_inter has landed:
    // skull-fly slam damage, missile fly-over / fly-under skip + same-
    // species filter + damage dispatch, and special-pickup with the
    // MF_PICKUP gate.
    public static boolean pitCheckThing(Mobj thing) {
        int blockdist;
        boolean solid;
        int damage;

        if ((thing.flags & (Mobj.MF_SOLID | Mobj.MF_SPECIAL | Mobj.MF_SHOOTABLE)) == 0) {
            return true;
        }

        blockdist = thing.radius + tmthing.radius;

        if (Math.abs(thing.x - tmx) >= blockdist
                || Math.abs(thing.y - tmy) >= blockdist) {
            // didn't hit it
            return true;
        }

        // don't clip against self
        if (thing == tmthing) {
            return true;
        }

        // check for skulls slamming into things
        if ((tmthing.flags & Mobj.MF_SKULLFLY) != 0) {
            damage = ((MRandom.pRandom() % 8) + 1) * tmthing.info.damage;

            PInter.pDamageMobj(thing, tmthing, tmthing, damage);

            tmthing.flags &= ~Mobj.MF_SKULLFLY;
            tmthing.momx = tmthing.momy = tmthing.momz = 0;

            PMobj.pSetMobjState(tmthing, tmthing.info.spawnstate);

            return false;       // stop moving
        }


        // missiles can hit other things
        if ((tmthing.flags & Mobj.MF_MISSILE) != 0) {
            // see if it went over / under
            if (tmthing.z > thing.z + thing.height) {
                return true;    // overhead
            }
            if (tmthing.z + tmthing.height < thing.z) {
                return true;    // underneath
            }

            if (tmthing.target != null && (
                    tmthing.target.type == thing.type
                    || (tmthing.target.type == Info.MT_KNIGHT && thing.type == Info.MT_BRUISER)
                    || (tmthing.target.type == Info.MT_BRUISER && thing.type == Info.MT_KNIGHT))) {
                // Don't hit same species as originator.
                if (thing == tmthing.target) {
                    return true;
                }

                if (thing.type != Info.MT_PLAYER) {
                    // Explode, but do no damage.
                    // Let players missile other players.
                    return false;
                }
            }

            if ((thing.flags & Mobj.MF_SHOOTABLE) == 0) {
                // didn't do any damage
                return (thing.flags & Mobj.MF_SOLID) == 0;
            }

            // damage / explode
            damage = ((MRandom.pRandom() % 8) + 1) * tmthing.info.damage;
            PInter.pDamageMobj(thing, tmthing, tmthing.target, damage);

            // don't traverse any more
            return false;
        }

        // check for special pickup
        if ((thing.flags & Mobj.MF_SPECIAL) != 0) {
            solid = (thing.flags & Mobj.MF_SOLID) != 0;
            if ((tmflags & Mobj.MF_PICKUP) != 0) {
                // can remove thing
                PInter.pTouchSpecialThing(thing, tmthing);
            }
            return !solid;
        }

        return (thing.flags & Mobj.MF_SOLID) == 0;
    }

    //
    // P_CheckPosition
    // This is purely informative, nothing is modified (except
    // things picked up — which won't happen until the deferred
    // PIT_CheckThing branches port).
    //
    // p_map.c lines 374–442.
    public static boolean pCheckPosition(Mobj thing, int x, int y) {
        int xl;
        int xh;
        int yl;
        int yh;
        int bx;
        int by;
        Subsector newsubsec;

        tmthing = thing;
        tmflags = thing.flags;

        tmx = x;
        tmy = y;

        tmbbox[MBbox.BOXTOP] = y + tmthing.radius;
        tmbbox[MBbox.BOXBOTTOM] = y - tmthing.radius;
        tmbbox[MBbox.BOXRIGHT] = x + tmthing.radius;
        tmbbox[MBbox.BOXLEFT] = x - tmthing.radius;

        newsubsec = RMain.rPointInSubsector(x, y);
        ceilingline = null;

        // The base floor / ceiling is from the subsector that
        // contains the point. Any contacted lines the step closer
        // together will adjust them.
        tmfloorz = tmdropoffz = newsubsec.sector.floorheight;
        tmceilingz = newsubsec.sector.ceilingheight;

        RMain.validcount++;
        numspechit = 0;

        if ((tmflags & Mobj.MF_NOCLIP) != 0) {
            return true;
        }

        // p_local.h: MAXRADIUS = 32*FRACUNIT, MAPBLOCKSHIFT = FRACBITS+7.
        final int MAXRADIUS = 32 * Fixed.FRACUNIT;
        final int MAPBLOCKSHIFT = Fixed.FRACBITS + 7;

        // Check things first, possibly picking things up. The
        // bounding box is extended by MAXRADIUS because mobj_ts are
        // grouped into mapblocks based on their origin point, and
        // can overlap into adjacent blocks by up to MAXRADIUS units.
        xl = (tmbbox[MBbox.BOXLEFT] - PSetup.bmaporgx - MAXRADIUS) >> MAPBLOCKSHIFT;
        xh = (tmbbox[MBbox.BOXRIGHT] - PSetup.bmaporgx + MAXRADIUS) >> MAPBLOCKSHIFT;
        yl = (tmbbox[MBbox.BOXBOTTOM] - PSetup.bmaporgy - MAXRADIUS) >> MAPBLOCKSHIFT;
        yh = (tmbbox[MBbox.BOXTOP] - PSetup.bmaporgy + MAXRADIUS) >> MAPBLOCKSHIFT;

        for (bx = xl; bx <= xh; bx++) {
            for (by = yl; by <= yh; by++) {
                if (!PMaputl.pBlockThingsIterator(bx, by, PMap::pitCheckThing)) {
                    return false;
                }
            }
        }

        // check lines
        xl = (tmbbox[MBbox.BOXLEFT] - PSetup.bmaporgx) >> MAPBLOCKSHIFT;
        xh = (tmbbox[MBbox.BOXRIGHT] - PSetup.bmaporgx) >> MAPBLOCKSHIFT;
        yl = (tmbbox[MBbox.BOXBOTTOM] - PSetup.bmaporgy) >> MAPBLOCKSHIFT;
        yh = (tmbbox[MBbox.BOXTOP] - PSetup.bmaporgy) >> MAPBLOCKSHIFT;

        for (bx = xl; bx <= xh; bx++) {
            for (by = yl; by <= yh; by++) {
                if (!PMaputl.pBlockLinesIterator(bx, by, PMap::pitCheckLine)) {
                    return false;
                }
            }
        }

        return true;
    }

    //
    // P_TryMove
    // Attempt to move to a new position, crossing special lines
    // unless MF_TELEPORT is set.
    //
    // p_map.c lines 451–517.
    public static boolean pTryMove(Mobj thing, int x, int y) {
        int oldx;
        int oldy;
        int side;
        int oldside;
        Line ld;

        floatok = false;
        if (!pCheckPosition(thing, x, y)) {
            return false; // solid wall or thing
        }

        if ((thing.flags & Mobj.MF_NOCLIP) == 0) {
            if (tmceilingz - tmfloorz < thing.height) {
                return false; // doesn't fit
            }

            floatok = true;

            if ((thing.flags & Mobj.MF_TELEPORT) == 0
                    && tmceilingz - thing.z < thing.height) {
                return false; // mobj must lower itself to fit
            }

            if ((thing.flags & Mobj.MF_TELEPORT) == 0
                    && tmfloorz - thing.z > 24 * Fixed.FRACUNIT) {
                return false; // too big a step up
            }

            if ((thing.flags & (Mobj.MF_DROPOFF | Mobj.MF_FLOAT)) == 0
                    && tmfloorz - tmdropoffz > 24 * Fixed.FRACUNIT) {
                return false; // don't stand over a dropoff
            }
        }

        // the move is ok, so link the thing into its new position
        PMaputl.pUnsetThingPosition(thing);

        oldx = thing.x;
        oldy = thing.y;
        thing.floorz = tmfloorz;
        thing.ceilingz = tmceilingz;
        thing.x = x;
        thing.y = y;

        PMaputl.pSetThingPosition(thing);

        // if any special lines were hit, do the effect
        if ((thing.flags & (Mobj.MF_TELEPORT | Mobj.MF_NOCLIP)) == 0) {
            while (numspechit > 0) {
                numspechit--;
                // see if the line was crossed
                ld = spechit[numspechit];
                side = PMaputl.pPointOnLineSide(thing.x, thing.y, ld);
                oldside = PMaputl.pPointOnLineSide(oldx, oldy, ld);
                if (side != oldside) {
                    if (ld.special != 0) {
                        PSpec.pCrossSpecialLine(lineIndex(ld), oldside, thing);
                    }
                }
            }
        }

        return true;
    }

    // The C uses pointer arithmetic `ld - lines` to recover the line
    // index. PSetup.lines is a typed array so we walk it. Linear scan
    // is acceptable (numspechit ≤ 8 per move, numlines a few hundred).
    private static int lineIndex(Line ld) {
        for (int i = 0; i < PSetup.numlines; i++) {
            if (PSetup.lines[i] == ld) {
                return i;
            }
        }
        return -1;
    }

    //
    // P_ThingHeightClip
    // Takes a valid thing and adjusts thing.floorz, thing.ceilingz,
    // and possibly thing.z. Called for all nearby monsters whenever
    // a sector changes height. If the thing doesn't fit, z is set to
    // the lowest value and false is returned.
    //
    // p_map.c lines 530–558.
    public static boolean pThingHeightClip(Mobj thing) {
        boolean onfloor;

        onfloor = (thing.z == thing.floorz);

        pCheckPosition(thing, thing.x, thing.y);
        // what about stranding a monster partially off an edge?

        thing.floorz = tmfloorz;
        thing.ceilingz = tmceilingz;

        if (onfloor) {
            // walking monsters rise and fall with the floor
            thing.z = thing.floorz;
        } else {
            // don't adjust a floating monster unless forced to
            if (thing.z + thing.height > thing.ceilingz) {
                thing.z = thing.ceilingz - thing.height;
            }
        }

        if (thing.ceilingz - thing.floorz < thing.height) {
            return false;
        }

        return true;
    }

    //
    // SLIDE MOVE
    // Allows the player to slide along any angled walls.
    //
    // p_map.c lines 566–575.
    public static int bestslidefrac;
    public static int secondslidefrac;

    public static Line bestslideline;
    public static Line secondslideline;

    public static Mobj slidemo;

    public static int tmxmove; // fixed_t
    public static int tmymove; // fixed_t

    //
    // P_HitSlideLine
    // Adjusts the xmove / ymove so that the next move will slide
    // along the wall.
    //
    // p_map.c lines 584–630.
    public static void pHitSlideLine(Line ld) {
        int side;

        int lineangle;  // angle_t
        int moveangle;  // angle_t
        int deltaangle; // angle_t

        int movelen;
        int newlen;

        if (ld.slopetype == Slopetype.ST_HORIZONTAL) {
            tmymove = 0;
            return;
        }

        if (ld.slopetype == Slopetype.ST_VERTICAL) {
            tmxmove = 0;
            return;
        }

        side = PMaputl.pPointOnLineSide(slidemo.x, slidemo.y, ld);

        lineangle = RMain.rPointToAngle2(0, 0, ld.dx, ld.dy);

        if (side == 1) {
            lineangle += Tables.ANG180;
        }

        moveangle = RMain.rPointToAngle2(0, 0, tmxmove, tmymove);
        deltaangle = moveangle - lineangle;

        // The C uses unsigned compare. In Java, ANG180 (0x80000000) is
        // negative as signed; an unsigned > ANG180 maps to "the bit
        // pattern is at or past 0x80000000". `compareUnsigned > 0`
        // captures that.
        if (Integer.compareUnsigned(deltaangle, Tables.ANG180) > 0) {
            deltaangle += Tables.ANG180;
        }
        // I_Error("SlideLine: ang>ANG180");

        // angle_t indices into the fine sin/cos tables. The shift
        // is unsigned (>>> in Java) — angle_t is unsigned in C.
        int la = lineangle >>> Tables.ANGLETOFINESHIFT;
        int da = deltaangle >>> Tables.ANGLETOFINESHIFT;

        movelen = PMaputl.pAproxDistance(tmxmove, tmymove);
        newlen = Fixed.mul(movelen, Tables.finecosine(da));

        tmxmove = Fixed.mul(newlen, Tables.finecosine(la));
        tmymove = Fixed.mul(newlen, Tables.finesine[la]);
    }

    //
    // PTR_SlideTraverse
    //
    // p_map.c lines 636–682. The C uses `goto isblocking` to share a
    // tail; Java threads it with a boolean.
    public static boolean ptrSlideTraverse(Intercept in) {
        Line li;

        if (!in.isaline) {
            ISystem.iError("PTR_SlideTraverse: not a line?");
        }

        li = in.line;

        boolean isblocking = false;

        if ((li.flags & Line.ML_TWOSIDED) == 0) {
            if (PMaputl.pPointOnLineSide(slidemo.x, slidemo.y, li) != 0) {
                // don't hit the back side
                return true;
            }
            isblocking = true;
        }

        if (!isblocking) {
            // set openrange, opentop, openbottom
            PMaputl.pLineOpening(li);

            if (PMaputl.openrange < slidemo.height) {
                isblocking = true;          // doesn't fit
            } else if (PMaputl.opentop - slidemo.z < slidemo.height) {
                isblocking = true;          // mobj is too high
            } else if (PMaputl.openbottom - slidemo.z > 24 * Fixed.FRACUNIT) {
                isblocking = true;          // too big a step up
            } else {
                // this line doesn't block movement
                return true;
            }
        }

        // the line does block movement, see if it is closer than the
        // best so far.
        if (in.frac < bestslidefrac) {
            secondslidefrac = bestslidefrac;
            secondslideline = bestslideline;
            bestslidefrac = in.frac;
            bestslideline = li;
        }

        return false; // stop
    }

    //
    // P_SlideMove
    // The momx / momy move is bad, so try to slide along a wall.
    // Find the first line hit, move flush to it, and slide along it.
    //
    // This is a kludgy mess.
    //
    // p_map.c lines 695–788. The C `goto retry` and `goto stairstep`
    // become a labeled retry-loop + a `stairstep` helper to share the
    // jump target.
    public static void pSlideMove(Mobj mo) {
        int leadx;
        int leady;
        int trailx;
        int traily;
        int newx;
        int newy;
        int hitcount;

        slidemo = mo;
        hitcount = 0;

        retry: while (true) {
            if (++hitcount == 3) {
                // don't loop forever
                slideStairstep(mo);
                return;
            }

            // trace along the three leading corners
            if (mo.momx > 0) {
                leadx = mo.x + mo.radius;
                trailx = mo.x - mo.radius;
            } else {
                leadx = mo.x - mo.radius;
                trailx = mo.x + mo.radius;
            }

            if (mo.momy > 0) {
                leady = mo.y + mo.radius;
                traily = mo.y - mo.radius;
            } else {
                leady = mo.y - mo.radius;
                traily = mo.y + mo.radius;
            }

            bestslidefrac = Fixed.FRACUNIT + 1;

            PMaputl.pPathTraverse(leadx, leady, leadx + mo.momx, leady + mo.momy,
                    PMaputl.PT_ADDLINES, PMap::ptrSlideTraverse);
            PMaputl.pPathTraverse(trailx, leady, trailx + mo.momx, leady + mo.momy,
                    PMaputl.PT_ADDLINES, PMap::ptrSlideTraverse);
            PMaputl.pPathTraverse(leadx, traily, leadx + mo.momx, traily + mo.momy,
                    PMaputl.PT_ADDLINES, PMap::ptrSlideTraverse);

            // move up to the wall
            if (bestslidefrac == Fixed.FRACUNIT + 1) {
                // the move must have hit the middle, so stairstep
                slideStairstep(mo);
                return;
            }

            // fudge a bit to make sure it doesn't hit
            bestslidefrac -= 0x800;
            if (bestslidefrac > 0) {
                newx = Fixed.mul(mo.momx, bestslidefrac);
                newy = Fixed.mul(mo.momy, bestslidefrac);

                if (!pTryMove(mo, mo.x + newx, mo.y + newy)) {
                    slideStairstep(mo);
                    return;
                }
            }

            // Now continue along the wall.
            // First calculate remainder.
            bestslidefrac = Fixed.FRACUNIT - (bestslidefrac + 0x800);

            if (bestslidefrac > Fixed.FRACUNIT) {
                bestslidefrac = Fixed.FRACUNIT;
            }

            if (bestslidefrac <= 0) {
                return;
            }

            tmxmove = Fixed.mul(mo.momx, bestslidefrac);
            tmymove = Fixed.mul(mo.momy, bestslidefrac);

            pHitSlideLine(bestslideline); // clip the moves

            mo.momx = tmxmove;
            mo.momy = tmymove;

            if (!pTryMove(mo, mo.x + tmxmove, mo.y + tmymove)) {
                continue retry;
            }

            return;
        }
    }

    // The `stairstep:` label in P_SlideMove is jumped to from three
    // places. The body it guards (try momy-only then momx-only) is
    // factored here.
    private static void slideStairstep(Mobj mo) {
        if (!pTryMove(mo, mo.x, mo.y + mo.momy)) {
            pTryMove(mo, mo.x + mo.momx, mo.y);
        }
    }

    //
    // USE LINES
    //
    // p_local.h line 56 — player "use" reach.
    public static final int USERANGE = 64 * Fixed.FRACUNIT;

    // p_map.c line 1093 — set by P_UseLines so PTR_UseTraverse can
    // forward to P_UseSpecialLine with the right thing reference.
    public static Mobj usething;

    //
    // PTR_UseTraverse
    //
    // p_map.c lines 1095–1123.
    public static boolean ptrUseTraverse(Intercept in) {
        int side;

        if (in.line.special == 0) {
            PMaputl.pLineOpening(in.line);
            if (PMaputl.openrange <= 0) {
                SSound.sStartSound(usething, Sounds.sfx_noway);

                // can't use through a wall
                return false;
            }
            // not a special line, but keep checking
            return true;
        }

        side = 0;
        if (PMaputl.pPointOnLineSide(usething.x, usething.y, in.line) == 1) {
            side = 1;
        }

        // return false;        // don't use back side

        PSwitch.pUseSpecialLine(usething, in.line, side);

        // can't use for than one special line in a row
        return false;
    }

    //
    // P_UseLines
    // Looks for special lines in front of the player to activate.
    //
    // p_map.c lines 1130–1148.
    public static void pUseLines(Player player) {
        int angle;
        int x1;
        int y1;
        int x2;
        int y2;

        usething = player.mo;

        angle = player.mo.angle >>> Tables.ANGLETOFINESHIFT;

        x1 = player.mo.x;
        y1 = player.mo.y;
        x2 = x1 + (USERANGE >> Fixed.FRACBITS) * Tables.finecosine(angle);
        y2 = y1 + (USERANGE >> Fixed.FRACBITS) * Tables.finesine[angle];

        PMaputl.pPathTraverse(x1, y1, x2, y2,
                PMaputl.PT_ADDLINES, PMap::ptrUseTraverse);
    }

    //
    // TELEPORT MOVE
    //

    //
    // PIT_StompThing
    //
    // p_map.c lines 81–108. Telefrag damage = 10000 (always lethal).
    // Monsters can only stomp on the boss level (gamemap == 30) —
    // this gates the Romero-head boss-spitter targets.
    public static boolean pitStompThing(Mobj thing) {
        int blockdist;

        if ((thing.flags & Mobj.MF_SHOOTABLE) == 0) {
            return true;
        }

        blockdist = thing.radius + tmthing.radius;

        if (Math.abs(thing.x - tmx) >= blockdist
                || Math.abs(thing.y - tmy) >= blockdist) {
            // didn't hit it
            return true;
        }

        // don't clip against self
        if (thing == tmthing) {
            return true;
        }

        // monsters don't stomp things except on boss level
        if (tmthing.player == null && Doomstat.gamemap != 30) {
            return false;
        }

        PInter.pDamageMobj(thing, tmthing, tmthing, 10000);

        return true;
    }

    //
    // P_TeleportMove
    //
    // p_map.c lines 114–177. Teleport drops the mobj at (x, y),
    // telefragging anything inside the radius bbox. Returns true on
    // success; false only when monsters try to teleport into a non-
    // boss-level destination occupied by something shootable.
    public static boolean pTeleportMove(Mobj thing, int x, int y) {
        int xl;
        int xh;
        int yl;
        int yh;
        int bx;
        int by;

        Subsector newsubsec;

        // kill anything occupying the position
        tmthing = thing;
        tmflags = thing.flags;

        tmx = x;
        tmy = y;

        tmbbox[MBbox.BOXTOP] = y + tmthing.radius;
        tmbbox[MBbox.BOXBOTTOM] = y - tmthing.radius;
        tmbbox[MBbox.BOXRIGHT] = x + tmthing.radius;
        tmbbox[MBbox.BOXLEFT] = x - tmthing.radius;

        newsubsec = RMain.rPointInSubsector(x, y);
        ceilingline = null;

        // The base floor/ceiling is from the subsector
        // that contains the point.
        // Any contacted lines the step closer together
        // will adjust them.
        tmfloorz = tmdropoffz = newsubsec.sector.floorheight;
        tmceilingz = newsubsec.sector.ceilingheight;

        RMain.validcount++;
        numspechit = 0;

        // p_local.h: MAXRADIUS = 32*FRACUNIT, MAPBLOCKSHIFT = FRACBITS+7.
        final int MAXRADIUS = 32 * Fixed.FRACUNIT;
        final int MAPBLOCKSHIFT = Fixed.FRACBITS + 7;

        // stomp on any things contacted
        xl = (tmbbox[MBbox.BOXLEFT] - PSetup.bmaporgx - MAXRADIUS) >> MAPBLOCKSHIFT;
        xh = (tmbbox[MBbox.BOXRIGHT] - PSetup.bmaporgx + MAXRADIUS) >> MAPBLOCKSHIFT;
        yl = (tmbbox[MBbox.BOXBOTTOM] - PSetup.bmaporgy - MAXRADIUS) >> MAPBLOCKSHIFT;
        yh = (tmbbox[MBbox.BOXTOP] - PSetup.bmaporgy + MAXRADIUS) >> MAPBLOCKSHIFT;

        for (bx = xl; bx <= xh; bx++) {
            for (by = yl; by <= yh; by++) {
                if (!PMaputl.pBlockThingsIterator(bx, by, PMap::pitStompThing)) {
                    return false;
                }
            }
        }

        // the move is ok,
        // so link the thing into its new position
        PMaputl.pUnsetThingPosition(thing);

        thing.floorz = tmfloorz;
        thing.ceilingz = tmceilingz;
        thing.x = x;
        thing.y = y;

        PMaputl.pSetThingPosition(thing);

        return true;
    }

    //
    // SHOOT TRACE
    //
    // p_map.c lines 793–1086. The hitscan combat helpers all share a
    // single trace + a handful of globals: shootthing (the firer),
    // shootz (eye z), aimslope (vertical aim), la_damage (damage to
    // apply on hit), linetarget (the mobj actually hit by AimTraverse),
    // and PMobj.attackrange (used by P_SpawnPuff for the punch-spark
    // special case).
    //
    // topslope / bottomslope are the per-trace vertical aim window;
    // shared with p_sight.c (the C `extern fixed_t topslope`) — both
    // modules live on PSight.
    public static Mobj shootthing;
    public static int shootz;       // fixed_t — eye z of the firer
    public static int la_damage;
    public static Mobj linetarget;  // last shootable mobj hit by AimTraverse
    public static int aimslope;     // fixed_t

    //
    // PTR_AimTraverse
    // Sets linetarget and aimslope when a target is aimed at.
    //
    // p_map.c lines 815–893.
    public static boolean ptrAimTraverse(Intercept in) {
        Line li;
        Mobj th;
        int slope;
        int thingtopslope;
        int thingbottomslope;
        int dist;

        if (in.isaline) {
            li = in.line;

            if ((li.flags & Line.ML_TWOSIDED) == 0) {
                return false;       // stop
            }

            // Crosses a two sided line.
            // A two sided line will restrict
            // the possible target ranges.
            PMaputl.pLineOpening(li);

            if (PMaputl.openbottom >= PMaputl.opentop) {
                return false;       // stop
            }

            dist = Fixed.mul(PMobj.attackrange, in.frac);

            if (li.frontsector.floorheight != li.backsector.floorheight) {
                slope = Fixed.div(PMaputl.openbottom - shootz, dist);
                if (slope > PSight.bottomslope) {
                    PSight.bottomslope = slope;
                }
            }

            if (li.frontsector.ceilingheight != li.backsector.ceilingheight) {
                slope = Fixed.div(PMaputl.opentop - shootz, dist);
                if (slope < PSight.topslope) {
                    PSight.topslope = slope;
                }
            }

            if (PSight.topslope <= PSight.bottomslope) {
                return false;       // stop
            }

            return true;            // shot continues
        }

        // shoot a thing
        th = in.thing;
        if (th == shootthing) {
            return true;            // can't shoot self
        }

        if ((th.flags & Mobj.MF_SHOOTABLE) == 0) {
            return true;            // corpse or something
        }

        // check angles to see if the thing can be aimed at
        dist = Fixed.mul(PMobj.attackrange, in.frac);
        thingtopslope = Fixed.div(th.z + th.height - shootz, dist);

        if (thingtopslope < PSight.bottomslope) {
            return true;            // shot over the thing
        }

        thingbottomslope = Fixed.div(th.z - shootz, dist);

        if (thingbottomslope > PSight.topslope) {
            return true;            // shot under the thing
        }

        // this thing can be hit!
        if (thingtopslope > PSight.topslope) {
            thingtopslope = PSight.topslope;
        }

        if (thingbottomslope < PSight.bottomslope) {
            thingbottomslope = PSight.bottomslope;
        }

        aimslope = (thingtopslope + thingbottomslope) / 2;
        linetarget = th;

        return false;               // don't go any farther
    }


    //
    // PTR_ShootTraverse
    //
    // p_map.c lines 899–1016. The C uses a `goto hitline:` to share
    // the line-impact branch across two paths through the function.
    // Java replaces that with an explicit boolean flag (CLAUDE.md §2:
    // labeled-break / boolean toggle, never restructure).
    public static boolean ptrShootTraverse(Intercept in) {
        int x;
        int y;
        int z;
        int frac;

        Line li;

        Mobj th;

        int slope;
        int dist;
        int thingtopslope;
        int thingbottomslope;

        if (in.isaline) {
            li = in.line;

            if (li.special != 0) {
                PSpec.pShootSpecialLine(shootthing, li);
            }

            boolean hitline;
            if ((li.flags & Line.ML_TWOSIDED) == 0) {
                hitline = true;
            } else {
                // crosses a two sided line
                PMaputl.pLineOpening(li);

                dist = Fixed.mul(PMobj.attackrange, in.frac);
                hitline = false;

                if (li.frontsector.floorheight != li.backsector.floorheight) {
                    slope = Fixed.div(PMaputl.openbottom - shootz, dist);
                    if (slope > aimslope) {
                        hitline = true;
                    }
                }

                if (!hitline
                        && li.frontsector.ceilingheight
                            != li.backsector.ceilingheight) {
                    slope = Fixed.div(PMaputl.opentop - shootz, dist);
                    if (slope < aimslope) {
                        hitline = true;
                    }
                }

                // shot continues
                if (!hitline) {
                    return true;
                }
            }

            // hit line
            // position a bit closer
            frac = in.frac - Fixed.div(4 * Fixed.FRACUNIT, PMobj.attackrange);
            x = PMaputl.trace.x + Fixed.mul(PMaputl.trace.dx, frac);
            y = PMaputl.trace.y + Fixed.mul(PMaputl.trace.dy, frac);
            z = shootz + Fixed.mul(aimslope,
                    Fixed.mul(frac, PMobj.attackrange));

            if (li.frontsector.ceilingpic == RSky.skyflatnum) {
                // don't shoot the sky!
                if (z > li.frontsector.ceilingheight) {
                    return false;
                }

                // it's a sky hack wall
                if (li.backsector != null
                        && li.backsector.ceilingpic == RSky.skyflatnum) {
                    return false;
                }
            }

            // Spawn bullet puffs.
            PMobj.pSpawnPuff(x, y, z);

            // don't go any farther
            return false;
        }

        // shoot a thing
        th = in.thing;
        if (th == shootthing) {
            return true;            // can't shoot self
        }

        if ((th.flags & Mobj.MF_SHOOTABLE) == 0) {
            return true;            // corpse or something
        }

        // check angles to see if the thing can be aimed at
        dist = Fixed.mul(PMobj.attackrange, in.frac);
        thingtopslope = Fixed.div(th.z + th.height - shootz, dist);

        if (thingtopslope < aimslope) {
            return true;            // shot over the thing
        }

        thingbottomslope = Fixed.div(th.z - shootz, dist);

        if (thingbottomslope > aimslope) {
            return true;            // shot under the thing
        }


        // hit thing
        // position a bit closer
        frac = in.frac - Fixed.div(10 * Fixed.FRACUNIT, PMobj.attackrange);

        x = PMaputl.trace.x + Fixed.mul(PMaputl.trace.dx, frac);
        y = PMaputl.trace.y + Fixed.mul(PMaputl.trace.dy, frac);
        z = shootz + Fixed.mul(aimslope, Fixed.mul(frac, PMobj.attackrange));

        // Spawn bullet puffs or blod spots,
        // depending on target type.
        if ((in.thing.flags & Mobj.MF_NOBLOOD) != 0) {
            PMobj.pSpawnPuff(x, y, z);
        } else {
            PMobj.pSpawnBlood(x, y, z, la_damage);
        }

        if (la_damage != 0) {
            PInter.pDamageMobj(th, shootthing, shootthing, la_damage);
        }

        // don't go any farther
        return false;
    }


    //
    // P_AimLineAttack
    //
    // p_map.c lines 1023–1054.
    public static int pAimLineAttack(Mobj t1, int angle, int distance) {
        int x2;
        int y2;

        int a = (angle >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;
        shootthing = t1;

        x2 = t1.x + (distance >> Fixed.FRACBITS) * Tables.finecosine(a);
        y2 = t1.y + (distance >> Fixed.FRACBITS) * Tables.finesine[a];
        shootz = t1.z + (t1.height >> 1) + 8 * Fixed.FRACUNIT;

        // can't shoot outside view angles
        PSight.topslope = 100 * Fixed.FRACUNIT / 160;
        PSight.bottomslope = -100 * Fixed.FRACUNIT / 160;

        PMobj.attackrange = distance;
        linetarget = null;

        PMaputl.pPathTraverse(t1.x, t1.y,
                x2, y2,
                PMaputl.PT_ADDLINES | PMaputl.PT_ADDTHINGS,
                PMap::ptrAimTraverse);

        if (linetarget != null) {
            return aimslope;
        }

        return 0;
    }


    //
    // P_LineAttack
    // If damage == 0, it is just a test trace
    // that will leave linetarget set.
    //
    // p_map.c lines 1063–1086.
    public static void pLineAttack(Mobj t1, int angle, int distance,
            int slope, int damage) {
        int x2;
        int y2;

        int a = (angle >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;
        shootthing = t1;
        la_damage = damage;
        x2 = t1.x + (distance >> Fixed.FRACBITS) * Tables.finecosine(a);
        y2 = t1.y + (distance >> Fixed.FRACBITS) * Tables.finesine[a];
        shootz = t1.z + (t1.height >> 1) + 8 * Fixed.FRACUNIT;
        PMobj.attackrange = distance;
        aimslope = slope;

        PMaputl.pPathTraverse(t1.x, t1.y,
                x2, y2,
                PMaputl.PT_ADDLINES | PMaputl.PT_ADDTHINGS,
                PMap::ptrShootTraverse);
    }


    //
    // RADIUS ATTACK
    //
    // p_map.c lines 1154–1233. Splash damage from a bombspot, scaled
    // linearly by Chebyshev (max-axis) distance with a P_CheckSight
    // pre-flight to keep the explosion from leaking through walls.
    public static Mobj bombsource;
    public static Mobj bombspot;
    public static int bombdamage;

    //
    // PIT_RadiusAttack
    // "bombsource" is the creature that caused the explosion at
    // "bombspot".
    //
    // p_map.c lines 1164–1198.
    public static boolean pitRadiusAttack(Mobj thing) {
        int dx;
        int dy;
        int dist;

        if ((thing.flags & Mobj.MF_SHOOTABLE) == 0) {
            return true;
        }

        // Boss spider and cyborg
        // take no damage from concussion.
        if (thing.type == Info.MT_CYBORG
                || thing.type == Info.MT_SPIDER) {
            return true;
        }

        dx = Math.abs(thing.x - bombspot.x);
        dy = Math.abs(thing.y - bombspot.y);

        dist = dx > dy ? dx : dy;
        dist = (dist - thing.radius) >> Fixed.FRACBITS;

        if (dist < 0) {
            dist = 0;
        }

        if (dist >= bombdamage) {
            return true;        // out of range
        }

        if (PSight.pCheckSight(thing, bombspot)) {
            // must be in direct path
            PInter.pDamageMobj(thing, bombspot, bombsource, bombdamage - dist);
        }

        return true;
    }


    //
    // SECTOR HEIGHT CHANGING
    //

    //
    // PIT_ChangeSector
    //
    // p_map.c lines 1257–1313. Per-thing crush handler driven by
    // P_ChangeSector when a sector floor/ceiling moves.
    public static boolean pitChangeSector(Mobj thing) {
        Mobj mo;

        if (pThingHeightClip(thing)) {
            // keep checking
            return true;
        }


        // crunch bodies to giblets
        if (thing.health <= 0) {
            PMobj.pSetMobjState(thing, Info.S_GIBS);

            thing.flags &= ~Mobj.MF_SOLID;
            thing.height = 0;
            thing.radius = 0;

            // keep checking
            return true;
        }

        // crunch dropped items
        if ((thing.flags & Mobj.MF_DROPPED) != 0) {
            PMobj.pRemoveMobj(thing);

            // keep checking
            return true;
        }

        if ((thing.flags & Mobj.MF_SHOOTABLE) == 0) {
            // assume it is bloody gibs or something
            return true;
        }

        nofit = true;

        if (crushchange && (PTick.leveltime & 3) == 0) {
            PInter.pDamageMobj(thing, null, null, 10);

            // spray blood in a random direction
            mo = PMobj.pSpawnMobj(thing.x,
                    thing.y,
                    thing.z + thing.height / 2, Info.MT_BLOOD);

            mo.momx = (MRandom.pRandom() - MRandom.pRandom()) << 12;
            mo.momy = (MRandom.pRandom() - MRandom.pRandom()) << 12;
        }

        // keep checking (crush other things)
        return true;
    }


    //
    // P_ChangeSector
    //
    // p_map.c lines 1320–1338. Re-checks heights for all mobjs in
    // the sector's blockmap bbox; sets `nofit` if any can't fit.
    // Crusher floors / ceilings pass `crunch=true` to make the per-
    // thing handler damage shootables.
    public static boolean pChangeSector(Sector sector, boolean crunch) {
        if (changeSectorOverride) {
            return changeSectorResult;
        }

        int x;
        int y;

        nofit = false;
        crushchange = crunch;

        // re-check heights for all things near the moving sector
        for (x = sector.blockbox[MBbox.BOXLEFT];
                x <= sector.blockbox[MBbox.BOXRIGHT];
                x++) {
            for (y = sector.blockbox[MBbox.BOXBOTTOM];
                    y <= sector.blockbox[MBbox.BOXTOP];
                    y++) {
                PMaputl.pBlockThingsIterator(x, y, PMap::pitChangeSector);
            }
        }


        return nofit;
    }

    //
    // P_RadiusAttack
    // Source is the creature that caused the explosion at spot.
    //
    // p_map.c lines 1206–1233.
    public static void pRadiusAttack(Mobj spot, Mobj source, int damage) {
        int x;
        int y;

        int xl;
        int xh;
        int yl;
        int yh;

        int dist;

        // p_local.h: MAXRADIUS = 32*FRACUNIT, MAPBLOCKSHIFT = FRACBITS+7.
        final int MAXRADIUS = 32 * Fixed.FRACUNIT;
        final int MAPBLOCKSHIFT = Fixed.FRACBITS + 7;

        // C: dist = (damage + MAXRADIUS) << FRACBITS — double-shifted
        // since MAXRADIUS is already in fixed_t. Preserved verbatim
        // per CLAUDE.md §2 (deviation 28). The over-broad iteration
        // bounds don't change correctness — PIT_RadiusAttack filters
        // per-thing.
        dist = (damage + MAXRADIUS) << Fixed.FRACBITS;
        yh = (spot.y + dist - PSetup.bmaporgy) >> MAPBLOCKSHIFT;
        yl = (spot.y - dist - PSetup.bmaporgy) >> MAPBLOCKSHIFT;
        xh = (spot.x + dist - PSetup.bmaporgx) >> MAPBLOCKSHIFT;
        xl = (spot.x - dist - PSetup.bmaporgx) >> MAPBLOCKSHIFT;
        bombspot = spot;
        bombsource = source;
        bombdamage = damage;

        for (y = yl; y <= yh; y++) {
            for (x = xl; x <= xh; x++) {
                PMaputl.pBlockThingsIterator(x, y, PMap::pitRadiusAttack);
            }
        }
    }
}
