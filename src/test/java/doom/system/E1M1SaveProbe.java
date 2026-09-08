package doom.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Diagnostic: boot E1M1 and try to save the game. Reports any
 * exception thrown during PSaveg.save() — the user reported an
 * "endless loop exception" when they tried Save from the menu.
 *
 * Armed via `-De1m1.probe=1`.
 */
class E1M1SaveProbe {

    private static final Path WAD_PATH = doom.testing.TestWads.find();

    @Test
    void saveGameDoesNotLoopOrThrow(@TempDir Path tmp) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "1".equals(System.getProperty("e1m1.probe")),
                "Diagnostic disabled; enable with -De1m1.probe=1");
        doom.testing.TestWads.require(WAD_PATH);

        DMain.dDoomMain(new String[] {
                "-iwad", WAD_PATH.toString(),
                "-warp", "1", "1",
                "-nomonsters" });

        // Run a few ticks so the world settles.
        for (int i = 0; i < 5; i++) DMain.dDoomLoop();

        // Save via the same path the menu uses: gSaveGame queues
        // ga_savegame, gTicker pumps it, gDoSaveGame writes.
        Path savePath = tmp.resolve("e1m1_test.dsg");
        GGame.gSaveGame(0, "test save");
        GGame.saveName = savePath.toString();
        long start = System.nanoTime();
        try {
            GGame.gDoSaveGame();
        } catch (Throwable t) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            System.err.printf("gDoSaveGame threw after %d ms: %s%n", ms, t);
            t.printStackTrace(System.err);
            throw t;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.err.printf("gDoSaveGame OK in %d ms (size=%d bytes)%n",
                ms, Files.size(savePath));
    }
}
