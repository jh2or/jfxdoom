package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.Node;
import doom.render.RData;
import doom.render.Sector;
import doom.render.Seg;
import doom.render.Side;
import doom.render.Slopetype;
import doom.render.Subsector;
import doom.render.Vertex;
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
class PSetupTest {

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
                "Skipping PSetupTest — no WAD in reference-wads/");
    }

    @BeforeEach
    void resetState() throws IOException {
        ZZone.zInit();
        WWad.resetForTests();
        RData.resetForTests();
        WWad.wInitFile(WAD_PATH.toString());
        // Tests that touch sectors/sides need flats + textures resolved.
        RData.rInitData();
    }

    // The sample level — every shipped Doom WAD has E1M1 ("Hangar").
    private int e1m1() {
        return WWad.wGetNumForName("E1M1");
    }

    // --- Individual loaders ---

    @Test
    void loadVertexesPopulatesArrayAtFractionalCoords() throws IOException {
        PSetup.pLoadVertexes(e1m1() + PSetup.ML_VERTEXES);

        assertTrue(PSetup.numvertexes > 0);
        assertEquals(PSetup.numvertexes, PSetup.vertexes.length);
        // Vertex coords are signed shorts shifted into fixed_t —
        // i.e. always multiples of FRACUNIT (low 16 bits zero).
        for (int i = 0; i < Math.min(PSetup.numvertexes, 50); i++) {
            assertEquals(0, PSetup.vertexes[i].x & (Fixed.FRACUNIT - 1),
                    "vertex.x not on fixed-int boundary at i=" + i);
            assertEquals(0, PSetup.vertexes[i].y & (Fixed.FRACUNIT - 1),
                    "vertex.y not on fixed-int boundary at i=" + i);
        }
    }

    @Test
    void loadSectorsPopulatesArrayAndResolvesFlats() throws IOException {
        PSetup.pLoadSectors(e1m1() + PSetup.ML_SECTORS);

        assertTrue(PSetup.numsectors > 0);
        for (int i = 0; i < PSetup.numsectors; i++) {
            Sector s = PSetup.sectors[i];
            assertNotNull(s);
            // Heights are fixed_t — no fractional bits.
            assertEquals(0, s.floorheight & (Fixed.FRACUNIT - 1));
            assertEquals(0, s.ceilingheight & (Fixed.FRACUNIT - 1));
            // floorpic/ceilingpic must resolve into the flat range.
            assertTrue(s.floorpic >= 0, "negative floorpic at sector " + i);
            assertTrue(s.ceilingpic >= 0, "negative ceilingpic at sector " + i);
            // thinglist starts empty.
            assertNull(s.thinglist);
            // light level is a 0..255 short.
            assertTrue(s.lightlevel >= 0 && s.lightlevel <= 255);
        }
    }

    @Test
    void loadSideDefsLinksToSectors() throws IOException {
        PSetup.pLoadSectors(e1m1() + PSetup.ML_SECTORS);
        PSetup.pLoadSideDefs(e1m1() + PSetup.ML_SIDEDEFS);

        assertTrue(PSetup.numsides > 0);
        for (int i = 0; i < PSetup.numsides; i++) {
            Side sd = PSetup.sides[i];
            assertNotNull(sd);
            assertNotNull(sd.sector, "side " + i + " has null sector");
            // Texture offsets are fixed_t.
            assertEquals(0, sd.textureoffset & (Fixed.FRACUNIT - 1));
            assertEquals(0, sd.rowoffset & (Fixed.FRACUNIT - 1));
        }
    }

    @Test
    void loadLineDefsTagsSlopeAndBboxCorrectly() throws IOException {
        PSetup.pLoadVertexes(e1m1() + PSetup.ML_VERTEXES);
        PSetup.pLoadSectors(e1m1() + PSetup.ML_SECTORS);
        PSetup.pLoadSideDefs(e1m1() + PSetup.ML_SIDEDEFS);
        PSetup.pLoadLineDefs(e1m1() + PSetup.ML_LINEDEFS);

        assertTrue(PSetup.numlines > 0);
        for (int i = 0; i < PSetup.numlines; i++) {
            Line ld = PSetup.lines[i];
            assertNotNull(ld);
            assertNotNull(ld.v1);
            assertNotNull(ld.v2);
            // dx/dy match v2 - v1.
            assertEquals(ld.v2.x - ld.v1.x, ld.dx);
            assertEquals(ld.v2.y - ld.v1.y, ld.dy);

            // Slopetype sanity.
            if (ld.dx == 0) {
                assertEquals(Slopetype.ST_VERTICAL, ld.slopetype);
            } else if (ld.dy == 0) {
                assertEquals(Slopetype.ST_HORIZONTAL, ld.slopetype);
            } else {
                assertTrue(ld.slopetype == Slopetype.ST_POSITIVE
                        || ld.slopetype == Slopetype.ST_NEGATIVE,
                        "diagonal line " + i + " has slope " + ld.slopetype);
            }

            // Bbox brackets the endpoints.
            assertEquals(Math.min(ld.v1.x, ld.v2.x), ld.bbox[MBbox.BOXLEFT]);
            assertEquals(Math.max(ld.v1.x, ld.v2.x), ld.bbox[MBbox.BOXRIGHT]);
            assertEquals(Math.min(ld.v1.y, ld.v2.y), ld.bbox[MBbox.BOXBOTTOM]);
            assertEquals(Math.max(ld.v1.y, ld.v2.y), ld.bbox[MBbox.BOXTOP]);

            // sidenum[0] is always set on Doom maps; sidenum[1] is -1
            // for one-sided lines.
            assertTrue(ld.sidenum[0] >= 0,
                    "front sidenum missing at line " + i);
            if (ld.sidenum[1] == -1) {
                assertNull(ld.backsector,
                        "one-sided line " + i + " has backsector");
            } else {
                assertNotNull(ld.backsector,
                        "two-sided line " + i + " missing backsector");
            }
            assertNotNull(ld.frontsector);
        }
    }

    @Test
    void loadSubsectorsParsesIndices() throws IOException {
        PSetup.pLoadSubsectors(e1m1() + PSetup.ML_SSECTORS);

        assertTrue(PSetup.numsubsectors > 0);
        for (int i = 0; i < PSetup.numsubsectors; i++) {
            Subsector ss = PSetup.subsectors[i];
            assertNotNull(ss);
            assertTrue(ss.numlines >= 0);
            assertTrue(ss.firstline >= 0);
            // sector pointer is filled later by pGroupLines.
            assertNull(ss.sector);
        }
    }

    @Test
    void loadNodesParsesPartitionAndChildren() throws IOException {
        PSetup.pLoadNodes(e1m1() + PSetup.ML_NODES);

        assertTrue(PSetup.numnodes > 0);
        for (int i = 0; i < PSetup.numnodes; i++) {
            Node n = PSetup.nodes[i];
            assertNotNull(n);
            // Partition coords are fixed_t.
            assertEquals(0, n.x & (Fixed.FRACUNIT - 1));
            assertEquals(0, n.y & (Fixed.FRACUNIT - 1));
            // Each child is either an in-range subsector index (with
            // NF_SUBSECTOR bit) or in-range node index — but we don't
            // have numsubsectors loaded here, so just sanity-check
            // bounds via the bit semantics.
            for (int j = 0; j < 2; j++) {
                int c = n.children[j];
                assertTrue(c >= 0, "negative child idx");
                assertTrue(c <= 0xFFFF, "child > 16 bits"); // unsigned short
            }
        }
    }

    @Test
    void loadSegsResolvesLinedefAndSectorPointers() throws IOException {
        PSetup.pLoadVertexes(e1m1() + PSetup.ML_VERTEXES);
        PSetup.pLoadSectors(e1m1() + PSetup.ML_SECTORS);
        PSetup.pLoadSideDefs(e1m1() + PSetup.ML_SIDEDEFS);
        PSetup.pLoadLineDefs(e1m1() + PSetup.ML_LINEDEFS);
        PSetup.pLoadSegs(e1m1() + PSetup.ML_SEGS);

        assertTrue(PSetup.numsegs > 0);
        for (int i = 0; i < PSetup.numsegs; i++) {
            Seg s = PSetup.segs[i];
            assertNotNull(s);
            assertNotNull(s.v1);
            assertNotNull(s.v2);
            assertNotNull(s.linedef);
            assertNotNull(s.sidedef);
            assertNotNull(s.frontsector);
            // backsector null iff linedef is one-sided.
            boolean lineTwoSided =
                    (s.linedef.flags & Line.ML_TWOSIDED) != 0;
            if (lineTwoSided) {
                assertNotNull(s.backsector,
                        "two-sided line seg " + i + " has null backsector");
            } else {
                assertNull(s.backsector);
            }
        }
    }

    @Test
    void loadBlockMapPopulatesHeaderAndArrays() throws IOException {
        PSetup.pLoadBlockMap(e1m1() + PSetup.ML_BLOCKMAP);

        assertTrue(PSetup.bmapwidth > 0);
        assertTrue(PSetup.bmapheight > 0);
        // origin is fixed_t.
        assertEquals(0, PSetup.bmaporgx & (Fixed.FRACUNIT - 1));
        assertEquals(0, PSetup.bmaporgy & (Fixed.FRACUNIT - 1));
        assertEquals(PSetup.bmapwidth * PSetup.bmapheight,
                PSetup.blocklinks.length);
        // First four entries are the header repeated in raw form.
        // bmapwidth/bmapheight got mask&0xFFFF, so verify alignment.
        assertEquals(PSetup.blockmaplump, PSetup.blockmap);
    }

    // --- pSetupLevelGeometry: full pipeline integration ---

    @Test
    void setupLevelGeometryFullPipelineE1M1() throws IOException {
        PSetup.pSetupLevelGeometry(e1m1());

        // All eight arrays + reject populated.
        assertTrue(PSetup.numvertexes > 0);
        assertTrue(PSetup.numsegs > 0);
        assertTrue(PSetup.numsectors > 0);
        assertTrue(PSetup.numsubsectors > 0);
        assertTrue(PSetup.numnodes > 0);
        assertTrue(PSetup.numlines > 0);
        assertTrue(PSetup.numsides > 0);
        assertNotNull(PSetup.rejectmatrix);
        assertNotNull(PSetup.blockmap);
        assertNotNull(PSetup.blocklinks);

        // pGroupLines wired the subsector→sector pointers.
        for (int i = 0; i < PSetup.numsubsectors; i++) {
            assertNotNull(PSetup.subsectors[i].sector,
                    "subsector " + i + " has null sector");
        }

        // Every sector got its lines list, sized to linecount.
        for (int i = 0; i < PSetup.numsectors; i++) {
            Sector s = PSetup.sectors[i];
            assertNotNull(s.lines, "sector " + i + " missing lines[]");
            assertEquals(s.linecount, s.lines.length);
            // Bbox-derived sound origin lies within reasonable range
            // (just a sanity check that bbox math fired).
            assertTrue(s.lines.length > 0, "sector " + i + " has no lines");
        }

        // Every loaded line back-points into the sector arrays we have.
        for (int i = 0; i < PSetup.numlines; i++) {
            Line ld = PSetup.lines[i];
            // frontsector must be in PSetup.sectors[].
            int found = -1;
            for (int j = 0; j < PSetup.numsectors; j++) {
                if (PSetup.sectors[j] == ld.frontsector) {
                    found = j;
                    break;
                }
            }
            assertTrue(found >= 0,
                    "line " + i + " frontsector not in sectors[]");
        }

        // Sector blockbox is clipped to bmap extents.
        for (int i = 0; i < PSetup.numsectors; i++) {
            Sector s = PSetup.sectors[i];
            assertTrue(s.blockbox[MBbox.BOXTOP] >= 0
                    && s.blockbox[MBbox.BOXTOP] < PSetup.bmapheight);
            assertTrue(s.blockbox[MBbox.BOXBOTTOM] >= 0
                    && s.blockbox[MBbox.BOXBOTTOM] < PSetup.bmapheight);
            assertTrue(s.blockbox[MBbox.BOXLEFT] >= 0
                    && s.blockbox[MBbox.BOXLEFT] < PSetup.bmapwidth);
            assertTrue(s.blockbox[MBbox.BOXRIGHT] >= 0
                    && s.blockbox[MBbox.BOXRIGHT] < PSetup.bmapwidth);
        }
    }

    // ML_* constants match the C enum order.
    @Test
    void mapLumpEnumOrdinalsMatchC() {
        assertEquals(0, PSetup.ML_LABEL);
        assertEquals(1, PSetup.ML_THINGS);
        assertEquals(2, PSetup.ML_LINEDEFS);
        assertEquals(3, PSetup.ML_SIDEDEFS);
        assertEquals(4, PSetup.ML_VERTEXES);
        assertEquals(5, PSetup.ML_SEGS);
        assertEquals(6, PSetup.ML_SSECTORS);
        assertEquals(7, PSetup.ML_NODES);
        assertEquals(8, PSetup.ML_SECTORS);
        assertEquals(9, PSetup.ML_REJECT);
        assertEquals(10, PSetup.ML_BLOCKMAP);
    }

    // E1M1 (Hangar) — regression hook. Counts are for the registered
    // DOOM.WAD specifically; shareware doom1.wad's E1M1 ships
    // slightly different geometry. If these drift, either the WAD
    // changed or the parser miscounts.
    @Test
    void e1m1HasKnownGeometryCounts() throws IOException {
        PSetup.pSetupLevelGeometry(e1m1());

        assertEquals(470, PSetup.numvertexes);
        assertEquals(486, PSetup.numlines);
        assertEquals(666, PSetup.numsides);
        assertEquals(88, PSetup.numsectors);
    }
}
