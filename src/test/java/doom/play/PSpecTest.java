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
}
