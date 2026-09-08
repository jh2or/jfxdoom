package doom.system;

import doom.testing.TestWads;
import doom.ui.VVideo;
import doom.wad.WWad;
import doom.wad.ZZone;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The expected title is decoded directly from the supplied WAD, without Patch/WWad/VVideo. */
class TitlePageReferenceTest {
    @Test void realWadTitleMatchesIndependentRasterReferenceOnEveryPixel() throws Exception {
        var path = TestWads.find();
        TestWads.require(path);
        byte[] wad = Files.readAllBytes(path);
        byte[] title = rawLump(wad, "TITLEPIC");
        byte[] palette = rawLump(wad, "PLAYPAL");
        byte[] expected = decodeTitle(title);
        String previousPage = DMain.pagename;
        try {
            WWad.resetForTests();
            ZZone.zInit();
            WWad.wInitFile(path.toString());
            VVideo.vInit();
            DMain.pagename = "TITLEPIC";
            // Both uncached and cached draws must fully repaint the title.
            for (int sentinel : new int[] {42, 123}) {
                Arrays.fill(VVideo.screens[0], (byte) sentinel);
                DMain.dPageDrawer();
                assertArrayEquals(expected, VVideo.screens[0], "TITLEPIC palette indices");
                byte[] mountedPalette = WWad.wCacheLumpName("PLAYPAL", ZZone.PU_CACHE);
                for (int i = 0; i < expected.length; i++) {
                    int source = (expected[i] & 255) * 3;
                    int rendered = (VVideo.screens[0][i] & 255) * 3;
                    for (int channel = 0; channel < 3; channel++) {
                        assertEquals(palette[source + channel], mountedPalette[rendered + channel],
                                "title RGB pixel " + i + " channel " + channel);
                    }
                }
            }
        } finally {
            WWad.resetForTests();
            DMain.pagename = previousPage;
        }
    }

    private byte[] rawLump(byte[] wad, String name) {
        ByteBuffer input = ByteBuffer.wrap(wad).order(ByteOrder.LITTLE_ENDIAN);
        int count = input.getInt(4);
        int directory = input.getInt(8);
        for (int entry = count - 1; entry >= 0; entry--) {
            int at = directory + entry * 16;
            String entryName = new String(wad, at + 8, 8, StandardCharsets.US_ASCII)
                    .replace("\0", "");
            if (entryName.equals(name)) {
                int start = input.getInt(at);
                return Arrays.copyOfRange(wad, start, start + input.getInt(at + 4));
            }
        }
        throw new AssertionError("Required Doom title lump missing: " + name);
    }

    private byte[] decodeTitle(byte[] bytes) {
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(320, input.getShort(0));
        assertEquals(200, input.getShort(2));
        assertEquals(0, input.getShort(4));
        assertEquals(0, input.getShort(6));
        byte[] pixels = new byte[320 * 200];
        boolean[] covered = new boolean[pixels.length];
        for (int x = 0; x < 320; x++) {
            int post = input.getInt(8 + 4 * x);
            while ((bytes[post] & 255) != 255) {
                int y = bytes[post] & 255;
                int length = bytes[post + 1] & 255;
                for (int row = 0; row < length; row++) {
                    int at = (y + row) * 320 + x;
                    pixels[at] = bytes[post + 3 + row];
                    covered[at] = true;
                }
                post += length + 4;
            }
        }
        for (boolean pixel : covered) assertTrue(pixel, "TITLEPIC must cover the full screen");
        return pixels;
    }
}
