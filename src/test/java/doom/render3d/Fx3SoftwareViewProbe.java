package doom.render3d;

import doom.system.DMain;
import doom.system.Doomdef;
import doom.ui.VVideo;
import doom.wad.WWad;
import doom.wad.ZZone;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Diagnostic companion to Fx3ScreenshotProbe: dumps the *software*
 * renderer's E1M1 view (VVideo.viewBuffer at RENDER size) to
 * build/probe/sw_e1m1.png for side-by-side comparison with the
 * experimental 3D renderer's output. Headless — no JavaFX.
 *
 * Enabled with -Dfx3.probe=1; run it in its own Gradle invocation
 * (each probe boots the engine once per JVM).
 */
class Fx3SoftwareViewProbe {

    private static final Path WAD_PATH = Path.of("reference-wads/DOOM.WAD");

    @Test
    void dumpSoftwareE1M1() throws Exception {
        if (!"1".equals(System.getProperty("fx3.probe"))) return;

        DMain.dDoomMain(new String[] {
                "-iwad", WAD_PATH.toString(),
                "-warp", "1", "1",
                "-nomonsters" });
        for (int tic = 0; tic < 70; tic++) {
            DMain.dDoomLoop();
        }

        byte[] palette = WWad.wCacheLumpName("PLAYPAL", ZZone.PU_CACHE);
        byte[] view = VVideo.viewBuffer;
        int w = Doomdef.RENDER_WIDTH;
        int h = Doomdef.RENDER_HEIGHT;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = view[y * w + x] & 0xff;
                int r = palette[idx * 3]     & 0xff;
                int g = palette[idx * 3 + 1] & 0xff;
                int b = palette[idx * 3 + 2] & 0xff;
                img.setRGB(x, y, 0xff000000 | (r << 16) | (g << 8) | b);
            }
        }
        Files.createDirectories(Path.of("build/probe"));
        ImageIO.write(img, "png", Path.of("build/probe/sw_e1m1.png").toFile());
        System.err.println("fx3.probe: wrote build/probe/sw_e1m1.png");
    }
}
