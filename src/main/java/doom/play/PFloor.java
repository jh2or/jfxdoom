// Port of linuxdoom-1.10/p_floor.c — partial.
//
// p_floor.c covers floor animations (raising stairs, donut floors,
// lower-to-lowest, raise-to-texture, ...). The whole file hangs off
// T_MovePlane, the generic plane mover that both floor and ceiling
// thinkers drive. Downstream (p_plats, p_ceilng, p_doors) all call
// T_MovePlane directly.
//
// This port carries T_MovePlane only; T_MoveFloor, EV_DoFloor, and
// EV_BuildStairs pull in a crop of still-unported helpers
// (`P_FindHighestFloorSurrounding`, `P_FindLowestCeilingSurrounding`,
// `P_FindNextHighestFloor`, `twoSided`, `getSide`, the `floor_e` and
// `stair_e` enums, `floormove_t`) plus `S_StartSound` and
// `P_RemoveThinker` callers, so they land with p_spec.c's fuller port.
package doom.play;

import doom.render.Sector;

public final class PFloor {

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

    private PFloor() {
    }
}
