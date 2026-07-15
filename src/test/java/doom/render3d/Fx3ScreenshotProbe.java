package doom.render3d;

import doom.system.DMain;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SubScene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Diagnostic (not a test): boots E1M1 with the experimental JavaFX
 * scene-graph renderer and snapshots the 3D SubScene to
 * build/probe/fx3_e1m1.png so winding / camera orientation can be
 * verified without a display session. Mirrors E1M1ScreenshotProbe.
 *
 * Enabled with -Dfx3.probe=1 (forwarded by the Gradle test task).
 * Requires a JavaFX pipeline with SCENE3D support (D3D on Windows);
 * prints a notice and returns cleanly if unsupported.
 */
class Fx3ScreenshotProbe {

    private static final Path WAD_PATH = Path.of("reference-wads/DOOM.WAD");

    @Test
    void dumpFx3E1M1() throws Exception {
        if (!"1".equals(System.getProperty("fx3.probe"))) return;

        CountDownLatch done = new CountDownLatch(1);
        Throwable[] fail = new Throwable[1];

        Platform.startup(() -> {
            try {
                if (!Platform.isSupported(
                        javafx.application.ConditionalFeature.SCENE3D)) {
                    System.err.println("fx3.probe: SCENE3D unsupported — skipping");
                    return;
                }
                DMain.dDoomMain(new String[] {
                        "-iwad", WAD_PATH.toString(),
                        "-warp", "1", "1",
                        "-nomonsters" });

                Fx3Renderer.active = true;
                SubScene sub = Fx3Renderer.subScene();
                sub.setWidth(640);
                sub.setHeight(400);
                // A Scene keeps snapshot happy; never shown.
                new Scene(new Group(sub));

                // ~2 seconds of tics: the level is up, wipe done,
                // renderPlayerView has built the meshes and aimed
                // the camera.
                for (int tic = 0; tic < 70; tic++) {
                    DMain.dDoomLoop();
                }

                Files.createDirectories(Path.of("build/probe"));
                WritableImage img = sub.snapshot(null, null);
                savePng(img, Path.of("build/probe/fx3_e1m1.png"));

                // Extra viewpoints: spin the player (camera-only
                // state) and re-render. West faces the zigzag
                // window strip (upper/lower walls + sky beyond),
                // south the STARTAN back wall.
                int[] angles = { 0x80000000, 0xC0000000, 0x00000000 };
                String[] names = { "w", "s", "e" };
                for (int a = 0; a < angles.length; a++) {
                    doom.system.GGame.players[0].mo.angle = angles[a];
                    for (int tic = 0; tic < 3; tic++) {
                        DMain.dDoomLoop();
                    }
                    savePng(sub.snapshot(null, null),
                            Path.of("build/probe/fx3_e1m1_" + names[a] + ".png"));
                }
                System.err.println("fx3.probe: wrote build/probe/fx3_e1m1*.png");
            } catch (Throwable t) {
                fail[0] = t;
            } finally {
                done.countDown();
            }
        });

        if (!done.await(120, TimeUnit.SECONDS)) {
            throw new AssertionError("fx3.probe: timed out");
        }
        Platform.exit();
        if (fail[0] != null) throw new AssertionError(fail[0]);
    }

    private static void savePng(WritableImage img, Path out) throws Exception {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bi.setRGB(x, y, pr.getArgb(x, y));
            }
        }
        ImageIO.write(bi, "png", out.toFile());
    }
}
