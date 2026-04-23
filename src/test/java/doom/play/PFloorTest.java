package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;

import doom.render.Sector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PFloorTest {

    // Sector heights are fixed_t but integer arithmetic is what matters.
    // Tests use plain small ints to keep the branch logic obvious.

    @BeforeEach
    void reset() {
        // Default: no mobj blocking. Each test re-flips as needed.
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
}
