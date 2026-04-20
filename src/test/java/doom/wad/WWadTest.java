package doom.wad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import doom.system.ISystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WWadTest {

    // Both casings exist on different machines; prefer what's on disk.
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
                "Skipping WWadTest — no WAD in reference-wads/");
    }

    @BeforeEach
    void resetState() throws IOException {
        ZZone.zInit();
        WWad.resetForTests();
    }

    // --- Pure unit tests that don't need the WAD ---

    @Test
    void extractFileBaseBasic() {
        assertEquals("DOOM1", WWad.extractFileBase("C:/games/doom1.wad"));
        assertEquals("DOOM1", WWad.extractFileBase("doom1.wad"));
        assertEquals("DOOM1", WWad.extractFileBase("C:\\games\\doom1.wad"));
        assertEquals("FOO", WWad.extractFileBase("/tmp/foo"));
    }

    @Test
    void extractFileBaseTooLongThrows() {
        assertThrows(ISystem.DoomError.class,
                () -> WWad.extractFileBase("nine_char.wad"));
    }

    @Test
    void parseHeaderReadsIdentification() {
        byte[] bytes = new byte[] {
                'I', 'W', 'A', 'D',
                0x12, 0x00, 0x00, 0x00,
                0x40, 0x00, 0x00, 0x00 };
        WWad.WadInfo info = WWad.parseHeader(bytes);
        assertEquals("IWAD", info.identification);
        assertEquals(0x12, info.numlumps);
        assertEquals(0x40, info.infotableofs);
    }

    @Test
    void trimLumpNameStripsTrailingNulls() {
        byte[] raw = { 'E', '1', 'M', '1', 0, 0, 0, 0 };
        assertEquals("E1M1", WWad.trimLumpName(raw));
    }

    @Test
    void trimLumpNameFullEight() {
        byte[] raw = "PLAYPAL_".getBytes();
        assertEquals("PLAYPAL_", WWad.trimLumpName(raw));
    }

    // --- Integration tests against DOOM.WAD ---

    @Test
    void wInitFileLoadsRegisteredWad() throws IOException {
        WWad.wInitFile(WAD_PATH.toString());
        assertTrue(WWad.numlumps > 0, "WAD has lumps");
        // Registered DOOM.WAD has ~2306 lumps, shareware doom1.wad ~1264.
        // Just assert "a lot" — precise count depends on which WAD is on disk.
        assertTrue(WWad.numlumps > 500, "numlumps unexpectedly small: " + WWad.numlumps);
    }

    @Test
    void wCheckNumForNameFindsKnownLump() throws IOException {
        WWad.wInitFile(WAD_PATH.toString());
        // PLAYPAL (256×3 × 14 palettes = 10752) is always present.
        int i = WWad.wCheckNumForName("PLAYPAL");
        assertTrue(i >= 0, "PLAYPAL missing");
        assertEquals("PLAYPAL", WWad.lumpinfo[i].name);
        assertEquals(10752, WWad.wLumpLength(i), "PLAYPAL is 14 palettes × 768 bytes");
    }

    @Test
    void wCheckNumForNameMissesUnknown() throws IOException {
        WWad.wInitFile(WAD_PATH.toString());
        assertEquals(-1, WWad.wCheckNumForName("ZOMBZORZ"));
    }

    @Test
    void wGetNumForNameThrowsOnMissing() throws IOException {
        WWad.wInitFile(WAD_PATH.toString());
        assertThrows(ISystem.DoomError.class,
                () -> WWad.wGetNumForName("ZOMBZORZ"));
    }

    @Test
    void wCheckNumIsCaseInsensitive() throws IOException {
        WWad.wInitFile(WAD_PATH.toString());
        int lo = WWad.wCheckNumForName("playpal");
        int hi = WWad.wCheckNumForName("PLAYPAL");
        assertEquals(hi, lo);
    }

    @Test
    void wReadLumpPlayPalSanity() throws IOException {
        WWad.wInitFile(WAD_PATH.toString());
        int num = WWad.wGetNumForName("PLAYPAL");
        byte[] buf = new byte[WWad.wLumpLength(num)];
        WWad.wReadLump(num, buf);
        assertEquals(10752, buf.length);
        // The first palette entry is the background / first color — often
        // non-trivial. Stronger assertion: not all-zero.
        int nonzero = 0;
        for (int i = 0; i < 768; i++) if (buf[i] != 0) nonzero++;
        assertTrue(nonzero > 100, "first palette should have many non-zero entries");
    }

    @Test
    void wCacheLumpNumCachesAndReuses() throws IOException {
        WWad.wInitFile(WAD_PATH.toString());
        int num = WWad.wGetNumForName("PLAYPAL");
        byte[] first = WWad.wCacheLumpNum(num, ZZone.PU_STATIC);
        byte[] second = WWad.wCacheLumpNum(num, ZZone.PU_STATIC);
        // Same array instance — second call hits the cache.
        assertSame(first, second);
        assertEquals(10752, first.length);
    }

    @Test
    void wCacheLumpNumRefillsAfterFree() throws IOException {
        WWad.wInitFile(WAD_PATH.toString());
        int num = WWad.wGetNumForName("PLAYPAL");
        byte[] first = WWad.wCacheLumpNum(num, ZZone.PU_CACHE);
        // Simulate zone reclamation of the purgable block.
        ZZone.zFree(first);
        assertNotNull(WWad.lumpcache);
        // Slot was cleared by the UserRef.
        assertTrue(WWad.lumpcache[num] == null);
        byte[] refetched = WWad.wCacheLumpNum(num, ZZone.PU_CACHE);
        assertNotSame(first, refetched);
        assertEquals(10752, refetched.length);
    }

    @Test
    void wCacheLumpNameDelegatesToNum() throws IOException {
        WWad.wInitFile(WAD_PATH.toString());
        byte[] a = WWad.wCacheLumpName("PLAYPAL", ZZone.PU_STATIC);
        byte[] b = WWad.wCacheLumpNum(WWad.wGetNumForName("PLAYPAL"), ZZone.PU_STATIC);
        assertSame(a, b);
    }
}
