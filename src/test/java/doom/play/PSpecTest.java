package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import doom.render.Line;
import doom.render.Sector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PSpecTest {

    @BeforeEach
    void reset() {
        PSetup.numsectors = 0;
        PSetup.sectors = null;
        PSetup.numlines = 0;
        PSetup.lines = null;
    }

    // getNextSector: one-sided line (ML_TWOSIDED not set) returns null.
    @Test
    void getNextSectorReturnsNullForOneSided() {
        Line line = new Line();
        line.flags = 0;
        Sector front = new Sector();
        Sector back = new Sector();
        line.frontsector = front;
        line.backsector = back;

        assertNull(PSpec.getNextSector(line, front));
    }

    // getNextSector: when asking from front, returns back.
    @Test
    void getNextSectorReturnsBackFromFront() {
        Line line = new Line();
        line.flags = Line.ML_TWOSIDED;
        Sector front = new Sector();
        Sector back = new Sector();
        line.frontsector = front;
        line.backsector = back;

        assertSame(back, PSpec.getNextSector(line, front));
    }

    // getNextSector: when asking from back, returns front.
    @Test
    void getNextSectorReturnsFrontFromBack() {
        Line line = new Line();
        line.flags = Line.ML_TWOSIDED;
        Sector front = new Sector();
        Sector back = new Sector();
        line.frontsector = front;
        line.backsector = back;

        assertSame(front, PSpec.getNextSector(line, back));
    }

    // P_FindSectorFromLineTag: scan starting at start+1 for a tag match.
    @Test
    void findSectorFromLineTagIteratesMatches() {
        Sector s0 = new Sector(); s0.tag = 9;
        Sector s1 = new Sector(); s1.tag = 5;
        Sector s2 = new Sector(); s2.tag = 9;
        Sector s3 = new Sector(); s3.tag = 9;
        PSetup.sectors = new Sector[] { s0, s1, s2, s3 };
        PSetup.numsectors = 4;
        Line line = new Line();
        line.tag = 9;

        int a = PSpec.pFindSectorFromLineTag(line, -1);
        int b = PSpec.pFindSectorFromLineTag(line, a);
        int c = PSpec.pFindSectorFromLineTag(line, b);
        int d = PSpec.pFindSectorFromLineTag(line, c);

        assertEquals(0, a);
        assertEquals(2, b);
        assertEquals(3, c);
        assertEquals(-1, d); // no more matches
    }

    // P_FindMinSurroundingLight: a sector with two adjacent neighbours
    // picks the darker one; the original max bounds the result when
    // every neighbour is brighter.
    @Test
    void findMinSurroundingLightPicksDarkestNeighbour() {
        Sector center = new Sector();
        center.lightlevel = 200;
        Sector dark = new Sector();   dark.lightlevel = 80;
        Sector bright = new Sector(); bright.lightlevel = 240;

        Line l1 = new Line();
        l1.flags = Line.ML_TWOSIDED;
        l1.frontsector = center;
        l1.backsector = dark;
        Line l2 = new Line();
        l2.flags = Line.ML_TWOSIDED;
        l2.frontsector = center;
        l2.backsector = bright;

        center.lines = new Line[] { l1, l2 };
        center.linecount = 2;

        assertEquals(80, PSpec.pFindMinSurroundingLight(center, 200));
    }

    @Test
    void findMinSurroundingLightFallsBackToMaxWhenNoDarkerNeighbour() {
        Sector center = new Sector();
        Sector bright = new Sector(); bright.lightlevel = 250;

        Line l = new Line();
        l.flags = Line.ML_TWOSIDED;
        l.frontsector = center;
        l.backsector = bright;

        center.lines = new Line[] { l };
        center.linecount = 1;

        assertEquals(128, PSpec.pFindMinSurroundingLight(center, 128));
    }

    // One-sided neighbours (no ML_TWOSIDED) are ignored.
    @Test
    void findMinSurroundingLightSkipsOneSidedLines() {
        Sector center = new Sector();

        Line oneSided = new Line();
        oneSided.flags = 0;
        oneSided.frontsector = center;
        oneSided.backsector = null;

        center.lines = new Line[] { oneSided };
        center.linecount = 1;

        assertEquals(100, PSpec.pFindMinSurroundingLight(center, 100));
    }

    // --- Surrounding floor / ceiling walkers ---

    // Helper: two-sided line between `a` and `b`.
    private static Line twoSided(Sector front, Sector back) {
        Line l = new Line();
        l.flags = Line.ML_TWOSIDED;
        l.frontsector = front;
        l.backsector = back;
        return l;
    }

    @Test
    void findLowestFloorSurroundingSeedsWithOwnFloor() {
        Sector center = new Sector();
        center.floorheight = 100;
        Sector lower = new Sector();  lower.floorheight = 40;
        Sector higher = new Sector(); higher.floorheight = 160;

        center.lines = new Line[] { twoSided(center, lower), twoSided(center, higher) };
        center.linecount = 2;

        assertEquals(40, PSpec.pFindLowestFloorSurrounding(center));
    }

    // No neighbour darker than the seed → seed wins (own floor).
    @Test
    void findLowestFloorSurroundingFallsBackToOwnFloor() {
        Sector center = new Sector();
        center.floorheight = 100;
        Sector higher = new Sector(); higher.floorheight = 200;

        center.lines = new Line[] { twoSided(center, higher) };
        center.linecount = 1;

        assertEquals(100, PSpec.pFindLowestFloorSurrounding(center));
    }

    // Isolated sector with no two-sided neighbours returns the
    // -500 * FRACUNIT sentinel unchanged.
    @Test
    void findHighestFloorSurroundingReturnsSentinelWhenIsolated() {
        Sector center = new Sector();
        center.floorheight = 100;
        Line oneSided = new Line();
        oneSided.flags = 0;
        oneSided.frontsector = center;
        oneSided.backsector = null;

        center.lines = new Line[] { oneSided };
        center.linecount = 1;

        assertEquals(-500 * doom.fixed.Fixed.FRACUNIT,
                PSpec.pFindHighestFloorSurrounding(center));
    }

    @Test
    void findHighestFloorSurroundingPicksMaxAcrossNeighbours() {
        Sector center = new Sector();
        Sector a = new Sector(); a.floorheight = 50;
        Sector b = new Sector(); b.floorheight = 170;
        Sector c = new Sector(); c.floorheight = 90;

        center.lines = new Line[] { twoSided(center, a), twoSided(center, b), twoSided(center, c) };
        center.linecount = 3;

        assertEquals(170, PSpec.pFindHighestFloorSurrounding(center));
    }

    // P_FindNextHighestFloor: min of neighbours strictly above current.
    @Test
    void findNextHighestFloorPicksSmallestStrictlyAbove() {
        Sector center = new Sector();
        Sector a = new Sector(); a.floorheight = 50;
        Sector b = new Sector(); b.floorheight = 80;
        Sector c = new Sector(); c.floorheight = 120;
        Sector d = new Sector(); d.floorheight = 70;

        center.lines = new Line[] {
                twoSided(center, a), twoSided(center, b),
                twoSided(center, c), twoSided(center, d) };
        center.linecount = 4;

        assertEquals(70, PSpec.pFindNextHighestFloor(center, 60));
    }

    // No neighbour above current → return currentheight (the C quirk).
    @Test
    void findNextHighestFloorReturnsCurrentWhenNoneAbove() {
        Sector center = new Sector();
        Sector low = new Sector(); low.floorheight = 10;

        center.lines = new Line[] { twoSided(center, low) };
        center.linecount = 1;

        assertEquals(100, PSpec.pFindNextHighestFloor(center, 100));
    }

    @Test
    void findNextHighestFloorIgnoresOneSidedNeighbours() {
        Sector center = new Sector();
        Line oneSided = new Line();
        oneSided.flags = 0;
        oneSided.frontsector = center;
        oneSided.backsector = null;

        center.lines = new Line[] { oneSided };
        center.linecount = 1;

        assertEquals(50, PSpec.pFindNextHighestFloor(center, 50));
    }

    // MAX_ADJOINING_SECTORS cap: more than 20 candidates triggers the
    // warning + break. Result is the min of the first 20 collected.
    @Test
    void findNextHighestFloorCapsAt20Adjoining() {
        Sector center = new Sector();
        Line[] lines = new Line[25];
        // Heights 100..124, all strictly above currentheight=0.
        for (int i = 0; i < 25; i++) {
            Sector n = new Sector();
            n.floorheight = 100 + i;
            lines[i] = twoSided(center, n);
        }
        center.lines = lines;
        center.linecount = 25;

        // Collects 20 (heights 100..119), min = 100.
        assertEquals(100, PSpec.pFindNextHighestFloor(center, 0));
    }

    @Test
    void findLowestCeilingSurroundingPicksMin() {
        Sector center = new Sector();
        Sector a = new Sector(); a.ceilingheight = 300;
        Sector b = new Sector(); b.ceilingheight = 180;
        Sector c = new Sector(); c.ceilingheight = 220;

        center.lines = new Line[] { twoSided(center, a), twoSided(center, b), twoSided(center, c) };
        center.linecount = 3;

        assertEquals(180, PSpec.pFindLowestCeilingSurrounding(center));
    }

    @Test
    void findLowestCeilingSurroundingReturnsMaxIntWhenIsolated() {
        Sector center = new Sector();
        Line oneSided = new Line();
        oneSided.flags = 0;
        oneSided.frontsector = center;
        oneSided.backsector = null;

        center.lines = new Line[] { oneSided };
        center.linecount = 1;

        assertEquals(Integer.MAX_VALUE, PSpec.pFindLowestCeilingSurrounding(center));
    }

    @Test
    void findHighestCeilingSurroundingPicksMax() {
        Sector center = new Sector();
        Sector a = new Sector(); a.ceilingheight = 100;
        Sector b = new Sector(); b.ceilingheight = 400;

        center.lines = new Line[] { twoSided(center, a), twoSided(center, b) };
        center.linecount = 2;

        assertEquals(400, PSpec.pFindHighestCeilingSurrounding(center));
    }

    // Isolated sector → seed 0 is returned.
    @Test
    void findHighestCeilingSurroundingReturnsZeroWhenIsolated() {
        Sector center = new Sector();
        center.lines = new Line[0];
        center.linecount = 0;

        assertEquals(0, PSpec.pFindHighestCeilingSurrounding(center));
    }
}
