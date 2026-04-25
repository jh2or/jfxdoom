// Port of linuxdoom-1.10/p_floor.c.
//
// Floor animations: lower-floor, raise-floor, raise-to-texture,
// raise-stairs, donut-raise. Two thinker types are wired up here:
// the generic T_MovePlane (also used by ceilings, plats, doors) and
// the floor-specific T_MoveFloor (one per active floormove_t).
//
// EV_DoFloor and EV_BuildStairs allocate floormove_t records, attach
// them to the sector's specialdata slot, and link them into the
// thinker list. Per-tic, T_MoveFloor calls T_MovePlane to slide the
// floor one step toward `floordestheight`; on `pastdest` the
// thinker removes itself and (for `lowerAndChange` / `donutRaise`)
// rewrites the sector's pic and special.
//
// donutRaise itself is dispatched from p_spec.c (EV_DoDonut). This
// file only handles the per-tic completion path for an existing
// donutRaise floormove.
package doom.play;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.Sector;
import doom.render.Side;
import doom.sound.SSound;
import doom.sound.Sounds;

public final class PFloor {

    // p_spec.h lines 537–570 — floor_e (13 values).
    // Kept as int constants rather than Java enum so the switch +
    // fall-through in EV_DoFloor translates literally from the C
    // (raiseFloorCrush falls through into raiseFloor; lowerAndChange
    // and donutRaise fall through into the default :break: in
    // T_MoveFloor's pastdest branch).
    public static final int lowerFloor             = 0;
    public static final int lowerFloorToLowest     = 1;
    public static final int turboLower             = 2;
    public static final int raiseFloor             = 3;
    public static final int raiseFloorToNearest    = 4;
    public static final int raiseToTexture         = 5;
    public static final int lowerAndChange         = 6;
    public static final int raiseFloor24           = 7;
    public static final int raiseFloor24AndChange  = 8;
    public static final int raiseFloorCrush        = 9;
    public static final int raiseFloorTurbo        = 10;
    public static final int donutRaise             = 11;
    public static final int raiseFloor512          = 12;

    // p_spec.h lines 575–580 — stair_e.
    public static final int build8  = 0;
    public static final int turbo16 = 1;

    // p_spec.h lines 584–596 — floormove_t.
    public static final class FloorMove extends Thinker {
        public int type;            // floor_e
        public boolean crush;
        public Sector sector;
        public int direction;       // 1 = up, -1 = down
        public int newspecial;
        public int texture;         // short
        public int floordestheight; // fixed_t
        public int speed;           // fixed_t
    }

    // p_floor.c lines 48–203 — T_MovePlane.
    // Generic plane mover used by floor and ceiling thinkers.
    // `floorOrCeiling`: 0 = floor, 1 = ceiling.
    // `direction`:     -1 = down,  1 = up.
    // When speed would carry the plane past `dest`, the plane snaps to
    // `dest` and `pastdest` is returned. When an intermediate step
    // trips a mobj (P_ChangeSector returns true), the move is rolled
    // back and `crushed` is returned — unless the caller set
    // `crush = true`, in which case the crush proceeds (the single
    // up-floor path short-circuits before the rollback). Otherwise
    // the plane moves one `speed` step and returns `ok`.
    public static PSpec.Result tMovePlane(Sector sector, int speed, int dest,
                                          boolean crush, int floorOrCeiling, int direction) {
        boolean flag;
        int lastpos;

        switch (floorOrCeiling) {
            case 0:
                // FLOOR
                switch (direction) {
                    case -1:
                        // DOWN
                        if (sector.floorheight - speed < dest) {
                            lastpos = sector.floorheight;
                            sector.floorheight = dest;
                            flag = PMap.pChangeSector(sector, crush);
                            if (flag) {
                                sector.floorheight = lastpos;
                                PMap.pChangeSector(sector, crush);
                                //return crushed;
                            }
                            return PSpec.Result.pastdest;
                        } else {
                            lastpos = sector.floorheight;
                            sector.floorheight -= speed;
                            flag = PMap.pChangeSector(sector, crush);
                            if (flag) {
                                sector.floorheight = lastpos;
                                PMap.pChangeSector(sector, crush);
                                return PSpec.Result.crushed;
                            }
                        }
                        break;

                    case 1:
                        // UP
                        if (sector.floorheight + speed > dest) {
                            lastpos = sector.floorheight;
                            sector.floorheight = dest;
                            flag = PMap.pChangeSector(sector, crush);
                            if (flag) {
                                sector.floorheight = lastpos;
                                PMap.pChangeSector(sector, crush);
                                //return crushed;
                            }
                            return PSpec.Result.pastdest;
                        } else {
                            // COULD GET CRUSHED
                            lastpos = sector.floorheight;
                            sector.floorheight += speed;
                            flag = PMap.pChangeSector(sector, crush);
                            if (flag) {
                                if (crush) {
                                    return PSpec.Result.crushed;
                                }
                                sector.floorheight = lastpos;
                                PMap.pChangeSector(sector, crush);
                                return PSpec.Result.crushed;
                            }
                        }
                        break;
                }
                break;

            case 1:
                // CEILING
                switch (direction) {
                    case -1:
                        // DOWN
                        if (sector.ceilingheight - speed < dest) {
                            lastpos = sector.ceilingheight;
                            sector.ceilingheight = dest;
                            flag = PMap.pChangeSector(sector, crush);

                            if (flag) {
                                sector.ceilingheight = lastpos;
                                PMap.pChangeSector(sector, crush);
                                //return crushed;
                            }
                            return PSpec.Result.pastdest;
                        } else {
                            // COULD GET CRUSHED
                            lastpos = sector.ceilingheight;
                            sector.ceilingheight -= speed;
                            flag = PMap.pChangeSector(sector, crush);

                            if (flag) {
                                if (crush) {
                                    return PSpec.Result.crushed;
                                }
                                sector.ceilingheight = lastpos;
                                PMap.pChangeSector(sector, crush);
                                return PSpec.Result.crushed;
                            }
                        }
                        break;

                    case 1:
                        // UP
                        if (sector.ceilingheight + speed > dest) {
                            lastpos = sector.ceilingheight;
                            sector.ceilingheight = dest;
                            flag = PMap.pChangeSector(sector, crush);
                            if (flag) {
                                sector.ceilingheight = lastpos;
                                PMap.pChangeSector(sector, crush);
                                //return crushed;
                            }
                            return PSpec.Result.pastdest;
                        } else {
                            lastpos = sector.ceilingheight;
                            sector.ceilingheight += speed;
                            flag = PMap.pChangeSector(sector, crush);
                            // UNUSED
                            // #if 0
                            //      if (flag == true) {
                            //          sector.ceilingheight = lastpos;
                            //          P_ChangeSector(sector, crush);
                            //          return crushed;
                            //      }
                            // #endif
                        }
                        break;
                }
                break;
        }
        return PSpec.Result.ok;
    }

    //
    // MOVE A FLOOR TO IT'S DESTINATION (UP OR DOWN)
    //
    // p_floor.c lines 209–254 — T_MoveFloor.
    public static void tMoveFloor(Thinker t) {
        FloorMove floor = (FloorMove) t;
        PSpec.Result res;

        res = tMovePlane(floor.sector, floor.speed,
                floor.floordestheight, floor.crush, 0, floor.direction);

        if ((PTick.leveltime & 7) == 0) {
            SSound.sStartSound(floor.sector.soundorg, Sounds.sfx_stnmov);
        }

        if (res == PSpec.Result.pastdest) {
            floor.sector.specialdata = null;

            if (floor.direction == 1) {
                switch (floor.type) {
                    case donutRaise:
                        floor.sector.special = floor.newspecial;
                        floor.sector.floorpic = floor.texture;
                    default:
                        break;
                }
            } else if (floor.direction == -1) {
                switch (floor.type) {
                    case lowerAndChange:
                        floor.sector.special = floor.newspecial;
                        floor.sector.floorpic = floor.texture;
                    default:
                        break;
                }
            }
            PTick.pRemoveThinker(floor);

            SSound.sStartSound(floor.sector.soundorg, Sounds.sfx_pstop);
        }
    }

    //
    // HANDLE FLOOR TYPES
    //
    // p_floor.c lines 259–444 — EV_DoFloor.
    public static int evDoFloor(Line line, int floortype) {
        int secnum;
        int rtn;
        int i;
        Sector sec;
        FloorMove floor;

        secnum = -1;
        rtn = 0;
        while ((secnum = PSpec.pFindSectorFromLineTag(line, secnum)) >= 0) {
            sec = PSetup.sectors[secnum];

            // ALREADY MOVING?  IF SO, KEEP GOING...
            if (sec.specialdata != null) {
                continue;
            }

            // new floor thinker
            rtn = 1;
            floor = new FloorMove();
            PTick.pAddThinker(floor);
            sec.specialdata = floor;
            floor.function.acp1 = PFloor::tMoveFloor;
            floor.type = floortype;
            floor.crush = false;

            switch (floortype) {
                case lowerFloor:
                    floor.direction = -1;
                    floor.sector = sec;
                    floor.speed = PSpec.FLOORSPEED;
                    floor.floordestheight =
                            PSpec.pFindHighestFloorSurrounding(sec);
                    break;

                case lowerFloorToLowest:
                    floor.direction = -1;
                    floor.sector = sec;
                    floor.speed = PSpec.FLOORSPEED;
                    floor.floordestheight =
                            PSpec.pFindLowestFloorSurrounding(sec);
                    break;

                case turboLower:
                    floor.direction = -1;
                    floor.sector = sec;
                    floor.speed = PSpec.FLOORSPEED * 4;
                    floor.floordestheight =
                            PSpec.pFindHighestFloorSurrounding(sec);
                    if (floor.floordestheight != sec.floorheight) {
                        floor.floordestheight += 8 * Fixed.FRACUNIT;
                    }
                    break;

                case raiseFloorCrush:
                    floor.crush = true;
                    // fall through
                case raiseFloor:
                    floor.direction = 1;
                    floor.sector = sec;
                    floor.speed = PSpec.FLOORSPEED;
                    floor.floordestheight =
                            PSpec.pFindLowestCeilingSurrounding(sec);
                    if (floor.floordestheight > sec.ceilingheight) {
                        floor.floordestheight = sec.ceilingheight;
                    }
                    floor.floordestheight -= (8 * Fixed.FRACUNIT)
                            * (floortype == raiseFloorCrush ? 1 : 0);
                    break;

                case raiseFloorTurbo:
                    floor.direction = 1;
                    floor.sector = sec;
                    floor.speed = PSpec.FLOORSPEED * 4;
                    floor.floordestheight =
                            PSpec.pFindNextHighestFloor(sec, sec.floorheight);
                    break;

                case raiseFloorToNearest:
                    floor.direction = 1;
                    floor.sector = sec;
                    floor.speed = PSpec.FLOORSPEED;
                    floor.floordestheight =
                            PSpec.pFindNextHighestFloor(sec, sec.floorheight);
                    break;

                case raiseFloor24:
                    floor.direction = 1;
                    floor.sector = sec;
                    floor.speed = PSpec.FLOORSPEED;
                    floor.floordestheight = floor.sector.floorheight
                            + 24 * Fixed.FRACUNIT;
                    break;
                case raiseFloor512:
                    floor.direction = 1;
                    floor.sector = sec;
                    floor.speed = PSpec.FLOORSPEED;
                    floor.floordestheight = floor.sector.floorheight
                            + 512 * Fixed.FRACUNIT;
                    break;

                case raiseFloor24AndChange:
                    floor.direction = 1;
                    floor.sector = sec;
                    floor.speed = PSpec.FLOORSPEED;
                    floor.floordestheight = floor.sector.floorheight
                            + 24 * Fixed.FRACUNIT;
                    sec.floorpic = line.frontsector.floorpic;
                    sec.special = line.frontsector.special;
                    break;

                case raiseToTexture: {
                    int minsize = Integer.MAX_VALUE;
                    Side side;

                    floor.direction = 1;
                    floor.sector = sec;
                    floor.speed = PSpec.FLOORSPEED;
                    for (i = 0; i < sec.linecount; i++) {
                        if (PSpec.twoSided(secnum, i) != 0) {
                            side = PSpec.getSide(secnum, i, 0);
                            if (side.bottomtexture >= 0) {
                                if (doom.render.RData.textureheight[side.bottomtexture]
                                        < minsize) {
                                    minsize = doom.render.RData.textureheight[side.bottomtexture];
                                }
                            }
                            side = PSpec.getSide(secnum, i, 1);
                            if (side.bottomtexture >= 0) {
                                if (doom.render.RData.textureheight[side.bottomtexture]
                                        < minsize) {
                                    minsize = doom.render.RData.textureheight[side.bottomtexture];
                                }
                            }
                        }
                    }
                    floor.floordestheight =
                            floor.sector.floorheight + minsize;
                }
                break;

                case lowerAndChange:
                    floor.direction = -1;
                    floor.sector = sec;
                    floor.speed = PSpec.FLOORSPEED;
                    floor.floordestheight =
                            PSpec.pFindLowestFloorSurrounding(sec);
                    floor.texture = sec.floorpic;

                    for (i = 0; i < sec.linecount; i++) {
                        if (PSpec.twoSided(secnum, i) != 0) {
                            // The C uses pointer arithmetic
                            // `getSide(...)->sector - sectors` to get a sector
                            // index. Translate that as: compare the side's
                            // sector to PSetup.sectors[secnum].
                            if (PSpec.getSide(secnum, i, 0).sector
                                    == PSetup.sectors[secnum]) {
                                sec = PSpec.getSector(secnum, i, 1);

                                if (sec.floorheight == floor.floordestheight) {
                                    floor.texture = sec.floorpic;
                                    floor.newspecial = sec.special;
                                    break;
                                }
                            } else {
                                sec = PSpec.getSector(secnum, i, 0);

                                if (sec.floorheight == floor.floordestheight) {
                                    floor.texture = sec.floorpic;
                                    floor.newspecial = sec.special;
                                    break;
                                }
                            }
                        }
                    }
                default:
                    break;
            }
        }
        return rtn;
    }

    //
    // BUILD A STAIRCASE!
    //
    // p_floor.c lines 452–554 — EV_BuildStairs.
    public static int evBuildStairs(Line line, int type) {
        int secnum;
        int height;
        int i;
        int newsecnum;
        int texture;
        int ok;
        int rtn;

        Sector sec;
        Sector tsec;

        FloorMove floor;

        int stairsize = 0;
        int speed = 0;

        secnum = -1;
        rtn = 0;
        while ((secnum = PSpec.pFindSectorFromLineTag(line, secnum)) >= 0) {
            sec = PSetup.sectors[secnum];

            // ALREADY MOVING?  IF SO, KEEP GOING...
            if (sec.specialdata != null) {
                continue;
            }

            // new floor thinker
            rtn = 1;
            floor = new FloorMove();
            PTick.pAddThinker(floor);
            sec.specialdata = floor;
            floor.function.acp1 = PFloor::tMoveFloor;
            floor.direction = 1;
            floor.sector = sec;
            switch (type) {
                case build8:
                    speed = PSpec.FLOORSPEED / 4;
                    stairsize = 8 * Fixed.FRACUNIT;
                    break;
                case turbo16:
                    speed = PSpec.FLOORSPEED * 4;
                    stairsize = 16 * Fixed.FRACUNIT;
                    break;
            }
            floor.speed = speed;
            height = sec.floorheight + stairsize;
            floor.floordestheight = height;

            texture = sec.floorpic;

            // Find next sector to raise
            // 1.   Find 2-sided line with same sector side[0]
            // 2.   Other side is the next sector to raise
            do {
                ok = 0;
                for (i = 0; i < sec.linecount; i++) {
                    if ((sec.lines[i].flags & Line.ML_TWOSIDED) == 0) {
                        continue;
                    }

                    tsec = sec.lines[i].frontsector;
                    newsecnum = sectorIndex(tsec);

                    if (secnum != newsecnum) {
                        continue;
                    }

                    tsec = sec.lines[i].backsector;
                    newsecnum = sectorIndex(tsec);

                    if (tsec.floorpic != texture) {
                        continue;
                    }

                    height += stairsize;

                    if (tsec.specialdata != null) {
                        continue;
                    }

                    sec = tsec;
                    secnum = newsecnum;
                    floor = new FloorMove();

                    PTick.pAddThinker(floor);

                    sec.specialdata = floor;
                    floor.function.acp1 = PFloor::tMoveFloor;
                    floor.direction = 1;
                    floor.sector = sec;
                    floor.speed = speed;
                    floor.floordestheight = height;
                    ok = 1;
                    break;
                }
            } while (ok != 0);
        }
        return rtn;
    }

    // The C does `tsec - sectors` to get a sector index from a pointer.
    // In Java we scan the sectors array. Linear scan is fine: sectors
    // are at most a few hundred per level and EV_BuildStairs is rare.
    private static int sectorIndex(Sector s) {
        for (int i = 0; i < PSetup.numsectors; i++) {
            if (PSetup.sectors[i] == s) {
                return i;
            }
        }
        return -1;
    }

    private PFloor() {
    }
}
