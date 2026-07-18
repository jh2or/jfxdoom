package doom.render3d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import doom.play.PSetup;
import doom.render.RData;
import doom.render.Seg;
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

// Validates the BSP-clip polygon reconstruction (Fx3SubsectorPolys)
// against real WAD geometry: every subsector must come back as a
// closed convex polygon that contains all of its own segs. This is
// the load-bearing invariant of the experimental 3D renderer's
// floors/ceilings (3D_OVERHAUL.md, Phase 1). Headless — the class
// under test has no JavaFX dependencies.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fx3SubsectorPolysTest {

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
                "Skipping Fx3SubsectorPolysTest — no WAD in reference-wads/");
    }

    @BeforeEach
    void resetState() throws IOException {
        ZZone.zInit();
        WWad.resetForTests();
        RData.resetForTests();
        WWad.wInitFile(WAD_PATH.toString());
        RData.rInitData();
    }

    private void loadMap(String name) throws IOException {
        PSetup.pSetupLevelGeometry(WWad.wGetNumForName(name));
    }

    // Signed distance of (px,py) to the right of the directed line —
    // positive on side 0 (the subsector's interior side of a seg).
    private static double side0Dist(double ax, double ay, double dx, double dy,
                                    double px, double py) {
        double len = Math.hypot(dx, dy);
        return (dy * (px - ax) - dx * (py - ay)) / len;
    }

    // Point-in-convex-CCW-polygon with tolerance: inside means not
    // more than `tol` outside any edge. For a CCW polygon the
    // interior is on the LEFT of each directed edge, i.e. side0Dist
    // (right side) must be <= tol.
    private static boolean containsPoint(double[] poly, double px, double py,
                                         double tol) {
        int n = poly.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double d = side0Dist(poly[2 * i], poly[2 * i + 1],
                    poly[2 * j] - poly[2 * i], poly[2 * j + 1] - poly[2 * i + 1],
                    px, py);
            if (d > tol) return false;
        }
        return true;
    }

    @Test
    void e1m1EverySubsectorGetsAValidPolygon() throws IOException {
        loadMap("E1M1");
        double[][] polys = Fx3SubsectorPolys.buildAll();

        assertEquals(PSetup.numsubsectors, polys.length);
        int degenerate = 0;
        for (int i = 0; i < polys.length; i++) {
            assertNotNull(polys[i]);
            if (polys[i].length == 0) {
                degenerate++;
                continue;
            }
            assertTrue(polys[i].length >= 6,
                    "polygon with <3 vertices at subsector " + i);
            assertTrue(Fx3SubsectorPolys.signedArea(polys[i]) > 0,
                    "non-CCW/zero-area polygon at subsector " + i);
        }
        // Vanilla id maps reconstruct fully; allow nothing degenerate
        // on E1M1 so regressions are loud.
        assertEquals(0, degenerate, "degenerate subsector polygons on E1M1");
    }

    @Test
    void e1m1PolygonsContainTheirSegs() throws IOException {
        loadMap("E1M1");
        assertPolygonsContainSegs("E1M1");
    }

    @Test
    void allShareware1MapsReconstruct() throws IOException {
        for (int m = 1; m <= 9; m++) {
            String map = "E1M" + m;
            loadMap(map);
            assertPolygonsContainSegs(map);
        }
    }

    private void assertPolygonsContainSegs(String map) {
        double[][] polys = Fx3SubsectorPolys.buildAll();
        for (int i = 0; i < PSetup.numsubsectors; i++) {
            double[] poly = polys[i];
            Subsector ss = PSetup.subsectors[i];
            if (poly.length < 6) {
                // Empty is acceptable only for genuine slivers —
                // subsectors whose own segs span (near-)zero area
                // (rounded split vertices can flatten a tiny leaf,
                // e.g. E1M6 subsector 589: one 8-unit seg squashed
                // onto a diagonal linedef). Anything larger must
                // reconstruct.
                double[] cloud = new double[ss.numlines * 4];
                for (int s = 0; s < ss.numlines; s++) {
                    Seg seg = PSetup.segs[ss.firstline + s];
                    cloud[4 * s]     = seg.v1.x / 65536.0;
                    cloud[4 * s + 1] = seg.v1.y / 65536.0;
                    cloud[4 * s + 2] = seg.v2.x / 65536.0;
                    cloud[4 * s + 3] = seg.v2.y / 65536.0;
                }
                double segArea = Math.abs(Fx3SubsectorPolys.signedArea(
                        Fx3SubsectorPolys.convexHull(cloud)));
                assertTrue(segArea < 1.0,
                        map + ": degenerate polygon at subsector " + i
                        + " but segs span area " + segArea);
                continue;
            }
            for (int s = 0; s < ss.numlines; s++) {
                Seg seg = PSetup.segs[ss.firstline + s];
                double x1 = seg.v1.x / 65536.0, y1 = seg.v1.y / 65536.0;
                double x2 = seg.v2.x / 65536.0, y2 = seg.v2.y / 65536.0;
                // Split-fragment endpoints are builder-rounded to
                // integers and may sit up to ~0.71 units off the
                // exact linedef line the polygon is clipped to —
                // tolerance 1.0 absorbs that.
                assertTrue(containsPoint(poly, x1, y1, 1.0),
                        map + ": seg v1 outside polygon, subsector " + i);
                assertTrue(containsPoint(poly, x2, y2, 1.0),
                        map + ": seg v2 outside polygon, subsector " + i);
                assertTrue(containsPoint(poly, (x1 + x2) / 2, (y1 + y2) / 2, 1.0),
                        map + ": seg midpoint outside polygon, subsector " + i);
            }
        }
    }

    @Test
    void polygonAreasAreSane() throws IOException {
        loadMap("E1M1");
        double[][] polys = Fx3SubsectorPolys.buildAll();

        double total = 0;
        for (double[] poly : polys) {
            total += Fx3SubsectorPolys.signedArea(poly);
        }
        // E1M1's playable area is on the order of a few million
        // square map units; the polygons must cover something in a
        // plausible band (they exclude void space outside the map).
        assertTrue(total > 100_000, "implausibly small total area: " + total);

        // And no polygon exceeds the level bounding box.
        double minx = Double.MAX_VALUE, miny = Double.MAX_VALUE;
        double maxx = -Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
        for (int i = 0; i < PSetup.numvertexes; i++) {
            minx = Math.min(minx, PSetup.vertexes[i].x / 65536.0);
            maxx = Math.max(maxx, PSetup.vertexes[i].x / 65536.0);
            miny = Math.min(miny, PSetup.vertexes[i].y / 65536.0);
            maxy = Math.max(maxy, PSetup.vertexes[i].y / 65536.0);
        }
        double bboxArea = (maxx - minx + 16) * (maxy - miny + 16);
        assertTrue(total < bboxArea,
                "total polygon area exceeds level bbox: " + total);
    }
}
