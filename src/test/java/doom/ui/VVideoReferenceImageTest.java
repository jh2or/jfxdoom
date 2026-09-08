package doom.ui;

import doom.render.Patch;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VVideoReferenceImageTest {
    @Test void patchCompositionMatchesIndependentlyAuthoredImage() throws Exception {
        VVideo.resetForTests();
        VVideo.vInit();
        Arrays.fill(VVideo.screens[0], (byte) 42);
        Patch patch;
        try (var stream = getClass().getResourceAsStream("/render-refs/authored.patch")) {
            assertNotNull(stream);
            patch = Patch.fromLump(stream.readAllBytes());
        }
        // Patch offsets (2,3) put the upper-left at (0,0), (2,2), (315,193).
        VVideo.vDrawPatch(2, 3, 0, patch);
        VVideo.vDrawPatchFlipped(4, 5, 0, patch);
        VVideo.vDrawPatchFlipped(317, 196, 0, patch);
        try (var stream = getClass().getResourceAsStream("/render-refs/authored-patch-composite.png")) {
            assertNotNull(stream);
            var expected = ImageIO.read(stream);
            assertEquals(320, expected.getWidth());
            assertEquals(200, expected.getHeight());
            for (int y = 0; y < 200; y++) {
                for (int x = 0; x < 320; x++) {
                    int index = VVideo.screens[0][y * 320 + x] & 255;
                    int actual = 0xff000000 | index * 0x010101;
                    assertEquals(expected.getRGB(x, y), actual, "pixel " + x + "," + y);
                }
            }
        }
    }
}
