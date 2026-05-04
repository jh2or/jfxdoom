package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.RData;
import doom.render.Sector;
import doom.render.Side;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PFloorTest {

    // Sector heights are fixed_t but integer arithmetic is what matters.
    // Tests use plain small ints to keep the branch logic obvious.

    @BeforeEach
    void reset() {
        // Default: no mobj blocking. Each test re-flips as needed.
        PMap.changeSectorOverride = true;
        PMap.changeSectorResult = false;
    }

    // --- FLOOR DOWN ---

    // Past-dest: speed overshoots dest → snap to dest, return pastdest.
    @Test
    void floorDownSnapsToDestWhenSpeedExceedsDistance() {
        Sector s = new Sector();
        s.floorheight = 100;

        PSpec.Result r = PFloor.tMovePlane(s, 30, 80, false, 0, -1);

        assertEquals(PSpec.Result.pastdest, r);
        assertEquals(80, s.floorheight);
    }

    // Normal step: move -speed, return ok (no crush by default stub).
    @Test
    void floorDownMovesOneStep() {
        Sector s = new Sector();
        s.floorheight = 100;

        PSpec.Result r = PFloor.tMovePlane(s, 10, 50, false, 0, -1);

        assertEquals(PSpec.Result.ok, r);
        assertEquals(90, s.floorheight);
    }

    // Blocked mid-move → rollback + crushed.
    @Test
    void floorDownCrushRollsBackAndReturnsCrushed() {
        Sector s = new Sector();
        s.floorheight = 100;
        PMap.changeSectorResult = true;

        PSpec.Result r = PFloor.tMovePlane(s, 10, 50, false, 0, -1);

        assertEquals(PSpec.Result.crushed, r);
        assertEquals(100, s.floorheight); // rolled back
    }

    // Past-dest with blocking mobj: C's commented "//return crushed"
    // makes this path also return pastdest after the rollback write.
    @Test
    void floorDownPastDestRollsBackButStillReturnsPastdest() {
        Sector s = new Sector();
        s.floorheight = 100;
        PMap.changeSectorResult = true;

        PSpec.Result r = PFloor.tMovePlane(s, 30, 80, false, 0, -1);

        assertEquals(PSpec.Result.pastdest, r);
        assertEquals(100, s.floorheight); // rolled back
    }

    // --- FLOOR UP ---

    @Test
    void floorUpSnapsToDestWhenSpeedExceedsDistance() {
        Sector s = new Sector();
        s.floorheight = 40;

        PSpec.Result r = PFloor.tMovePlane(s, 30, 60, false, 0, 1);

        assertEquals(PSpec.Result.pastdest, r);
        assertEquals(60, s.floorheight);
    }

    @Test
    void floorUpMovesOneStep() {
        Sector s = new Sector();
        s.floorheight = 40;

        PSpec.Result r = PFloor.tMovePlane(s, 5, 100, false, 0, 1);

        assertEquals(PSpec.Result.ok, r);
        assertEquals(45, s.floorheight);
    }

    // Floor-up crush WITHOUT `crush` flag: roll back + crushed.
    @Test
    void floorUpCrushNoCrushFlagRollsBack() {
        Sector s = new Sector();
        s.floorheight = 40;
        PMap.changeSectorResult = true;

        PSpec.Result r = PFloor.tMovePlane(s, 5, 100, false, 0, 1);

        assertEquals(PSpec.Result.crushed, r);
        assertEquals(40, s.floorheight);
    }

    // Floor-up crush WITH `crush` flag: the C returns crushed
    // immediately without rolling back the floorheight increment.
    @Test
    void floorUpCrushWithCrushFlagDoesNotRollBack() {
        Sector s = new Sector();
        s.floorheight = 40;
        PMap.changeSectorResult = true;

        PSpec.Result r = PFloor.tMovePlane(s, 5, 100, true, 0, 1);

        assertEquals(PSpec.Result.crushed, r);
        assertEquals(45, s.floorheight); // NOT rolled back — asymmetry vs floor-down
    }

    // --- CEILING DOWN ---

    @Test
    void ceilingDownSnapsToDestWhenSpeedExceedsDistance() {
        Sector s = new Sector();
        s.ceilingheight = 200;

        PSpec.Result r = PFloor.tMovePlane(s, 30, 180, false, 1, -1);

        assertEquals(PSpec.Result.pastdest, r);
        assertEquals(180, s.ceilingheight);
    }

    @Test
    void ceilingDownMovesOneStep() {
        Sector s = new Sector();
        s.ceilingheight = 200;

        PSpec.Result r = PFloor.tMovePlane(s, 10, 100, false, 1, -1);

        assertEquals(PSpec.Result.ok, r);
        assertEquals(190, s.ceilingheight);
    }

    // Ceiling-down crush with crush flag: no rollback (symmetric to
    // floor-up-crush).
    @Test
    void ceilingDownCrushWithFlagDoesNotRollBack() {
        Sector s = new Sector();
        s.ceilingheight = 200;
        PMap.changeSectorResult = true;

        PSpec.Result r = PFloor.tMovePlane(s, 10, 100, true, 1, -1);

        assertEquals(PSpec.Result.crushed, r);
        assertEquals(190, s.ceilingheight);
    }

    @Test
    void ceilingDownCrushNoFlagRollsBack() {
        Sector s = new Sector();
        s.ceilingheight = 200;
        PMap.changeSectorResult = true;

        PSpec.Result r = PFloor.tMovePlane(s, 10, 100, false, 1, -1);

        assertEquals(PSpec.Result.crushed, r);
        assertEquals(200, s.ceilingheight);
    }

    // --- CEILING UP ---

    @Test
    void ceilingUpSnapsToDestWhenSpeedExceedsDistance() {
        Sector s = new Sector();
        s.ceilingheight = 100;

        PSpec.Result r = PFloor.tMovePlane(s, 30, 120, false, 1, 1);

        assertEquals(PSpec.Result.pastdest, r);
        assertEquals(120, s.ceilingheight);
    }

    // Ceiling-up has a #if 0 crush branch in the C — commented out.
    // When pChangeSector returns true, the ceiling still moves and
    // returns ok (no rollback, no crushed).
    @Test
    void ceilingUpNormalMoveReturnsOkEvenWhenBlocked() {
        Sector s = new Sector();
        s.ceilingheight = 100;
        PMap.changeSectorResult = true;

        PSpec.Result r = PFloor.tMovePlane(s, 5, 300, false, 1, 1);

        assertEquals(PSpec.Result.ok, r);
        assertEquals(105, s.ceilingheight);
    }

    @Test
    void ceilingUpMovesOneStep() {
        Sector s = new Sector();
        s.ceilingheight = 100;

        PSpec.Result r = PFloor.tMovePlane(s, 5, 300, false, 1, 1);

        assertEquals(PSpec.Result.ok, r);
        assertEquals(105, s.ceilingheight);
    }

    // Unknown floorOrCeiling / direction combinations return ok
    // without touching the sector — the outer switch falls through.
    @Test
    void unknownAxisReturnsOkUnchanged() {
        Sector s = new Sector();
        s.floorheight = 50;
        s.ceilingheight = 200;

        PSpec.Result r = PFloor.tMovePlane(s, 10, 100, false, 2, 1);

        assertEquals(PSpec.Result.ok, r);
        assertEquals(50, s.floorheight);
        assertEquals(200, s.ceilingheight);
    }

    // PSpec.Result ordering matches the C enum (ok=0, crushed=1, pastdest=2).
    @Test
    void resultEnumOrdinalsMatchCEnum() {
        assertEquals(0, PSpec.Result.ok.ordinal());
        assertEquals(1, PSpec.Result.crushed.ordinal());
        assertEquals(2, PSpec.Result.pastdest.ordinal());
    }

    // ----- T_MoveFloor / EV_DoFloor / EV_BuildStairs -----
    //
    // Setup helpers below build small worlds in PSetup.sectors[]/lines[]/sides[]
    // and clear the thinker list. EV_* dispatchers walk PSetup tags.

    private static void resetWorld() {
        PSetup.sectors = null;
        PSetup.numsectors = 0;
        PSetup.lines = null;
        PSetup.numlines = 0;
        PSetup.sides = null;
        PSetup.numsides = 0;
        PTick.pInitThinkers();
        PMap.changeSectorResult = false;
    }

    private static Sector mkSector(int floor, int ceiling, int floorpic, int special, int tag) {
        Sector s = new Sector();
        s.floorheight = floor;
        s.ceilingheight = ceiling;
        s.floorpic = floorpic;
        s.special = special;
        s.tag = tag;
        s.linecount = 0;
        return s;
    }

    private static Line mkLine(Sector front, Sector back, int flags) {
        Line ld = new Line();
        ld.frontsector = front;
        ld.backsector = back;
        ld.flags = flags;
        ld.sidenum[0] = -1;
        ld.sidenum[1] = -1;
        return ld;
    }

    // --- T_MoveFloor pastdest cleanup ---

    // pastdest with direction=-1 lowerAndChange: rewrites floorpic + special.
    @Test
    void tMoveFloorLowerAndChangeRewritesSectorOnPastDest() {
        resetWorld();
        Sector s = mkSector(/*floor*/100, /*ceil*/300, /*pic*/5, /*spec*/7, /*tag*/0);
        s.specialdata = new Object();

        PFloor.FloorMove f = new PFloor.FloorMove();
        f.sector = s;
        f.speed = 50;             // > distance, so first call hits pastdest
        f.floordestheight = 80;   // 100-50 = 50 < 80 → snap to 80
        f.direction = -1;
        f.type = PFloor.lowerAndChange;
        f.texture = 99;
        f.newspecial = 42;
        f.function.acp1 = PFloor::tMoveFloor;
        PTick.pAddThinker(f);

        PFloor.tMoveFloor(f);

        assertEquals(80, s.floorheight);          // snapped to dest
        assertNull(s.specialdata);                // cleared
        assertEquals(99, s.floorpic);             // rewritten
        assertEquals(42, s.special);              // rewritten
        assertTrue(f.removed);                    // P_RemoveThinker was called
    }

    // pastdest with direction=+1 donutRaise: rewrites floorpic + special.
    @Test
    void tMoveFloorDonutRaiseRewritesSectorOnPastDest() {
        resetWorld();
        Sector s = mkSector(100, 300, 5, 7, 0);
        s.specialdata = new Object();

        PFloor.FloorMove f = new PFloor.FloorMove();
        f.sector = s;
        f.speed = 50;
        f.floordestheight = 120;  // 100+50=150 > 120 → snap to 120
        f.direction = 1;
        f.type = PFloor.donutRaise;
        f.texture = 88;
        f.newspecial = 33;
        f.function.acp1 = PFloor::tMoveFloor;
        PTick.pAddThinker(f);

        PFloor.tMoveFloor(f);

        assertEquals(120, s.floorheight);
        assertNull(s.specialdata);
        assertEquals(88, s.floorpic);
        assertEquals(33, s.special);
        assertTrue(f.removed);
    }

    // pastdest with a non-rewriting type leaves floorpic/special alone.
    @Test
    void tMoveFloorRaiseFloorLeavesPicAndSpecialAlone() {
        resetWorld();
        Sector s = mkSector(100, 300, 5, 7, 0);
        s.specialdata = new Object();

        PFloor.FloorMove f = new PFloor.FloorMove();
        f.sector = s;
        f.speed = 50;
        f.floordestheight = 120;
        f.direction = 1;
        f.type = PFloor.raiseFloor;
        f.texture = 88;
        f.newspecial = 33;
        f.function.acp1 = PFloor::tMoveFloor;
        PTick.pAddThinker(f);

        PFloor.tMoveFloor(f);

        assertEquals(120, s.floorheight);
        assertNull(s.specialdata);
        assertEquals(5, s.floorpic);   // untouched
        assertEquals(7, s.special);    // untouched
        assertTrue(f.removed);
    }

    // Mid-move (not pastdest): no specialdata clear, no remove.
    @Test
    void tMoveFloorMidMoveDoesNotRemoveThinker() {
        resetWorld();
        Sector s = mkSector(100, 300, 5, 7, 0);
        Object marker = new Object();
        s.specialdata = marker;

        PFloor.FloorMove f = new PFloor.FloorMove();
        f.sector = s;
        f.speed = 10;
        f.floordestheight = 50;
        f.direction = -1;
        f.type = PFloor.lowerFloor;
        f.function.acp1 = PFloor::tMoveFloor;
        PTick.pAddThinker(f);

        PFloor.tMoveFloor(f);

        assertEquals(90, s.floorheight);
        assertSame(marker, s.specialdata);  // not cleared
        assertEquals(false, f.removed);
    }

    // --- EV_DoFloor: no matching tag returns 0 ---

    @Test
    void evDoFloorReturnsZeroWhenNoSectorTagMatches() {
        resetWorld();
        Sector s = mkSector(0, 200, 0, 0, /*tag*/5);
        PSetup.sectors = new Sector[] { s };
        PSetup.numsectors = 1;

        Line trigger = new Line();
        trigger.tag = 99;  // doesn't match

        assertEquals(0, PFloor.evDoFloor(trigger, PFloor.raiseFloor24));
    }

    // --- EV_DoFloor raiseFloor24: dest = floor + 24*FRACUNIT ---

    @Test
    void evDoFloorRaiseFloor24SetsExpectedDest() {
        resetWorld();
        Sector s = mkSector(/*floor*/100 * Fixed.FRACUNIT, 300 * Fixed.FRACUNIT, 0, 0, /*tag*/4);
        PSetup.sectors = new Sector[] { s };
        PSetup.numsectors = 1;

        Line trigger = new Line();
        trigger.tag = 4;

        int rtn = PFloor.evDoFloor(trigger, PFloor.raiseFloor24);

        assertEquals(1, rtn);
        assertNotNull(s.specialdata);

        PFloor.FloorMove f = (PFloor.FloorMove) s.specialdata;
        assertEquals(1, f.direction);
        assertEquals(PSpec.FLOORSPEED, f.speed);
        assertEquals(100 * Fixed.FRACUNIT + 24 * Fixed.FRACUNIT, f.floordestheight);
        assertEquals(false, f.crush);
        assertEquals(PFloor.raiseFloor24, f.type);
    }

    // --- EV_DoFloor raiseFloor512 ---

    @Test
    void evDoFloorRaiseFloor512SetsExpectedDest() {
        resetWorld();
        Sector s = mkSector(0, 1000 * Fixed.FRACUNIT, 0, 0, 4);
        PSetup.sectors = new Sector[] { s };
        PSetup.numsectors = 1;

        Line trigger = new Line();
        trigger.tag = 4;

        PFloor.evDoFloor(trigger, PFloor.raiseFloor512);
        PFloor.FloorMove f = (PFloor.FloorMove) s.specialdata;
        assertEquals(512 * Fixed.FRACUNIT, f.floordestheight);
    }

    // --- EV_DoFloor turboLower: speed*=4, dest=highest+8 if non-trivial ---

    @Test
    void evDoFloorTurboLowerSpeedAndDestOffset() {
        resetWorld();
        // Sector with one neighbor of higher floor.
        Sector target = mkSector(50 * Fixed.FRACUNIT, 300 * Fixed.FRACUNIT, 0, 0, 1);
        Sector neighbor = mkSector(20 * Fixed.FRACUNIT, 300 * Fixed.FRACUNIT, 0, 0, 0);

        Line shared = mkLine(target, neighbor, Line.ML_TWOSIDED);
        target.lines = new Line[] { shared };
        target.linecount = 1;

        PSetup.sectors = new Sector[] { target, neighbor };
        PSetup.numsectors = 2;

        Line trigger = new Line();
        trigger.tag = 1;

        PFloor.evDoFloor(trigger, PFloor.turboLower);
        PFloor.FloorMove f = (PFloor.FloorMove) target.specialdata;

        assertEquals(-1, f.direction);
        assertEquals(PSpec.FLOORSPEED * 4, f.speed);
        // highest surrounding floor = 20 (neighbor); != target.floorheight (50)
        // so the +8*FRACUNIT offset applies.
        assertEquals(20 * Fixed.FRACUNIT + 8 * Fixed.FRACUNIT, f.floordestheight);
    }

    // --- EV_DoFloor raiseFloorCrush: crush=true and dest -= 8 ---

    @Test
    void evDoFloorRaiseFloorCrushSetsCrushAndOffset() {
        resetWorld();
        // Two sectors so pFindLowestCeilingSurrounding has a neighbor.
        Sector target = mkSector(0, 300 * Fixed.FRACUNIT, 0, 0, 1);
        Sector neighbor = mkSector(0, 200 * Fixed.FRACUNIT, 0, 0, 0);

        Line shared = mkLine(target, neighbor, Line.ML_TWOSIDED);
        target.lines = new Line[] { shared };
        target.linecount = 1;

        PSetup.sectors = new Sector[] { target, neighbor };
        PSetup.numsectors = 2;

        Line trigger = new Line();
        trigger.tag = 1;

        PFloor.evDoFloor(trigger, PFloor.raiseFloorCrush);
        PFloor.FloorMove f = (PFloor.FloorMove) target.specialdata;

        assertEquals(true, f.crush);
        assertEquals(1, f.direction);
        // lowest surrounding ceiling = 200, less ceiling clip stays 200,
        // minus 8*FRACUNIT for the crush variant.
        assertEquals(200 * Fixed.FRACUNIT - 8 * Fixed.FRACUNIT, f.floordestheight);
    }

    // --- EV_DoFloor raiseFloor24AndChange: copies pic/special from
    //     line.frontsector ---

    @Test
    void evDoFloorRaiseFloor24AndChangeCopiesFromTriggerFront() {
        resetWorld();
        Sector target = mkSector(0, 300 * Fixed.FRACUNIT, /*pic*/0, /*special*/0, /*tag*/7);
        Sector lineFront = mkSector(0, 0, /*pic*/77, /*special*/88, /*tag*/0);
        PSetup.sectors = new Sector[] { target };
        PSetup.numsectors = 1;

        Line trigger = new Line();
        trigger.tag = 7;
        trigger.frontsector = lineFront;

        PFloor.evDoFloor(trigger, PFloor.raiseFloor24AndChange);

        assertEquals(77, target.floorpic);
        assertEquals(88, target.special);
        PFloor.FloorMove f = (PFloor.FloorMove) target.specialdata;
        assertEquals(24 * Fixed.FRACUNIT, f.floordestheight);
    }

    // --- EV_DoFloor skips already-active sectors ---

    @Test
    void evDoFloorSkipsSectorsWithSpecialdata() {
        resetWorld();
        Sector s = mkSector(0, 300, 0, 0, /*tag*/3);
        s.specialdata = new Object(); // already busy
        PSetup.sectors = new Sector[] { s };
        PSetup.numsectors = 1;

        Line trigger = new Line();
        trigger.tag = 3;

        // Returns 0 because the only matching sector was busy.
        assertEquals(0, PFloor.evDoFloor(trigger, PFloor.raiseFloor24));
    }

    // --- EV_DoFloor raiseToTexture: dest = floor + min bottomtexture height ---

    @Test
    void evDoFloorRaiseToTextureUsesShortestBottomtex() {
        resetWorld();
        // Texture heights: index 0=0, index 1=64*FRACUNIT, index 2=16*FRACUNIT.
        RData.textureheight = new int[] { 0, 64 * Fixed.FRACUNIT, 16 * Fixed.FRACUNIT };

        Sector target = mkSector(/*floor*/100, /*ceil*/500, 0, 0, /*tag*/2);
        Sector other  = mkSector(0, 0, 0, 0, 0);

        // Two sides of one two-sided line, with bottomtextures 1 (=64) and 2 (=16).
        Side front = new Side(); front.bottomtexture = 1; front.sector = target;
        Side back  = new Side(); back.bottomtexture = 2;  back.sector  = other;
        PSetup.sides = new Side[] { front, back };
        PSetup.numsides = 2;

        Line shared = mkLine(target, other, Line.ML_TWOSIDED);
        shared.sidenum[0] = 0;
        shared.sidenum[1] = 1;
        target.lines = new Line[] { shared };
        target.linecount = 1;

        PSetup.sectors = new Sector[] { target, other };
        PSetup.numsectors = 2;

        Line trigger = new Line();
        trigger.tag = 2;

        PFloor.evDoFloor(trigger, PFloor.raiseToTexture);
        PFloor.FloorMove f = (PFloor.FloorMove) target.specialdata;

        // minsize = min(64, 16)*FRACUNIT = 16*FRACUNIT.
        assertEquals(100 + 16 * Fixed.FRACUNIT, f.floordestheight);
        assertEquals(1, f.direction);

        RData.textureheight = null;
    }

    // --- EV_BuildStairs: build8 base case ---

    @Test
    void evBuildStairsBuild8FirstStepDestAndSpeed() {
        resetWorld();
        // Single starter sector; no two-sided line out, so the stair
        // walk stops after the first one.
        Sector start = mkSector(0, 300 * Fixed.FRACUNIT, /*pic*/4, 0, /*tag*/9);
        start.lines = new Line[0];
        start.linecount = 0;

        PSetup.sectors = new Sector[] { start };
        PSetup.numsectors = 1;

        Line trigger = new Line();
        trigger.tag = 9;

        int rtn = PFloor.evBuildStairs(trigger, PFloor.build8);

        assertEquals(1, rtn);
        PFloor.FloorMove f = (PFloor.FloorMove) start.specialdata;
        assertEquals(PSpec.FLOORSPEED / 4, f.speed);
        assertEquals(8 * Fixed.FRACUNIT, f.floordestheight);
        assertEquals(1, f.direction);
    }

    // --- EV_BuildStairs: turbo16 base case ---

    @Test
    void evBuildStairsTurbo16FirstStepDestAndSpeed() {
        resetWorld();
        Sector start = mkSector(0, 300 * Fixed.FRACUNIT, 4, 0, 9);
        start.lines = new Line[0];
        start.linecount = 0;

        PSetup.sectors = new Sector[] { start };
        PSetup.numsectors = 1;

        Line trigger = new Line();
        trigger.tag = 9;

        PFloor.evBuildStairs(trigger, PFloor.turbo16);
        PFloor.FloorMove f = (PFloor.FloorMove) start.specialdata;
        assertEquals(PSpec.FLOORSPEED * 4, f.speed);
        assertEquals(16 * Fixed.FRACUNIT, f.floordestheight);
    }

    // --- EV_BuildStairs walks two steps when adjacent matching sector exists ---

    @Test
    void evBuildStairsWalksToNeighborWithSamePic() {
        resetWorld();
        // s0 (tag=9) -- two-sided line --> s1 (same floorpic).
        // s1 should pick up a new FloorMove with destheight = 2 * 8 = 16.
        Sector s0 = mkSector(0, 300 * Fixed.FRACUNIT, /*pic*/4, 0, 9);
        Sector s1 = mkSector(0, 300 * Fixed.FRACUNIT, /*pic*/4, 0, 0);

        Line shared = mkLine(s0, s1, Line.ML_TWOSIDED);
        s0.lines = new Line[] { shared };
        s0.linecount = 1;
        s1.lines = new Line[0];
        s1.linecount = 0;

        PSetup.sectors = new Sector[] { s0, s1 };
        PSetup.numsectors = 2;

        Line trigger = new Line();
        trigger.tag = 9;

        PFloor.evBuildStairs(trigger, PFloor.build8);

        PFloor.FloorMove f0 = (PFloor.FloorMove) s0.specialdata;
        PFloor.FloorMove f1 = (PFloor.FloorMove) s1.specialdata;

        assertNotNull(f0);
        assertNotNull(f1);
        assertEquals(8 * Fixed.FRACUNIT, f0.floordestheight);
        assertEquals(16 * Fixed.FRACUNIT, f1.floordestheight); // height += stairsize
    }

    // --- floor_e ordinals match the C enum ---

    @Test
    void floorEnumOrdinalsMatchC() {
        assertEquals(0, PFloor.lowerFloor);
        assertEquals(1, PFloor.lowerFloorToLowest);
        assertEquals(2, PFloor.turboLower);
        assertEquals(3, PFloor.raiseFloor);
        assertEquals(4, PFloor.raiseFloorToNearest);
        assertEquals(5, PFloor.raiseToTexture);
        assertEquals(6, PFloor.lowerAndChange);
        assertEquals(7, PFloor.raiseFloor24);
        assertEquals(8, PFloor.raiseFloor24AndChange);
        assertEquals(9, PFloor.raiseFloorCrush);
        assertEquals(10, PFloor.raiseFloorTurbo);
        assertEquals(11, PFloor.donutRaise);
        assertEquals(12, PFloor.raiseFloor512);
    }

    @Test
    void stairEnumOrdinalsMatchC() {
        assertEquals(0, PFloor.build8);
        assertEquals(1, PFloor.turbo16);
    }
}
