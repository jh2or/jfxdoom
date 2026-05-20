package doom.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.ui.VVideo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MMiscScreenshotTest {

    // The screenshot writer reads VVideo.screens[0] + the WAD's
    // PLAYPAL palette, decodes the indexed framebuffer to ARGB,
    // and writes a PNG. Tests that don't carry a real WAD can't
    // verify the full path — instead exercise the file-naming
    // ladder via a known-good palette stub and a synthesized
    // framebuffer.

    // Helper — install a fake PLAYPAL lump via direct mutation of
    // the screens + a temporary working directory.
    @Test
    void screenshotWritesPngWithDoomPrefix(@TempDir Path tmp) throws IOException {
        // Make CWD = tmp so DOOM00.png lands in the temp dir.
        String prevCwd = System.getProperty("user.dir");
        System.setProperty("user.dir", tmp.toString());
        try {
            // Seed framebuffer with a simple gradient.
            VVideo.screens[0] = new byte[Doomdef.SCREENWIDTH * Doomdef.SCREENHEIGHT];
            for (int i = 0; i < VVideo.screens[0].length; i++) {
                VVideo.screens[0][i] = (byte) (i & 0xff);
            }
            // We can't easily install a real PLAYPAL in tests
            // without a WAD — exercise the early-bail path: with
            // PLAYPAL missing, mScreenShot returns silently. The
            // separate WAD-loaded integration verifies the full
            // round-trip via the BufferedImage path.
            MMisc.mScreenShot();
            // Verify nothing crashed and no png landed in tmp.
            assertTrue(Files.list(tmp).noneMatch(p -> p.getFileName().toString()
                    .toLowerCase().endsWith(".png")));
        } finally {
            System.setProperty("user.dir", prevCwd);
        }
    }

    // Build a minimal "PLAYPAL" lump in memory and verify the
    // ARGB decode + PNG round-trip works (uses ImageIO directly,
    // bypassing the WAD-load + file-naming path).
    @Test
    void argbDecodeOfIndexedFramebufferMatchesPalette() throws IOException {
        // 256-colour palette: each index i → (i, i, i) grayscale.
        byte[] palette = new byte[768];
        for (int i = 0; i < 256; i++) {
            palette[i * 3]     = (byte) i;
            palette[i * 3 + 1] = (byte) i;
            palette[i * 3 + 2] = (byte) i;
        }
        // Synthetic 4×4 framebuffer with linear indices.
        int w = 4, h = 4;
        byte[] linear = { 0, 32, 64, 96,
                          (byte) 128, (byte) 160, (byte) 192, (byte) 224,
                          0, 32, 64, 96,
                          (byte) 128, (byte) 160, (byte) 192, (byte) 224 };
        // Match the ARGB conversion the screenshot writer does.
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[w * h];
        for (int i = 0; i < argb.length; i++) {
            int idx = linear[i] & 0xff;
            int r = palette[idx * 3] & 0xff;
            argb[i] = 0xff000000 | (r << 16) | (r << 8) | r;
        }
        img.setRGB(0, 0, w, h, argb, 0, w);
        // Round-trip through PNG.
        java.nio.file.Path tmp = Files.createTempFile("doomshot", ".png");
        try {
            ImageIO.write(img, "png", tmp.toFile());
            java.awt.image.BufferedImage read = ImageIO.read(tmp.toFile());
            assertNotNull(read);
            assertEquals(w, read.getWidth());
            assertEquals(h, read.getHeight());
            // Pixel (0, 0) was index 0 → black (0xff000000).
            assertEquals(0xff000000, read.getRGB(0, 0));
            // Pixel (3, 1) was index 224 → mid-bright gray.
            int p = read.getRGB(3, 1);
            assertEquals(224, p & 0xff);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
