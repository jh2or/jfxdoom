package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.Node;
import doom.render.RMain;
import doom.render.Sector;
import doom.render.Side;
import doom.render.Slopetype;
import doom.render.Subsector;
import doom.render.Vertex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PMapTest {

    // Tests use realistic per-tic step distances (~30 units). Moves
    // long enough to bypass the destination-bbox-only collision check
    // would mirror an engine quirk that's already kept in check by
    // MAXMOVE clamping in P_XYMovement (p_mobj, deferred).

    @BeforeEach
    void resetWorld() {
        PSetup.sectors = null;
        PSetup.numsectors = 0;
        PSetup.subsectors = null;
        PSetup.numsubsectors = 0;
        PSetup.nodes = null;
        PSetup.numnodes = 0;
        PSetup.lines = null;
        PSetup.numlines = 0;
        PSetup.sides = null;
        PSetup.numsides = 0;
        PSetup.bmaporgx = 0;
        PSetup.bmaporgy = 0;
        PSetup.bmapwidth = 0;
        PSetup.bmapheight = 0;
        PSetup.blockmap = null;
        PSetup.blockmaplump = null;
        PSetup.blocklinks = null;

        PMap.tmthing = null;
        PMap.tmflags = 0;
        PMap.tmx = 0;
        PMap.tmy = 0;
        PMap.tmfloorz = 0;
        PMap.tmceilingz = 0;
        PMap.tmdropoffz = 0;
        PMap.floatok = false;
        PMap.ceilingline = null;
        PMap.numspechit = 0;
        for (int i = 0; i < PMap.MAXSPECIALCROSS; i++) PMap.spechit[i] = null;
        PMap.changeSectorResult = false;

        RMain.validcount++;
        // pitCheckThing tests use pSpawnMobj, which adds to the
        // thinker chain — the chain must be initialised first.
        PTick.pInitThinkers();
    }

    // Single-sector world with a 1×1 blockmap origin -64*FRACUNIT, so
    // block (0,0) covers [-64, +64] in both axes (block size = 128
    // map units = 128*FRACUNIT in fixed). MAXRADIUS=32 extension still
    // resolves to block 0 for tests that move within ±32.
    private static Sector openWorld(int floor, int ceiling) {
        Sector sec = new Sector();
        sec.floorheight = floor;
        sec.ceilingheight = ceiling;
        sec.thinglist = null;

        Subsector ss = new Subsector();
        ss.sector = sec;

        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;
        PSetup.subsectors = new Subsector[] { ss };
        PSetup.numsubsectors = 1;
        PSetup.numnodes = 0;
        PSetup.nodes = new Node[0];

        PSetup.bmaporgx = -64 * Fixed.FRACUNIT;
        PSetup.bmaporgy = -64 * Fixed.FRACUNIT;
        PSetup.bmapwidth = 1;
        PSetup.bmapheight = 1;
        PSetup.blocklinks = new Mobj[1];

        // Empty blockmap: header (4 ints), 1 block-offset (=5), then
        // a single -1 terminator at index 5.
        PSetup.blockmaplump = new int[] { -64, -64, 1, 1, 5, -1 };
        PSetup.blockmap = PSetup.blockmaplump;

        return sec;
    }

    // Install one line in the world's single-block index at line index 0.
    // Returns the line so callers can tweak special/flags.
    private static Line installLine(Sector front, Sector back,
                                     int x1, int y1, int x2, int y2) {
        Line ld = new Line();
        ld.v1 = new Vertex(); ld.v1.x = x1; ld.v1.y = y1;
        ld.v2 = new Vertex(); ld.v2.x = x2; ld.v2.y = y2;
        ld.dx = x2 - x1;
        ld.dy = y2 - y1;
        ld.slopetype = (ld.dx == 0) ? Slopetype.ST_VERTICAL
                : (ld.dy == 0) ? Slopetype.ST_HORIZONTAL
                : Slopetype.ST_POSITIVE;
        ld.bbox[MBbox.BOXLEFT] = Math.min(x1, x2);
        ld.bbox[MBbox.BOXRIGHT] = Math.max(x1, x2);
        ld.bbox[MBbox.BOXBOTTOM] = Math.min(y1, y2);
        ld.bbox[MBbox.BOXTOP] = Math.max(y1, y2);
        ld.frontsector = front;
        ld.backsector = back;
        ld.sidenum[0] = 0;
        ld.sidenum[1] = (back == null) ? -1 : 1;
        if (back != null) ld.flags |= Line.ML_TWOSIDED;

        PSetup.lines = new Line[] { ld };
        PSetup.numlines = 1;

        Side front0 = new Side();
        front0.sector = front;
        if (back != null) {
            Side back0 = new Side();
            back0.sector = back;
            PSetup.sides = new Side[] { front0, back0 };
            PSetup.numsides = 2;
        } else {
            PSetup.sides = new Side[] { front0 };
            PSetup.numsides = 1;
        }

        // Blockmap: header (4) + 1 offset → 5 + line list [0, -1].
        PSetup.blockmaplump = new int[] { -64, -64, 1, 1, 5, 0, -1 };
        PSetup.blockmap = PSetup.blockmaplump;

        return ld;
    }

    private static Mobj makeMobj(int x, int y, int radius, int height) {
        Mobj m = new Mobj();
        m.x = x;
        m.y = y;
        m.radius = radius;
        m.height = height;
        m.flags = 0;
        return m;
    }

    // Link the mobj into the world (subsector + sector list +
    // blockmap). pTryMove unset/sets, so test mobjs need to start
    // linked.
    private static void linkMobj(Mobj m) {
        PMaputl.pSetThingPosition(m);
    }

    // --- P_CheckPosition: empty world ---

    @Test
    void checkPositionEmptyWorldReturnsTrueAndSetsFloorCeiling() {
        openWorld(0, 200 * Fixed.FRACUNIT);

        Mobj m = makeMobj(0, 0, 16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);

        boolean rtn = PMap.pCheckPosition(m, 0, 0);

        assertTrue(rtn);
        assertEquals(0, PMap.tmfloorz);
        assertEquals(200 * Fixed.FRACUNIT, PMap.tmceilingz);
        assertEquals(0, PMap.numspechit);
    }

    // --- PIT_CheckLine: one-sided line blocks ---
    //
    // Wall horizontal at y=0 between x=-50..+50. Mover at (0, -30)
    // moving to (0, -1) — destination bbox y=[-17, +15] crosses the
    // wall.

    @Test
    void tryMoveBlockedByOneSidedWall() {
        Sector sec = openWorld(0, 200 * Fixed.FRACUNIT);
        installLine(sec, null,
                -50 * Fixed.FRACUNIT, 0,
                 50 * Fixed.FRACUNIT, 0);

        Mobj m = makeMobj(0, -30 * Fixed.FRACUNIT,
                16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        m.z = 0;
        linkMobj(m);

        boolean rtn = PMap.pTryMove(m, 0, -1 * Fixed.FRACUNIT);

        assertFalse(rtn);
        // Position unchanged (TryMove returned before commit).
        assertEquals(0, m.x);
        assertEquals(-30 * Fixed.FRACUNIT, m.y);
    }

    // --- ML_BLOCKING blocks even players ---

    @Test
    void tryMoveBlockedByBlockingFlagOnTwoSidedLine() {
        Sector front = openWorld(0, 200 * Fixed.FRACUNIT);
        Sector back = new Sector();
        back.floorheight = 0;
        back.ceilingheight = 200 * Fixed.FRACUNIT;

        Line ld = installLine(front, back,
                -50 * Fixed.FRACUNIT, 0,
                 50 * Fixed.FRACUNIT, 0);
        ld.flags |= Line.ML_BLOCKING;

        Mobj m = makeMobj(0, -30 * Fixed.FRACUNIT,
                16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        m.z = 0;
        m.player = new Player();
        linkMobj(m);

        assertFalse(PMap.pTryMove(m, 0, -1 * Fixed.FRACUNIT));
    }

    // --- ML_BLOCKMONSTERS blocks monsters but lets players through ---

    @Test
    void blockMonstersFlagBlocksMonsterButNotPlayer() {
        Sector front = openWorld(0, 200 * Fixed.FRACUNIT);
        Sector back = new Sector();
        back.floorheight = 0;
        back.ceilingheight = 200 * Fixed.FRACUNIT;

        Line ld = installLine(front, back,
                -50 * Fixed.FRACUNIT, 0,
                 50 * Fixed.FRACUNIT, 0);
        ld.flags |= Line.ML_BLOCKMONSTERS;

        Mobj monster = makeMobj(0, -30 * Fixed.FRACUNIT,
                16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        monster.z = 0;
        linkMobj(monster);
        assertFalse(PMap.pTryMove(monster, 0, -1 * Fixed.FRACUNIT));

        // Reset and try as a player on the same setup.
        resetWorld();
        front = openWorld(0, 200 * Fixed.FRACUNIT);
        back = new Sector();
        back.floorheight = 0;
        back.ceilingheight = 200 * Fixed.FRACUNIT;
        ld = installLine(front, back,
                -50 * Fixed.FRACUNIT, 0,
                 50 * Fixed.FRACUNIT, 0);
        ld.flags |= Line.ML_BLOCKMONSTERS;

        Mobj player = makeMobj(0, -30 * Fixed.FRACUNIT,
                16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        player.z = 0;
        player.player = new Player();
        linkMobj(player);
        assertTrue(PMap.pTryMove(player, 0, -1 * Fixed.FRACUNIT));
    }

    // --- Step-up cap (24 units) ---

    @Test
    void tryMoveBlockedByStepTooHigh() {
        Sector front = openWorld(0, 200 * Fixed.FRACUNIT);
        Sector back = new Sector();
        back.floorheight = 32 * Fixed.FRACUNIT; // > 24, too tall
        back.ceilingheight = 200 * Fixed.FRACUNIT;

        installLine(front, back,
                -50 * Fixed.FRACUNIT, 0,
                 50 * Fixed.FRACUNIT, 0);

        Mobj m = makeMobj(0, -30 * Fixed.FRACUNIT,
                16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        m.z = 0;
        m.player = new Player();
        linkMobj(m);

        assertFalse(PMap.pTryMove(m, 0, -1 * Fixed.FRACUNIT));
        assertTrue(PMap.floatok); // height check passed; step check failed
    }

    // 24-unit step is allowed.
    @Test
    void tryMoveAllowsTwentyFourStep() {
        Sector front = openWorld(0, 200 * Fixed.FRACUNIT);
        Sector back = new Sector();
        back.floorheight = 24 * Fixed.FRACUNIT; // exactly the step cap
        back.ceilingheight = 200 * Fixed.FRACUNIT;

        installLine(front, back,
                -50 * Fixed.FRACUNIT, 0,
                 50 * Fixed.FRACUNIT, 0);

        Mobj m = makeMobj(0, -30 * Fixed.FRACUNIT,
                16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        m.z = 0;
        m.player = new Player();
        linkMobj(m);

        assertTrue(PMap.pTryMove(m, 0, -1 * Fixed.FRACUNIT));
        assertEquals(-1 * Fixed.FRACUNIT, m.y);
    }

    // --- Doesn't fit (ceiling/floor too tight) ---

    @Test
    void tryMoveBlockedWhenDoesntFit() {
        Sector front = openWorld(0, 200 * Fixed.FRACUNIT);
        Sector back = new Sector();
        back.floorheight = 0;
        back.ceilingheight = 40 * Fixed.FRACUNIT; // gap < mobj.height

        installLine(front, back,
                -50 * Fixed.FRACUNIT, 0,
                 50 * Fixed.FRACUNIT, 0);

        Mobj m = makeMobj(0, -30 * Fixed.FRACUNIT,
                16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT); // height 56 > gap 40
        m.z = 0;
        m.player = new Player();
        linkMobj(m);

        assertFalse(PMap.pTryMove(m, 0, -1 * Fixed.FRACUNIT));
        assertFalse(PMap.floatok); // height-fit check failed
    }

    // --- MF_NOCLIP bypasses everything ---

    @Test
    void noClipMobjMovesThroughEverything() {
        Sector front = openWorld(0, 200 * Fixed.FRACUNIT);
        Sector back = new Sector();
        back.floorheight = 100 * Fixed.FRACUNIT; // huge step
        back.ceilingheight = 110 * Fixed.FRACUNIT; // tiny gap

        Line ld = installLine(front, back,
                -50 * Fixed.FRACUNIT, 0,
                 50 * Fixed.FRACUNIT, 0);
        ld.flags |= Line.ML_BLOCKING; // even blocking ignored

        Mobj m = makeMobj(0, -30 * Fixed.FRACUNIT,
                16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        m.z = 0;
        m.flags = Mobj.MF_NOCLIP;
        linkMobj(m);

        assertTrue(PMap.pTryMove(m, 0, -1 * Fixed.FRACUNIT));
        assertEquals(-1 * Fixed.FRACUNIT, m.y);
    }

    // --- PIT_CheckThing: solid blocker ---

    @Test
    void tryMoveBlockedBySolidMobj() {
        openWorld(0, 200 * Fixed.FRACUNIT);

        // Plant a solid mobj near the destination at (0, 0) in block 0.
        Mobj blocker = makeMobj(0, 0,
                16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        blocker.flags = Mobj.MF_SOLID;
        linkMobj(blocker);

        Mobj mover = makeMobj(0, -30 * Fixed.FRACUNIT,
                16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        mover.z = 0;
        linkMobj(mover);

        assertFalse(PMap.pTryMove(mover, 0, -1 * Fixed.FRACUNIT));
    }

    // Non-solid mobj at destination is passable.
    @Test
    void tryMovePassesNonSolidMobj() {
        openWorld(0, 200 * Fixed.FRACUNIT);

        Mobj inert = makeMobj(0, 0,
                16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        inert.flags = 0; // not solid, not special, not shootable
        linkMobj(inert);

        Mobj mover = makeMobj(0, -30 * Fixed.FRACUNIT,
                16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        mover.z = 0;
        linkMobj(mover);

        assertTrue(PMap.pTryMove(mover, 0, -1 * Fixed.FRACUNIT));
    }

    // --- TryMove updates floorz/ceilingz on the mobj ---

    @Test
    void tryMoveCommitsFloorAndCeilingZ() {
        openWorld(40 * Fixed.FRACUNIT, 240 * Fixed.FRACUNIT);

        Mobj m = makeMobj(0, 0, 16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        m.z = 40 * Fixed.FRACUNIT;
        m.player = new Player();
        linkMobj(m);

        assertTrue(PMap.pTryMove(m, 16 * Fixed.FRACUNIT, 16 * Fixed.FRACUNIT));
        assertEquals(40 * Fixed.FRACUNIT, m.floorz);
        assertEquals(240 * Fixed.FRACUNIT, m.ceilingz);
        assertEquals(16 * Fixed.FRACUNIT, m.x);
        assertEquals(16 * Fixed.FRACUNIT, m.y);
    }

    // --- spechit accumulates special lines crossed ---

    @Test
    void tryMoveAcrossSpecialLineRecordsAndDispatches() {
        Sector front = openWorld(0, 200 * Fixed.FRACUNIT);
        Sector back = new Sector();
        back.floorheight = 0;
        back.ceilingheight = 200 * Fixed.FRACUNIT;

        Line ld = installLine(front, back,
                -50 * Fixed.FRACUNIT, 0,
                 50 * Fixed.FRACUNIT, 0);
        ld.special = 13;  // Light Turn On 255 (case 13 in P_CrossSpecialLine)

        Mobj m = makeMobj(0, -15 * Fixed.FRACUNIT,
                16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        m.z = 0;
        m.player = new Player();
        linkMobj(m);

        // Cross from y=-15 (front side) to y=+1 (back side) — bbox at
        // dest is y=[-15, +17], straddles the line; the side change
        // triggers P_CrossSpecialLine.
        assertTrue(PMap.pTryMove(m, 0, 1 * Fixed.FRACUNIT));
        // P_CrossSpecialLine for case 13 sets line.special = 0 (one-shot).
        assertEquals(0, ld.special);
    }

    // --- PIT_CheckLine bbox short-circuit ---

    @Test
    void pitCheckLineBboxOutsideEarlyExits() {
        openWorld(0, 200 * Fixed.FRACUNIT);

        Line ld = new Line();
        ld.bbox[MBbox.BOXLEFT] = 1000 * Fixed.FRACUNIT;
        ld.bbox[MBbox.BOXRIGHT] = 2000 * Fixed.FRACUNIT;
        ld.bbox[MBbox.BOXBOTTOM] = 1000 * Fixed.FRACUNIT;
        ld.bbox[MBbox.BOXTOP] = 2000 * Fixed.FRACUNIT;

        // Set tmbbox to be far to the left of the line.
        PMap.tmbbox[MBbox.BOXLEFT] = -100 * Fixed.FRACUNIT;
        PMap.tmbbox[MBbox.BOXRIGHT] = 0;
        PMap.tmbbox[MBbox.BOXBOTTOM] = -100 * Fixed.FRACUNIT;
        PMap.tmbbox[MBbox.BOXTOP] = 0;

        // Bbox doesn't overlap → returns true (no collision).
        assertTrue(PMap.pitCheckLine(ld));
    }

    // --- ChangeSector stub still works ---

    @Test
    void pChangeSectorStubReturnsOverrideValue() {
        PMap.changeSectorResult = false;
        assertFalse(PMap.pChangeSector(new Sector(), false));

        PMap.changeSectorResult = true;
        assertTrue(PMap.pChangeSector(new Sector(), false));
    }

    // ----- Globals match C constants -----

    @Test
    void maxSpecialCrossMatchesCHeader() {
        assertEquals(8, PMap.MAXSPECIALCROSS);
        assertEquals(8, PMap.spechit.length);
    }

    // ----- P_HitSlideLine -----

    @Test
    void hitSlideLineHorizontalZerosYMove() {
        Line ld = new Line();
        ld.slopetype = Slopetype.ST_HORIZONTAL;

        PMap.tmxmove = 100 * Fixed.FRACUNIT;
        PMap.tmymove = 50 * Fixed.FRACUNIT;

        PMap.pHitSlideLine(ld);

        // Horizontal walls flatten the y component. x is untouched.
        assertEquals(100 * Fixed.FRACUNIT, PMap.tmxmove);
        assertEquals(0, PMap.tmymove);
    }

    @Test
    void hitSlideLineVerticalZerosXMove() {
        Line ld = new Line();
        ld.slopetype = Slopetype.ST_VERTICAL;

        PMap.tmxmove = 100 * Fixed.FRACUNIT;
        PMap.tmymove = 50 * Fixed.FRACUNIT;

        PMap.pHitSlideLine(ld);

        assertEquals(0, PMap.tmxmove);
        assertEquals(50 * Fixed.FRACUNIT, PMap.tmymove);
    }

    // Diagonal slide: just verify the call doesn't crash and that
    // both components stay within [-tmxmove, +tmxmove] order. The
    // exact angles depend on R_PointToAngle2's tantoangle table fuzz
    // — the C and Java answers are bit-identical.
    @Test
    void hitSlideLineDiagonalDoesNotCrashAndProducesFiniteResult() {
        Line ld = new Line();
        ld.v1 = new Vertex(); ld.v1.x = 0;             ld.v1.y = 0;
        ld.v2 = new Vertex(); ld.v2.x = 100 * Fixed.FRACUNIT; ld.v2.y = 100 * Fixed.FRACUNIT;
        ld.dx = ld.v2.x - ld.v1.x;
        ld.dy = ld.v2.y - ld.v1.y;
        ld.slopetype = Slopetype.ST_POSITIVE; // diagonal

        PMap.slidemo = new Mobj();
        PMap.slidemo.x = -10 * Fixed.FRACUNIT;
        PMap.slidemo.y = 0;

        PMap.tmxmove = 50 * Fixed.FRACUNIT;
        PMap.tmymove = -50 * Fixed.FRACUNIT;

        PMap.pHitSlideLine(ld);

        // Components shouldn't overflow; they get scaled by the
        // cosine of the move-vs-wall angle.
        assertTrue(Math.abs(PMap.tmxmove) <= 50 * Fixed.FRACUNIT);
        assertTrue(Math.abs(PMap.tmymove) <= 50 * Fixed.FRACUNIT);
    }

    // ----- P_ThingHeightClip -----

    // Mobj on the floor: rises with the floor when the sector floor
    // changes height.
    @Test
    void thingHeightClipOnFloorRidesNewFloorHeight() {
        Sector sec = openWorld(50 * Fixed.FRACUNIT, 250 * Fixed.FRACUNIT);

        Mobj m = makeMobj(0, 0, 16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        m.z = 50 * Fixed.FRACUNIT;     // matches floorz → "on floor"
        m.floorz = 50 * Fixed.FRACUNIT;
        m.ceilingz = 250 * Fixed.FRACUNIT;
        linkMobj(m);

        // Raise the sector floor.
        sec.floorheight = 80 * Fixed.FRACUNIT;

        boolean fits = PMap.pThingHeightClip(m);

        assertTrue(fits);
        assertEquals(80 * Fixed.FRACUNIT, m.floorz);
        assertEquals(80 * Fixed.FRACUNIT, m.z);  // rode up with floor
    }

    // Floating mobj (z != floorz): ceiling drop pushes z down so it fits.
    @Test
    void thingHeightClipFloatingMobjDuckedUnderLowCeiling() {
        Sector sec = openWorld(0, 200 * Fixed.FRACUNIT);

        Mobj m = makeMobj(0, 0, 16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        m.z = 100 * Fixed.FRACUNIT;    // floating (not on floor)
        m.floorz = 0;
        m.ceilingz = 200 * Fixed.FRACUNIT;
        linkMobj(m);

        // Drop the ceiling so it almost crushes.
        sec.ceilingheight = 150 * Fixed.FRACUNIT;

        boolean fits = PMap.pThingHeightClip(m);

        assertTrue(fits);
        // z + height (100 + 56 = 156) > new ceiling (150), so z is
        // adjusted to ceilingz - height = 94.
        assertEquals(150 * Fixed.FRACUNIT - 56 * Fixed.FRACUNIT, m.z);
    }

    // ----- P_UseLines / PTR_UseTraverse -----
    //
    // Setup: player facing +x. A two-sided special-line wall is
    // placed between the player and a tagged sector. Pressing "use"
    // should walk pPathTraverse forward, hit the line, and dispatch
    // P_UseSpecialLine.

    // PTR_UseTraverse on a non-special line with no opening plays
    // `sfx_noway` and stops the walk.
    @Test
    void ptrUseTraverseStopsOnSolidNonSpecialWall() {
        Sector sec = openWorld(0, 200 * Fixed.FRACUNIT);
        // One-sided wall (back=null): pLineOpening sets openrange=0.
        Line ld = installLine(sec, null,
                -50 * Fixed.FRACUNIT, 0,
                 50 * Fixed.FRACUNIT, 0);
        ld.special = 0; // explicitly non-special

        PMap.usething = makeMobj(0, -30 * Fixed.FRACUNIT,
                16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        PMap.usething.player = new Player();

        Intercept in = new Intercept();
        in.isaline = true;
        in.line = ld;
        in.frac = Fixed.FRACUNIT / 2;

        boolean rtn = PMap.ptrUseTraverse(in);
        // openrange is 0 for one-sided line → stop the walk.
        assertFalse(rtn);
    }

    // PTR_UseTraverse on a non-special two-sided line lets the walk
    // continue (returns true).
    @Test
    void ptrUseTraverseContinuesPastNonSpecialOpenLine() {
        Sector front = openWorld(0, 200 * Fixed.FRACUNIT);
        Sector back = new Sector();
        back.floorheight = 0;
        back.ceilingheight = 200 * Fixed.FRACUNIT;

        Line ld = installLine(front, back,
                -50 * Fixed.FRACUNIT, 0,
                 50 * Fixed.FRACUNIT, 0);
        ld.special = 0;

        PMap.usething = makeMobj(0, -30 * Fixed.FRACUNIT,
                16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        PMap.usething.player = new Player();

        Intercept in = new Intercept();
        in.isaline = true;
        in.line = ld;

        assertTrue(PMap.ptrUseTraverse(in));
    }

    // PTR_UseTraverse on a special line dispatches to PSwitch and
    // stops the walk (one special per use).
    @Test
    void ptrUseTraverseDispatchesSpecialLineAndStops() {
        Sector front = openWorld(0, 200 * Fixed.FRACUNIT);
        Sector back = new Sector();
        back.floorheight = 0;
        back.ceilingheight = 200 * Fixed.FRACUNIT;

        Line ld = installLine(front, back,
                -50 * Fixed.FRACUNIT, 0,
                 50 * Fixed.FRACUNIT, 0);
        ld.special = 11; // Exit Level → flips GGame.gameaction

        PMap.usething = makeMobj(0, -30 * Fixed.FRACUNIT,
                16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        PMap.usething.player = new Player();

        // Reset gameaction so we can detect dispatch.
        doom.system.GGame.gameaction = doom.system.GGame.ga_nothing;

        Intercept in = new Intercept();
        in.isaline = true;
        in.line = ld;

        boolean rtn = PMap.ptrUseTraverse(in);

        assertFalse(rtn); // stops after dispatching one special
        assertEquals(doom.system.GGame.ga_completed,
                doom.system.GGame.gameaction);
    }

    // P_UseLines walks forward USERANGE units along the player's
    // facing and dispatches the first special line it hits.
    @Test
    void useLinesActivatesSpecialLineInFront() {
        Sector front = openWorld(0, 200 * Fixed.FRACUNIT);
        Sector back = new Sector();
        back.floorheight = 0;
        back.ceilingheight = 200 * Fixed.FRACUNIT;

        // Wall in front of the player (player at (-30, 0), facing +x).
        // Vertices ordered so that the v1→v2 vector has its right-hand
        // normal pointing toward the player (his side becomes the
        // line's front, which is required for special activation).
        Line ld = installLine(front, back,
                0,  50 * Fixed.FRACUNIT,
                0, -50 * Fixed.FRACUNIT);
        ld.special = 11; // Exit Level

        Mobj mo = makeMobj(-30 * Fixed.FRACUNIT, 0,
                16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        mo.angle = 0; // facing +x
        Player p = new Player();
        p.mo = mo;
        mo.player = p;

        doom.system.GGame.gameaction = doom.system.GGame.ga_nothing;

        PMap.pUseLines(p);

        // The use raycast hit the special line.
        assertEquals(doom.system.GGame.ga_completed,
                doom.system.GGame.gameaction);
    }

    // USERANGE constant matches the C macro.
    @Test
    void useRangeMatchesCMacro() {
        assertEquals(64 * Fixed.FRACUNIT, PMap.USERANGE);
    }

    // Mobj doesn't fit: returns false.
    @Test
    void thingHeightClipReturnsFalseWhenSqueezed() {
        Sector sec = openWorld(0, 200 * Fixed.FRACUNIT);

        Mobj m = makeMobj(0, 0, 16 * Fixed.FRACUNIT, 56 * Fixed.FRACUNIT);
        m.z = 0;
        m.floorz = 0;
        m.ceilingz = 200 * Fixed.FRACUNIT;
        linkMobj(m);

        // Squeeze ceiling below the mobj's height.
        sec.ceilingheight = 30 * Fixed.FRACUNIT; // gap < 56

        assertFalse(PMap.pThingHeightClip(m));
    }

    // --- PIT_CheckThing branches (now wired through PInter) ---

    // A picker (MF_PICKUP) walking onto an MF_SPECIAL pickup
    // triggers P_TouchSpecialThing, which should remove the item +
    // bump the player's stats.
    @Test
    void pitCheckThingPickupRemovesItem() {
        Sector sec = openWorld(0, 200 * Fixed.FRACUNIT);

        // Spawn a soulsphere (MF_SPECIAL|MF_COUNTITEM, doomednum 2013).
        Mobj item = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_MISC12);
        // Spawn the player at the same spot as the picker.
        Mobj picker = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_PLAYER);
        // Picker needs a Player back-pointer for pTouchSpecialThing
        // (sets player.message, etc.).
        Player p = new Player();
        p.health = 100;
        Mobj playerMobj = picker;
        playerMobj.player = p;
        p.mo = playerMobj;
        // The MF_PICKUP bit on tmflags drives the actual touch.
        // pCheckPosition copies thing.flags → tmflags, so it's enough
        // to ensure picker.flags carries MF_PICKUP (player flags
        // already include it from MT_PLAYER).
        assertTrue((picker.flags & Mobj.MF_PICKUP) != 0,
                "MT_PLAYER should have MF_PICKUP");

        PMap.tmthing = picker;
        PMap.tmflags = picker.flags;
        PMap.tmx = 0;
        PMap.tmy = 0;

        // pitCheckThing(item) — picker steps onto item.
        boolean kept = PMap.pitCheckThing(item);

        // Item is non-solid → returns true (no block).
        assertTrue(kept);
        // Item was removed by P_TouchSpecialThing.
        assertTrue(item.removed);
        // Player got the soulsphere message.
        assertEquals(PInter.GOTSUPER, p.message);
    }

    // A missile flying past at unrelated z → no damage, no stop.
    @Test
    void pitCheckThingMissileFliesOverTarget() {
        openWorld(0, 200 * Fixed.FRACUNIT);

        Mobj target = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_POSSESSED);
        int beforeHealth = target.health;

        // Missile origin and target type.
        Mobj missile = PMobj.pSpawnMobj(0, 0, 200 * Fixed.FRACUNIT,
                Info.MT_TROOPSHOT);
        missile.z = target.z + target.height + 100 * Fixed.FRACUNIT;

        PMap.tmthing = missile;
        PMap.tmflags = missile.flags;
        PMap.tmx = 0;
        PMap.tmy = 0;

        boolean kept = PMap.pitCheckThing(target);
        assertTrue(kept, "missile flew overhead → should not stop");
        assertEquals(beforeHealth, target.health);
    }

    // Missile hits shootable → P_DamageMobj fires, traversal stops.
    @Test
    void pitCheckThingMissileHitsShootable() {
        openWorld(0, 200 * Fixed.FRACUNIT);

        Mobj target = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_POSSESSED);
        int beforeHealth = target.health;

        Mobj missile = PMobj.pSpawnMobj(0, 0, target.z + 16 * Fixed.FRACUNIT,
                Info.MT_TROOPSHOT);
        // No friendly-fire same-species target.
        missile.target = null;

        PMap.tmthing = missile;
        PMap.tmflags = missile.flags;
        PMap.tmx = 0;
        PMap.tmy = 0;

        boolean kept = PMap.pitCheckThing(target);
        assertFalse(kept, "missile hit → traversal stops");
        // Damage was applied (3..24 from random*8+1 * info.damage=3).
        assertTrue(target.health < beforeHealth,
                "expected health drop, was " + target.health);
    }

    // --- P_RadiusAttack / PIT_RadiusAttack ---

    // A spot bombing a victim within range with clear LOS deals
    // (bombdamage - dist) damage. With dist clamped to 0 (radius
    // covers the victim) the full damage applies.
    @Test
    void radiusAttackDamagesVictimInRange() {
        openWorld(0, 200 * Fixed.FRACUNIT);

        // Victim is far enough away to be in the iteration range but
        // close enough that dist << bombdamage. Place at the bombspot
        // so dist = 0 → full damage.
        Mobj victim = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_POSSESSED);
        // Need REJECT to be all-zero so pCheckSight passes the bit
        // check (one byte covers the 1-sector world).
        PSetup.rejectmatrix = new byte[] { 0 };

        Mobj source = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_PLAYER);
        Mobj bomb   = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_ROCKET);
        int beforeHealth = victim.health;

        PMap.pRadiusAttack(bomb, source, 50);

        assertTrue(victim.health < beforeHealth,
                "expected damage, victim health was " + victim.health);
    }

    // CYBORG and SPIDER take no concussion damage.
    @Test
    void radiusAttackSparesCyborg() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        PSetup.rejectmatrix = new byte[] { 0 };

        Mobj cyborg = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_CYBORG);
        Mobj source = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_PLAYER);
        Mobj bomb   = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_ROCKET);
        int beforeHealth = cyborg.health;

        PMap.pRadiusAttack(bomb, source, 100);

        assertEquals(beforeHealth, cyborg.health,
                "CYBORG should be immune to splash");
    }

    // PIT_RadiusAttack returns true (keep iterating) for all paths.
    @Test
    void pitRadiusAttackAlwaysKeepsIterating() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        PSetup.rejectmatrix = new byte[] { 0 };

        Mobj nonShoot = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_TFOG);
        PMap.bombspot = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_ROCKET);
        PMap.bombsource = null;
        PMap.bombdamage = 100;

        // Non-shootable mobj → quick true return.
        assertTrue(PMap.pitRadiusAttack(nonShoot));
    }

    // --- P_AimLineAttack / P_LineAttack: minimal sanity ---

    // pAimLineAttack with no target in range returns 0 (linetarget
    // stays null).
    @Test
    void aimLineAttackNoTargetReturnsZero() {
        openWorld(0, 200 * Fixed.FRACUNIT);

        Mobj shooter = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_PLAYER);
        // Empty world — nothing to aim at.
        int slope = PMap.pAimLineAttack(shooter, 0,
                /*distance*/ 1024 * Fixed.FRACUNIT);

        assertEquals(0, slope);
        assertEquals(null, PMap.linetarget);
    }

    // pLineAttack with damage=0 is a test trace — leaves linetarget
    // pointing at any aimable mobj (when used after pAimLineAttack
    // setup, linetarget is whatever the C path traverse left).
    // This test just verifies the call doesn't throw on an empty
    // world and shootthing/attackrange globals get set.
    @Test
    void lineAttackSetsGlobalsAndDoesNotThrow() {
        openWorld(0, 200 * Fixed.FRACUNIT);

        Mobj shooter = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_PLAYER);
        PMap.pLineAttack(shooter, 0,
                /*distance*/ 256 * Fixed.FRACUNIT,
                /*slope*/ 0,
                /*damage*/ 0);

        assertEquals(shooter, PMap.shootthing);
        assertEquals(256 * Fixed.FRACUNIT, PMobj.attackrange);
        assertEquals(0, PMap.la_damage);
    }
}
