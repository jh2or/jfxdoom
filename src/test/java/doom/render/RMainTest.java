package doom.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import doom.fixed.Fixed;
import doom.play.PSetup;
import doom.wad.WWad;
import doom.wad.ZZone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RMainTest {

    private static final Path WAD_PATH = resolveWad();

    private static Path resolveWad() {
        for (String candidate : new String[] {
                "reference-wads/DOOM.WAD",
                "reference-wads/doom.wad",
                "reference-wads/doom1.wad",
                "reference-wads/DOOM1.WAD" }) {
            if (Files.exists(Paths.get(candidate))) {
                return Paths.get(candidate).toAbsolutePath();
            }
        }
        return null;
    }

    @BeforeAll
    void ensureWadPresent() {
        // The pure-math tests don't need a WAD, but the BSP tests do.
        // Skip the integration tests if no WAD is present; pure-math
        // tests still run via separate classes' init paths if needed.
    }

    @BeforeEach
    void resetPSetup() {
        PSetup.numnodes = 0;
        PSetup.nodes = null;
        PSetup.numsubsectors = 0;
        PSetup.subsectors = null;
    }

    // --- R_PointOnSide ---
    //
    // Same partition shape as PMaputl.pPointOnLineSide, but on a
    // node_t. Tests cover the vertical (dx=0) / horizontal (dy=0) /
    // diagonal axes.

    @Test
    void pointOnSideVerticalNodeWithPositiveDy() {
        // Partition x=10, going +y.
        Node n = new Node();
        n.x = 10; n.y = 0; n.dx = 0; n.dy = 100;

        // x <= node.x → dy>0 → side 1.
        assertEquals(1, RMain.rPointOnSide(5, 50, n));
        // x >  node.x → dy<0 → side 0.
        assertEquals(0, RMain.rPointOnSide(15, 50, n));
    }

    @Test
    void pointOnSideHorizontalNodeWithPositiveDx() {
        // Partition y=10, going +x.
        Node n = new Node();
        n.x = 0; n.y = 10; n.dx = 100; n.dy = 0;

        // y <= node.y → dx<0 → 0.
        assertEquals(0, RMain.rPointOnSide(50, 5, n));
        // y >  node.y → dx>0 → 1.
        assertEquals(1, RMain.rPointOnSide(50, 15, n));
    }

    @Test
    void pointOnSideDiagonalUsesSlowPath() {
        // Diagonal partition (0,0) -> (FRACUNIT, FRACUNIT).
        Node n = new Node();
        n.x = 0; n.y = 0; n.dx = Fixed.FRACUNIT; n.dy = Fixed.FRACUNIT;

        // (FRACUNIT, 0) — below the line going up-right → front (0).
        assertEquals(0, RMain.rPointOnSide(Fixed.FRACUNIT, 0, n));
        // (0, FRACUNIT) — above the line → back (1).
        assertEquals(1, RMain.rPointOnSide(0, Fixed.FRACUNIT, n));
    }

    // --- R_PointOnSegSide ---

    @Test
    void pointOnSegSideMirrorsPointOnLineSideStructure() {
        // Build a horizontal seg from (0, 10) to (100, 10).
        Seg s = new Seg();
        s.v1 = new Vertex(); s.v1.x = 0;   s.v1.y = 10;
        s.v2 = new Vertex(); s.v2.x = 100; s.v2.y = 10;

        // Below (y<10) → front (0).
        assertEquals(0, RMain.rPointOnSegSide(50, 5, s));
        // Above (y>10) → back (1).
        assertEquals(1, RMain.rPointOnSegSide(50, 15, s));
    }

    // --- R_PointInSubsector — synthetic ---

    // No-nodes corner case: single subsector at index 0.
    @Test
    void pointInSubsectorReturnsSingleSubsectorWhenNoNodes() {
        Subsector ss = new Subsector();
        PSetup.subsectors = new Subsector[] { ss };
        PSetup.numsubsectors = 1;
        PSetup.numnodes = 0;
        PSetup.nodes = new Node[0];

        assertSame(ss, RMain.rPointInSubsector(0, 0));
        assertSame(ss, RMain.rPointInSubsector(Fixed.FRACUNIT * 1000,
                                                -Fixed.FRACUNIT * 1000));
    }

    // 2-leaf BSP: one root node with two subsector children. Vertical
    // partition at x=0 going +y means front = right of x=0, back = left.
    @Test
    void pointInSubsectorDescendsToCorrectLeaf() {
        Subsector ssFront = new Subsector();
        Subsector ssBack = new Subsector();
        PSetup.subsectors = new Subsector[] { ssFront, ssBack };
        PSetup.numsubsectors = 2;

        Node root = new Node();
        root.x = 0; root.y = 0; root.dx = 0; root.dy = Fixed.FRACUNIT;
        // children[0] = front subsector (index 0 | NF_SUBSECTOR)
        // children[1] = back  subsector (index 1 | NF_SUBSECTOR)
        root.children[0] = 0 | Node.NF_SUBSECTOR;
        root.children[1] = 1 | Node.NF_SUBSECTOR;
        PSetup.nodes = new Node[] { root };
        PSetup.numnodes = 1;

        // Right of x=0 (positive x) — vertical with dy>0 picks back (1)
        // when x>node.x. Wait — re-reading: vertical line dx=0 with
        // dy>0: x<=node.x returns dy>0=1, x>node.x returns dy<0=0.
        // So x=10 → side 0 (front).
        assertSame(ssFront, RMain.rPointInSubsector(10, 0));
        // x=-10 → side 1 (back).
        assertSame(ssBack, RMain.rPointInSubsector(-10, 0));
    }

    // --- R_PointInSubsector — integration with real WAD geometry ---

    @Test
    void pointInSubsectorHitsKnownE1M1Geometry() throws IOException {
        assumeTrue(WAD_PATH != null,
                "Skipping E1M1 BSP test — no WAD in reference-wads/");

        ZZone.zInit();
        WWad.resetForTests();
        RData.resetForTests();
        WWad.wInitFile(WAD_PATH.toString());
        RData.rInitData();
        PSetup.pSetupLevelGeometry(WWad.wGetNumForName("E1M1"));

        // E1M1 player start (Hangar) — DoomEd thing 1 lives at
        // approximately (1056, -3616) in unscaled map units. We don't
        // load THINGS yet, so just verify the BSP returns *some*
        // subsector for any in-bounds point and that the subsector
        // points back to a real sector.
        int x = 1056 << Fixed.FRACBITS;
        int y = -3616 << Fixed.FRACBITS;

        Subsector ss = RMain.rPointInSubsector(x, y);
        assertNotNull(ss);
        assertNotNull(ss.sector);

        // Sample a few more arbitrary points; all should resolve.
        for (int sample = 0; sample < 8; sample++) {
            int sx = (sample * 200) << Fixed.FRACBITS;
            int sy = (sample * 200) << Fixed.FRACBITS;
            Subsector s = RMain.rPointInSubsector(sx, sy);
            assertNotNull(s, "sample " + sample);
            assertNotNull(s.sector, "sample " + sample + " sector");
        }
    }

    // NF_SUBSECTOR bit value preserved.
    @Test
    void nfSubsectorBitMatchesC() {
        assertEquals(0x8000, Node.NF_SUBSECTOR);
    }

    // ----- R_PointToAngle / R_PointToAngle2 / R_PointToDist -----
    //
    // Angle is BAM-encoded: 0 = +x, ANG90 = +y, ANG180 = -x,
    // ANG270 = -y. C's angle_t is unsigned; Java holds the same bit
    // pattern in a signed int.

    @Test
    void pointToAngleSamePointIsZero() {
        RMain.viewx = 100; RMain.viewy = 200;
        assertEquals(0, RMain.rPointToAngle(100, 200));
    }

    @Test
    void pointToAnglePositiveXAxisIsZero() {
        RMain.viewx = 0; RMain.viewy = 0;
        // Far along +x: angle 0 (octant 0 with y=0).
        assertEquals(0, RMain.rPointToAngle(Fixed.FRACUNIT * 1000, 0));
    }

    @Test
    void pointToAnglePositiveYAxisIsAng90() {
        RMain.viewx = 0; RMain.viewy = 0;
        int a = RMain.rPointToAngle(0, Fixed.FRACUNIT * 1000);
        // Octant 1 returns ANG90-1-tantoangle[slopeDiv(x,y)]. x=0,
        // slopeDiv(0, big) → 0 (since (0<<3)/(big>>8) = 0), and
        // tantoangle[0] = 0 (atan(0/SLOPERANGE)). So a = ANG90 - 1.
        assertEquals(Tables.ANG90 - 1, a);
    }

    @Test
    void pointToAngleNegativeXAxisIsAng180() {
        RMain.viewx = 0; RMain.viewy = 0;
        int a = RMain.rPointToAngle(-Fixed.FRACUNIT * 1000, 0);
        // Octant 3 (x<0, y>=0, x>y): ANG180 - 1 - tantoangle[0] = ANG180 - 1.
        assertEquals(Tables.ANG180 - 1, a);
    }

    @Test
    void pointToAngleNegativeYAxisIsAng270() {
        RMain.viewx = 0; RMain.viewy = 0;
        int a = RMain.rPointToAngle(0, -Fixed.FRACUNIT * 1000);
        // Octant 7 (x>=0, y<0, x<=y after negation):
        //   ANG270 + tantoangle[slopeDiv(0, big)] = ANG270 + 0.
        // Note the +x/+y/-x branches each hit ANG{0,90,180,270}-1
        // because they go through octants where the formula is
        // (ANGn - 1 - tantoangle[...]); -y is the asymmetric one
        // and lands exactly on ANG270.
        assertEquals(Tables.ANG270, a);
    }

    // 45-degree line in octant 0: x==y exactly. SlopeDiv(y,x) ends
    // at SLOPERANGE → tantoangle[SLOPERANGE] = ANG45 (the +1 entry
    // exists exactly for this case).
    @Test
    void pointToAngleFortyFiveDegreesIsAng45() {
        RMain.viewx = 0; RMain.viewy = 0;
        // x>y branch needs strict greater. Use a 1-unit diagonal so
        // the slopeDiv hits SLOPERANGE-1 region (still close to ANG45).
        int a = RMain.rPointToAngle(Fixed.FRACUNIT, Fixed.FRACUNIT - 1);
        // Should be very close to ANG45.
        // Allow a generous tolerance since slopeDiv truncates.
        long diff = Math.abs((a & 0xFFFFFFFFL) - (Tables.ANG45 & 0xFFFFFFFFL));
        assertTrue(diff < (1L << 24), "angle " + a + " not near ANG45");
    }

    // R_PointToAngle2 anchors viewx/viewy and computes from the
    // anchor to the second point.
    @Test
    void pointToAngle2AnchorsAtSourceAndOverwritesView() {
        RMain.viewx = 5; RMain.viewy = 5; // poison

        int a = RMain.rPointToAngle2(100, 100, 1100, 100); // due +x
        assertEquals(0, a);

        // Side-effect: viewx/viewy now equal the source.
        assertEquals(100, RMain.viewx);
        assertEquals(100, RMain.viewy);
    }

    // R_PointToDist: a point straight along +x at distance D from
    // the view returns D (within fixed-point fuzz).
    @Test
    void pointToDistAxisAlignedReturnsDistance() {
        RMain.viewx = 0; RMain.viewy = 0;
        int dist = RMain.rPointToDist(Fixed.FRACUNIT * 100, 0);
        // The tantoangle/finesine path has fuzz at the cosine
        // boundary: finesine[2048] is almost-but-not-exactly FRACUNIT
        // due to table quantization. Tolerate 0.01%.
        long ideal = (long) Fixed.FRACUNIT * 100;
        long diff = Math.abs((long) dist - ideal);
        assertTrue(diff < ideal / 10000,
                "dist " + dist + " not near 100*FRACUNIT (diff=" + diff + ")");
    }

    // 3-4-5 triangle: at (3, 4) from origin, distance is 5 (in fixed).
    @Test
    void pointToDistThreeFourFive() {
        RMain.viewx = 0; RMain.viewy = 0;
        int dist = RMain.rPointToDist(Fixed.FRACUNIT * 3, Fixed.FRACUNIT * 4);
        long ideal = (long) Fixed.FRACUNIT * 5;
        long diff = Math.abs((long) dist - ideal);
        // The tantoangle/finesine path has more fuzz at small inputs;
        // allow ~1/256 of the value.
        assertTrue(diff < ideal / 256,
                "dist " + dist + " not near 5*FRACUNIT (off by " + diff + ")");
    }
}
