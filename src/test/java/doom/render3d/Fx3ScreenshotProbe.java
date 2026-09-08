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
 * reports a JUnit skip if unsupported.
 */
class Fx3ScreenshotProbe {

    private static final Path WAD_PATH = doom.testing.TestWads.find();

    @Test
    void dumpFx3E1M1() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "1".equals(System.getProperty("fx3.probe")),
                "Diagnostic disabled; enable with -Dfx3.probe=1");
        doom.testing.TestWads.require(WAD_PATH);

        CountDownLatch done = new CountDownLatch(1);
        Throwable[] fail = new Throwable[1];

        Platform.startup(() -> {
            try {
                org.junit.jupiter.api.Assumptions.assumeTrue(Platform.isSupported(
                        javafx.application.ConditionalFeature.SCENE3D),
                        "JavaFX SCENE3D unavailable");
                DMain.dDoomMain(new String[] {
                        "-iwad", WAD_PATH.toString(),
                        "-warp", "1", "1" });

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
                doom.play.Player pl = doom.system.GGame.players[0];
                int[] angles = { 0x80000000, 0xC0000000, 0x00000000 };
                String[] names = { "w", "s", "e" };
                for (int a = 0; a < angles.length; a++) {
                    pl.mo.angle = angles[a];
                    for (int tic = 0; tic < 3; tic++) {
                        DMain.dDoomLoop();
                    }
                    savePng(sub.snapshot(null, null),
                            Path.of("build/probe/fx3_e1m1_" + names[a] + ".png"));
                }

                // Door test (Phase 3 dynamic rebuild): trigger the
                // first DR door (special 1) through the playsim and
                // face it mid-swing and fully open.
                doom.render.Line door = null;
                for (int i = 0; i < doom.play.PSetup.numlines; i++) {
                    if (doom.play.PSetup.lines[i].special == 1) {
                        door = doom.play.PSetup.lines[i];
                        break;
                    }
                }
                if (door != null) {
                    faceLine(pl, door);
                    doom.play.PDoors.evVerticalDoor(door, pl.mo);
                    for (int tic = 0; tic < 12; tic++) DMain.dDoomLoop();
                    savePng(sub.snapshot(null, null),
                            Path.of("build/probe/fx3_door_mid.png"));
                    for (int tic = 0; tic < 80; tic++) DMain.dDoomLoop();
                    savePng(sub.snapshot(null, null),
                            Path.of("build/probe/fx3_door_open.png"));
                }

                // Sky test: park the camera in the middle of the
                // biggest sky-ceiling subsector, eye at floor + 41.
                double[][] polys = Fx3SubsectorPolys.buildAll();
                int best = -1;
                double bestArea = 0;
                for (int i = 0; i < doom.play.PSetup.numsubsectors; i++) {
                    if (doom.play.PSetup.subsectors[i].sector.ceilingpic
                            != doom.render.RSky.skyflatnum) continue;
                    double area = Fx3SubsectorPolys.signedArea(polys[i]);
                    if (area > bestArea) { bestArea = area; best = i; }
                }
                if (best >= 0) {
                    double[] poly = polys[best];
                    double cx = 0, cy = 0;
                    int n = poly.length / 2;
                    for (int i = 0; i < n; i++) {
                        cx += poly[2 * i];
                        cy += poly[2 * i + 1];
                    }
                    doom.render.Sector sec =
                            doom.play.PSetup.subsectors[best].sector;
                    pl.mo.x = (int) (cx / n * 65536);
                    pl.mo.y = (int) (cy / n * 65536);
                    pl.mo.z = sec.floorheight;
                    pl.mo.floorz = sec.floorheight;
                    pl.mo.ceilingz = sec.ceilingheight;
                    // Face the farthest polygon vertex — the most
                    // open direction, likeliest to frame the sky.
                    double fvx = poly[0], fvy = poly[1], fd = 0;
                    for (int i = 0; i < n; i++) {
                        double d = Math.hypot(poly[2 * i] - cx / n,
                                poly[2 * i + 1] - cy / n);
                        if (d > fd) { fd = d; fvx = poly[2 * i]; fvy = poly[2 * i + 1]; }
                    }
                    double deg = Math.toDegrees(
                            Math.atan2(fvy - cy / n, fvx - cx / n));
                    pl.mo.angle = (int) ((deg / 360.0) * 4294967296.0);
                    for (int tic = 0; tic < 3; tic++) DMain.dDoomLoop();
                    savePng(sub.snapshot(null, null),
                            Path.of("build/probe/fx3_sky.png"));
                    pl.mo.angle += 0x80000000; // opposite direction
                    for (int tic = 0; tic < 3; tic++) DMain.dDoomLoop();
                    savePng(sub.snapshot(null, null),
                            Path.of("build/probe/fx3_sky2.png"));
                }
                System.err.println("fx3.probe: wrote build/probe/fx3_*.png");
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
        if (fail[0] instanceof org.opentest4j.TestAbortedException aborted) throw aborted;
        if (fail[0] != null) throw new AssertionError(fail[0]);
    }

    // Park the player 96 units in front of the line's midpoint (on
    // its front side — right of v1→v2), facing it.
    private static void faceLine(doom.play.Player pl, doom.render.Line line) {
        double x1 = line.v1.x / 65536.0, y1 = line.v1.y / 65536.0;
        double x2 = line.v2.x / 65536.0, y2 = line.v2.y / 65536.0;
        double mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.hypot(dx, dy);
        double px = mx + (dy / len) * 96;
        double py = my - (dx / len) * 96;
        pl.mo.x = (int) (px * 65536);
        pl.mo.y = (int) (py * 65536);
        if (line.frontsector != null) {
            pl.mo.z = line.frontsector.floorheight;
            pl.mo.floorz = line.frontsector.floorheight;
            pl.mo.ceilingz = line.frontsector.ceilingheight;
        }
        double deg = Math.toDegrees(Math.atan2(my - py, mx - px));
        pl.mo.angle = (int) ((deg / 360.0) * 4294967296.0);
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
