package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.render.Line;
import doom.render.RData;
import doom.render.Sector;
import doom.render.Side;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PSpecTest {

    @BeforeEach
    void reset() {
        PSetup.numsectors = 0;
        PSetup.sectors = null;
        PSetup.numlines = 0;
        PSetup.lines = null;
        PSetup.numsides = 0;
        PSetup.sides = null;
        PTick.leveltime = 0;
        PSpec.numanims = 0;
        PSpec.numlinespecials = 0;
        PSpec.levelTimer = false;
        PSpec.levelTimeCount = 0;
        for (int i = 0; i < PSwitch.MAXBUTTONS; i++) {
            PSwitch.buttonlist[i].line = null;
            PSwitch.buttonlist[i].where = 0;
            PSwitch.buttonlist[i].btexture = 0;
            PSwitch.buttonlist[i].btimer = 0;
            PSwitch.buttonlist[i].soundorg = null;
        }
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

    // --- P_UpdateSpecials ---

    // Seeds a single-side Side array with a given set of textures.
    private static void seedOneSide(int topTex, int midTex, int botTex) {
        Side side = new Side();
        side.toptexture = topTex;
        side.midtexture = midTex;
        side.bottomtexture = botTex;
        PSetup.sides = new Side[] { side };
        PSetup.numsides = 1;
    }

    // Button countdown: btimer > 1 just decrements.
    @Test
    void updateSpecialsButtonTimerJustDecrements() {
        seedOneSide(0, 0, 0);
        Line line = new Line();
        line.sidenum[0] = 0;

        PSwitch.Button b = PSwitch.buttonlist[0];
        b.line = line;
        b.where = PSwitch.top;
        b.btexture = 7;
        b.btimer = 10;

        PSpec.pUpdateSpecials();

        assertEquals(9, b.btimer);
        assertEquals(0, PSetup.sides[0].toptexture); // not yet reverted
    }

    // Button countdown: hitting 0 reverts the texture back to btexture,
    // then memsets the slot.
    @Test
    void updateSpecialsButtonTimerRestoresTopTexture() {
        seedOneSide(99, 0, 0);           // "pressed" texture
        Line line = new Line();
        line.sidenum[0] = 0;

        PSwitch.Button b = PSwitch.buttonlist[0];
        b.line = line;
        b.where = PSwitch.top;
        b.btexture = 42;                 // original "off" texture
        b.btimer = 1;

        PSpec.pUpdateSpecials();

        assertEquals(42, PSetup.sides[0].toptexture);
        // Slot cleared (memset).
        assertEquals(0, b.btimer);
        assertNull(b.line);
        assertEquals(0, b.btexture);
    }

    @Test
    void updateSpecialsButtonTimerRestoresMidTexture() {
        seedOneSide(0, 99, 0);
        Line line = new Line();
        line.sidenum[0] = 0;

        PSwitch.Button b = PSwitch.buttonlist[0];
        b.line = line;
        b.where = PSwitch.middle;
        b.btexture = 55;
        b.btimer = 1;

        PSpec.pUpdateSpecials();

        assertEquals(55, PSetup.sides[0].midtexture);
    }

    @Test
    void updateSpecialsButtonTimerRestoresBottomTexture() {
        seedOneSide(0, 0, 99);
        Line line = new Line();
        line.sidenum[0] = 0;

        PSwitch.Button b = PSwitch.buttonlist[0];
        b.line = line;
        b.where = PSwitch.bottom;
        b.btexture = 77;
        b.btimer = 1;

        PSpec.pUpdateSpecials();

        assertEquals(77, PSetup.sides[0].bottomtexture);
    }

    // Line-scroll special 48 increments textureoffset by FRACUNIT each tic.
    @Test
    void updateSpecialsLineScroll48IncrementsTextureOffset() {
        seedOneSide(0, 0, 0);
        PSetup.sides[0].textureoffset = 100;

        Line line = new Line();
        line.sidenum[0] = 0;
        line.special = 48;

        PSpec.linespeciallist[0] = line;
        PSpec.numlinespecials = 1;

        PSpec.pUpdateSpecials();

        assertEquals(100 + doom.fixed.Fixed.FRACUNIT, PSetup.sides[0].textureoffset);
    }

    // Non-scrolling special lines are left alone.
    @Test
    void updateSpecialsIgnoresNonScrollSpecials() {
        seedOneSide(0, 0, 0);
        PSetup.sides[0].textureoffset = 77;

        Line line = new Line();
        line.sidenum[0] = 0;
        line.special = 99;

        PSpec.linespeciallist[0] = line;
        PSpec.numlinespecials = 1;

        PSpec.pUpdateSpecials();

        assertEquals(77, PSetup.sides[0].textureoffset);
    }

    // Level timer: counts down when active.
    @Test
    void updateSpecialsLevelTimerDecrementsWhenActive() {
        PSpec.levelTimer = true;
        PSpec.levelTimeCount = 10;

        PSpec.pUpdateSpecials();

        assertEquals(9, PSpec.levelTimeCount);
    }

    // Level timer: inactive → not decremented.
    @Test
    void updateSpecialsLevelTimerIgnoredWhenOff() {
        PSpec.levelTimer = false;
        PSpec.levelTimeCount = 10;

        PSpec.pUpdateSpecials();

        assertEquals(10, PSpec.levelTimeCount);
    }

    // Flat/texture animation cycles texturetranslation[] for each
    // entry in `anims[]`.
    @Test
    void updateSpecialsAnimatesTextureIndicesWhenInstalled() {
        // Save + stub the RData translation arrays.
        int[] savedTex = RData.texturetranslation;
        int[] savedFlat = RData.flattranslation;
        try {
            RData.texturetranslation = new int[16];
            RData.flattranslation = new int[16];

            // Install one texture anim: basepic=5, numpics=3, speed=2.
            PSpec.Anim a = PSpec.anims[0];
            a.istexture = true;
            a.basepic = 5;
            a.numpics = 3;
            a.picnum = 7;
            a.speed = 2;
            PSpec.numanims = 1;

            PTick.leveltime = 4;

            PSpec.pUpdateSpecials();

            // For each i in [basepic, basepic+numpics):
            //   pic = basepic + ((leveltime / speed + i) % numpics)
            // leveltime/speed = 2. i=5: (2+5)%3 = 1 → pic=6. i=6: 2%3 → pic=7.
            // i=7: (2+7)%3 = 0 → pic=5.
            assertEquals(6, RData.texturetranslation[5]);
            assertEquals(7, RData.texturetranslation[6]);
            assertEquals(5, RData.texturetranslation[7]);
        } finally {
            RData.texturetranslation = savedTex;
            RData.flattranslation = savedFlat;
        }
    }

    // Flat animation: same logic, writes flattranslation[] when istexture = false.
    @Test
    void updateSpecialsAnimatesFlatIndices() {
        int[] savedTex = RData.texturetranslation;
        int[] savedFlat = RData.flattranslation;
        try {
            RData.texturetranslation = new int[16];
            RData.flattranslation = new int[16];

            PSpec.Anim a = PSpec.anims[0];
            a.istexture = false;
            a.basepic = 2;
            a.numpics = 2;
            a.picnum = 3;
            a.speed = 1;
            PSpec.numanims = 1;

            PTick.leveltime = 1;

            PSpec.pUpdateSpecials();

            // leveltime/speed = 1. i=2: (1+2)%2 = 1 → 2+1 = 3.
            //                    i=3: (1+3)%2 = 0 → 2+0 = 2.
            assertEquals(3, RData.flattranslation[2]);
            assertEquals(2, RData.flattranslation[3]);
            // texturetranslation untouched.
            assertEquals(0, RData.texturetranslation[2]);
        } finally {
            RData.texturetranslation = savedTex;
            RData.flattranslation = savedFlat;
        }
    }

    // Sanity: animdefs[] carries the expected number of real entries.
    @Test
    void animdefsCountMatchesCReference() {
        int nonNull = 0;
        for (PSpec.AnimDef d : PSpec.animdefs) {
            if (d != null) nonNull++;
        }
        // Doom 1 + Doom 2 flat animations + texture animations = 22.
        assertEquals(22, nonNull);
    }

    // Sizing constants match the C header.
    @Test
    void animSizingConstantsMatchHeader() {
        assertEquals(32, PSpec.MAXANIMS);
        assertEquals(64, PSpec.MAXLINEANIMS);
    }
}
