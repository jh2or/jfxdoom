package doom.system;

import doom.ui.IVideo;
import doom.ui.VVideo;
import doom.wad.WWad;
import doom.wad.ZZone;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Headless probe that boots E1M1 and dumps the framebuffer so
 * we can diff against the reference screenshot. Writes to
 * `build/probe/e1m1_<n>.png` once per call.
 *
 * Not strictly a test — it's a diagnostic tool. Enabled by
 * setting the system property `e1m1.probe=1` (default off so
 * `gradle test` doesn't slow down with the WAD load).
 */
class E1M1ScreenshotProbe {

    private static final Path WAD_PATH = doom.testing.TestWads.find();

    @Test
    void dumpE1M1Frames() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "1".equals(System.getProperty("e1m1.probe")),
                "Diagnostic disabled; enable with -De1m1.probe=1");
        doom.testing.TestWads.require(WAD_PATH);

        // Drive the boot end-to-end the way the JavaFX shell does.
        DMain.dDoomMain(new String[] {
                "-iwad", WAD_PATH.toString(),
                "-warp", "1", "1",
                "-nomonsters" });

        // Run a few frames so the BSP / status bar / HUD have all
        // touched the framebuffer.
        Path outDir = Path.of("build/probe");
        java.nio.file.Files.createDirectories(outDir);
        for (int tic = 0; tic < 5; tic++) {
            DMain.dDoomLoop();
            dump(VVideo.screens[0], outDir.resolve("e1m1_" + tic + ".png").toString());
        }
    }

    private static void dump(byte[] linear, String outPath) throws IOException {
        // Decode the active palette to ARGB.
        byte[] palette;
        try {
            palette = WWad.wCacheLumpName("PLAYPAL", ZZone.PU_CACHE);
        } catch (IOException e) {
            throw new AssertionError("Probe palette read failed", e);
        }
        int w = Doomdef.SCREENWIDTH;
        int h = Doomdef.SCREENHEIGHT;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[w * h];
        for (int i = 0; i < argb.length; i++) {
            int idx = linear[i] & 0xff;
            int r = palette[idx * 3]     & 0xff;
            int g = palette[idx * 3 + 1] & 0xff;
            int b = palette[idx * 3 + 2] & 0xff;
            argb[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        img.setRGB(0, 0, w, h, argb, 0, w);
        ImageIO.write(img, "png", Path.of(outPath).toFile());

        // Avoid an "unused" warning on IVideo when the probe is off.
        @SuppressWarnings("unused") int x = IVideo.startTicHook == null ? 0 : 1;
    }
}
