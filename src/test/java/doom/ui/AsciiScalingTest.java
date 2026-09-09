package doom.ui;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Run on a display with -Dascii.scaling.probe=1; verifies the actual JavaFX raster. */
class AsciiScalingTest {
    @Test void fractionalScalingDoesNotSplitUniformGlyphsIntoLightAndDarkTriangles() throws Exception {
        assumeTrue("1".equals(System.getProperty("ascii.scaling.probe")), "Requires a JavaFX display");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Runnable check = () -> {
            try {
                var surface = new JfxDoomApp.BlitSurface(1280, 800, true);
                int[] glyph = {7, 5, 7, 4, 7, 0};
                for (int y = 0; y < 800; y++) for (int x = 0; x < 1280; x++) {
                    surface.argb[y * 1280 + x] = x % 4 < 3 && (glyph[y % 6] & (4 >> (x % 4))) != 0
                            ? 0xffffffff : 0xff000000;
                }
                new Scene(new Group(surface.canvas));
                surface.canvas.setWidth(1920);
                surface.canvas.setHeight(1200);
                surface.blit(false);
                var shot = surface.canvas.snapshot(null, null);
                var reader = shot.getPixelReader();
                long sumA = 0, sumB = 0, countA = 0, countB = 0;
                for (int y = 0; y < 1200; y++) for (int x = 0; x < 1920; x++) {
                    double position = x / 1920.0 + y / 1200.0;
                    int brightness = reader.getArgb(x, y) & 255;
                    if (position < .98) { sumA += brightness; countA++; }
                    else if (position > 1.02) { sumB += brightness; countB++; }
                }
                double meanA = (double) sumA / countA, meanB = (double) sumB / countB;
                assertTrue(Math.abs(meanA - meanB) < 2,
                        "Uniform glyphs must retain matching triangle brightness: " + meanA + " vs " + meanB);
                assertTrue(meanA > 120 && meanA < 135, "Retain the pattern's overall intensity");
                surface.canvas.setWidth(2560);
                surface.canvas.setHeight(1600);
                surface.blit(false);
                assertFalse(surface.gc.isImageSmoothing(), "Integer scaling remains sharp");
                var normal = new JfxDoomApp.BlitSurface(1280, 800);
                normal.canvas.setWidth(1920);
                normal.canvas.setHeight(1200);
                normal.blit(false);
                assertFalse(normal.gc.isImageSmoothing(), "Other renderers keep their existing scaling");
            } catch (Throwable failure) { error.set(failure); }
            finally { done.countDown(); }
        };
        try { Platform.startup(check); }
        catch (IllegalStateException alreadyStarted) { Platform.runLater(check); }
        assertTrue(done.await(30, TimeUnit.SECONDS), "JavaFX raster check timed out");
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
