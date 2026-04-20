package doom.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import doom.fixed.Fixed;
import doom.system.ISystem;
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
class RDataTest {

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
                "Skipping RDataTest — no WAD in reference-wads/");
    }

    @BeforeEach
    void resetState() throws IOException {
        ZZone.zInit();
        WWad.resetForTests();
        RData.resetForTests();
        WWad.wInitFile(WAD_PATH.toString());
    }

    @Test
    void rInitColormapsLoadsTable() throws IOException {
        RData.rInitColormaps();
        // COLORMAP is a multiple of 256 (one row per light level). Shareware
        // and registered Doom ship 34-row tables; later IWADs may differ.
        assertTrue(RData.colormaps.length > 0);
        assertEquals(0, RData.colormaps.length % 256,
                "COLORMAP length must be a multiple of 256");
        assertTrue(RData.colormaps.length >= 32 * 256,
                "COLORMAP should have at least 32 light levels");
    }

    @Test
    void rInitFlatsPopulatesRange() {
        RData.rInitFlats();
        assertTrue(RData.firstflat > 0);
        assertTrue(RData.lastflat > RData.firstflat);
        assertEquals(RData.lastflat - RData.firstflat + 1, RData.numflats);
        assertEquals(RData.numflats + 1, RData.flattranslation.length);
        for (int i = 0; i < RData.numflats; i++) {
            assertEquals(i, RData.flattranslation[i]);
        }
    }

    @Test
    void rInitSpriteLumpsLoadsHeaders() throws IOException {
        RData.rInitSpriteLumps();
        assertTrue(RData.numspritelumps > 0);
        assertEquals(RData.numspritelumps, RData.spritewidth.length);
        // Every sprite lump should have a positive fixed-point width.
        for (int i = 0; i < RData.numspritelumps; i++) {
            assertTrue(Fixed.toInt(RData.spritewidth[i]) > 0,
                    "sprite " + i + " width > 0");
        }
    }

    @Test
    void rInitTexturesLoadsTexture1() throws IOException {
        RData.rInitTextures();
        assertTrue(RData.numtextures > 0);
        assertEquals(RData.numtextures, RData.textures.length);
        // STARTAN3 is a canonical Doom texture; present in both shareware
        // and registered WADs.
        int idx = RData.rCheckTextureNumForName("STARTAN3");
        assertTrue(idx >= 0, "STARTAN3 should be present");
        RData.Texture t = RData.textures[idx];
        assertNotNull(t);
        assertEquals(128, t.width);
        assertEquals(128, t.height);
        assertTrue(t.patchcount > 0);
        // textureheight is fixed-point.
        assertEquals(Fixed.fromInt(128), RData.textureheight[idx]);
        // widthmask = highest power-of-two <= width, minus 1. The C loop:
        //   j=1; while (j*2 <= width) j<<=1;
        // For width=128 → j advances to 128, mask = 127.
        assertEquals(127, RData.texturewidthmask[idx]);
    }

    @Test
    void rCheckTextureNumForNameHandlesNoTextureMarker() throws IOException {
        RData.rInitTextures();
        assertEquals(0, RData.rCheckTextureNumForName("-"));
        assertEquals(-1, RData.rCheckTextureNumForName("ZZNOSUCH"));
    }

    @Test
    void rTextureNumForNameThrowsOnMissing() throws IOException {
        RData.rInitTextures();
        assertThrows(ISystem.DoomError.class,
                () -> RData.rTextureNumForName("ZZNOSUCH"));
    }

    @Test
    void rInitDataWiresAllSubsystems() throws IOException {
        RData.rInitData();
        assertTrue(RData.numtextures > 0);
        assertTrue(RData.numflats > 0);
        assertTrue(RData.numspritelumps > 0);
        assertNotNull(RData.colormaps);
    }

    @Test
    void rGetColumnReturnsBytesForEveryColumn() throws IOException {
        RData.rInitTextures();
        int idx = RData.rTextureNumForName("STARTAN3");
        RData.Texture t = RData.textures[idx];
        for (int c = 0; c < t.width; c++) {
            RData.ColumnRef ref = RData.rGetColumn(idx, c);
            assertNotNull(ref.data);
            assertTrue(ref.offset >= 0);
            // Enough room for at least a 1-post terminator byte.
            assertTrue(ref.offset < ref.data.length);
        }
    }

    @Test
    void rGetColumnMasksWithTextureWidthMask() throws IOException {
        RData.rInitTextures();
        int idx = RData.rTextureNumForName("STARTAN3");
        int mask = RData.texturewidthmask[idx];
        RData.ColumnRef a = RData.rGetColumn(idx, 0);
        RData.ColumnRef b = RData.rGetColumn(idx, mask + 1); // wraps to col 0
        assertEquals(a.offset, b.offset);
    }

    @Test
    void precacheLevelStubNotYetWired() {
        assertThrows(UnsupportedOperationException.class,
                () -> RData.rPrecacheLevel());
    }
}
