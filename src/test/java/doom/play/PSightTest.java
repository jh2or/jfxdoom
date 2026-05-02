package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import doom.fixed.Fixed;
import doom.render.Node;
import doom.render.RData;
import doom.render.Sector;
import doom.render.Subsector;
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
class PSightTest {

    // --- P_DivlineSide / P_DivlineSideNode unit tests (pure math) ---

    @Test
    void divlineSideAxisAlignedVerticalReturnsOnFront0Back1() {
        Divline node = new Divline();
        // Vertical partition at x=0 going +y (dy>0). C: dx==0 branch.
        node.x = 0; node.y = 0; node.dx = 0; node.dy = 100;

        // Right of partition (x>0) with dy>0 → "back" (return value 0
        // here is per the C: x>node.x → return node.dy < 0 → false → 0).
        assertEquals(0, PSight.pDivlineSide(50, 0, node));
        // Left of partition with dy>0 → "front" (return node.dy > 0 → 1).
        assertEquals(1, PSight.pDivlineSide(-50, 0, node));
        // On the partition.
        assertEquals(2, PSight.pDivlineSide(0, 0, node));
    }

    @Test
    void divlineSideHorizontalAxisAligned() {
        Divline node = new Divline();
        // Horizontal partition at y=0 going +x.
        node.x = 0; node.y = 0; node.dx = 100; node.dy = 0;

        // C lines 76–85: dy==0 branch with `if (x==node.y)` (sic).
        // Above (y>0): return node.dx > 0 → 1.
        assertEquals(1, PSight.pDivlineSide(50, 50, node));
        // Below (y<0): return node.dx < 0 → 0.
        assertEquals(0, PSight.pDivlineSide(50, -50, node));
    }

    @Test
    void divlineSideDiagonalSplitsByCrossProduct() {
        Divline node = new Divline();
        // 45° partition through origin from (0,0) to (FRACUNIT,FRACUNIT).
        node.x = 0; node.y = 0;
        node.dx = Fixed.FRACUNIT;
        node.dy = Fixed.FRACUNIT;

        // Above the line (smaller dx, bigger dy) → back.
        assertEquals(1, PSight.pDivlineSide(0, Fixed.FRACUNIT, node));
        // Below the line.
        assertEquals(0, PSight.pDivlineSide(Fixed.FRACUNIT, 0, node));
        // Exactly on the line.
        assertEquals(2,
                PSight.pDivlineSide(Fixed.FRACUNIT / 2, Fixed.FRACUNIT / 2, node));
    }

    // pDivlineSideNode reads the same first four fields off a Node
    // (Java replacement for the C reinterpret cast). Output must
    // match pDivlineSide given identical x/y/dx/dy.
    @Test
    void divlineSideNodeMatchesDivlineVariant() {
        Node n = new Node();
        n.x = 0;
        n.y = 0;
        n.dx = Fixed.FRACUNIT;
        n.dy = Fixed.FRACUNIT;

        Divline d = new Divline();
        d.x = n.x; d.y = n.y; d.dx = n.dx; d.dy = n.dy;

        for (int x = -2 * Fixed.FRACUNIT; x <= 2 * Fixed.FRACUNIT;
                x += Fixed.FRACUNIT / 4) {
            for (int y = -2 * Fixed.FRACUNIT; y <= 2 * Fixed.FRACUNIT;
                    y += Fixed.FRACUNIT / 4) {
                assertEquals(PSight.pDivlineSide(x, y, d),
                        PSight.pDivlineSideNode(x, y, n),
                        "mismatch at (" + x + "," + y + ")");
            }
        }
    }

    // --- P_InterceptVector2 (pure math) ---

    // Two perpendicular divlines: v1 along +x, v2 along +y crossing
    // at (FRACUNIT, 0). The intercept frac along v2 (the first arg)
    // should land at v2.y position 0 → frac == 0 since v2.y = 0 on
    // arrival (parallel-to-axis case yields a known answer).
    @Test
    void interceptVector2PerpendicularLines() {
        // v1: from (0, 0) +x direction.
        Divline v1 = new Divline();
        v1.x = 0; v1.y = 0;
        v1.dx = Fixed.FRACUNIT; v1.dy = 0;

        // v2: from (FRACUNIT, -FRACUNIT) +y direction.
        Divline v2 = new Divline();
        v2.x = Fixed.FRACUNIT;
        v2.y = -Fixed.FRACUNIT;
        v2.dx = 0;
        v2.dy = Fixed.FRACUNIT;

        // The crossing point is (FRACUNIT, 0). Along v2's parameter,
        // that's frac = 1.0 in fixed, i.e. FRACUNIT.
        int frac = PSight.pInterceptVector2(v2, v1);
        assertEquals(Fixed.FRACUNIT, frac);
    }

    // Parallel divlines (zero determinant) return 0 per the C
    // bail-out (the I_Error is commented out in the C).
    @Test
    void interceptVector2ParallelReturnsZero() {
        Divline v1 = new Divline();
        v1.x = 0; v1.y = 0; v1.dx = Fixed.FRACUNIT; v1.dy = 0;

        Divline v2 = new Divline();
        v2.x = 0; v2.y = Fixed.FRACUNIT;
        v2.dx = Fixed.FRACUNIT; v2.dy = 0;

        assertEquals(0, PSight.pInterceptVector2(v2, v1));
    }

    // --- Synthetic two-sector REJECT short-circuit ---

    private Mobj newMobj(Sector sec, Subsector ss, int x, int y, int z) {
        Mobj m = new Mobj();
        m.x = x; m.y = y; m.z = z;
        m.height = 56 * Fixed.FRACUNIT;
        m.subsector = ss;
        return m;
    }

    @BeforeEach
    void resetWorldForUnit() {
        // Used by REJECT tests; the WAD-based tests rebuild it via
        // pSetupLevelGeometry below.
        PSight.sightcounts[0] = 0;
        PSight.sightcounts[1] = 0;
    }

    @Test
    void checkSightShortCircuitsOnRejectBit() {
        // Two-sector world. REJECT sets s1↔s2 to "never see" so
        // P_CheckSight returns false without descending the BSP.
        Sector a = new Sector();
        Sector b = new Sector();
        Subsector ssA = new Subsector(); ssA.sector = a;
        Subsector ssB = new Subsector(); ssB.sector = b;
        PSetup.sectors = new Sector[] { a, b };
        PSetup.numsectors = 2;
        // 2x2 bits = 4 bits — single byte covers it.
        // bit (s1*numsectors + s2): for s1=0,s2=1 that's bit 1.
        PSetup.rejectmatrix = new byte[] { 0b0000_0010 };

        Mobj t1 = newMobj(a, ssA, 0, 0, 0);
        Mobj t2 = newMobj(b, ssB, 100, 100, 0);

        // No BSP needed — should bail in the REJECT branch.
        boolean visible = PSight.pCheckSight(t1, t2);
        assertFalse(visible);
        assertEquals(1, PSight.sightcounts[0]);
        assertEquals(0, PSight.sightcounts[1]);
    }

    // --- Real-WAD integration (E1M1) ---
    // The REJECT-clear path needs valid sectors[]/segs[]/nodes[]/
    // subsectors[]/lines[] arrays. Use E1M1 like PSetupTest.

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
        assumeTrue(WAD_PATH != null,
                "Skipping PSightTest WAD path — no WAD in reference-wads/");
    }

    private void loadE1M1() throws IOException {
        ZZone.zInit();
        WWad.resetForTests();
        RData.resetForTests();
        WWad.wInitFile(WAD_PATH.toString());
        RData.rInitData();
        int lump = WWad.wGetNumForName("E1M1");
        PSetup.pSetupLevelGeometry(lump);
    }

    // Self-sight: t1 == t2 → REJECT bit on the s×s diagonal is
    // typically 0, BSP walk passes. Verifies the full pipeline runs.
    @Test
    void checkSightOnSelfSucceeds() throws IOException {
        loadE1M1();

        // Pick the first subsector that has a real sector (every one
        // should after pSetupLevelGeometry).
        Subsector ss = PSetup.subsectors[0];
        Sector sec = ss.sector;

        Mobj t = newMobj(sec, ss, 0, 0, sec.floorheight + 32 * Fixed.FRACUNIT);
        // Place t1 inside the sector; we don't have the actual centroid
        // but the REJECT diagonal is normally clear and the BSP walk
        // for t==t both endpoints means strace.dx==strace.dy==0 — the
        // recursive walk picks one side and traverses that subsector
        // alone.
        // The same mobj acts as both endpoints.
        boolean ok = PSight.pCheckSight(t, t);
        assertTrue(ok, "self-sight should not be rejected");
    }
}
