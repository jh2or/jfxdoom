package doom.ai;

import doom.play.PMap;
import doom.system.*;
import doom.wad.WWad;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RouteOptimizerTest {
    @TempDir Path temp;
    private static final Path WAD = Path.of("reference-wads/DOOM.WAD");

    @BeforeEach void requireMatchingWad() throws Exception {
        assumeTrue(Files.isRegularFile(WAD), "Requires local Doom 1 WAD");
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(WAD)));
        assumeTrue(hash.equals("6fdf361847b46228cfebd9f3af09cd844282ac75f3edbb61ca4cb27103ce2e7f"), "Fixture requires matching WAD");
        PMap.changeSectorOverride = false;
    }
    @AfterEach void cleanup() {
        doom.ui.StStuff.stStopped = true; doom.ui.StStuff.sbar = null;
        GGame.gameaction = GGame.ga_nothing;
        GGame.demorecording = Doomstat.demoplayback = Doomstat.singledemo = false;
        DMain.advancedemo = false; WWad.wShutdown();
    }
    private Path originalDemo() throws Exception {
        Path demo = temp.resolve("original.lmp");
        try (var input = getClass().getResourceAsStream("/ai/e1m1-learned.lmp")) { Files.copy(input, demo); }
        return demo;
    }

    @Test void shortcutsLearnedRouteUnderTwoMinutesWithLittleBlockedMovement() throws Exception {
        Episode e = new Episode(WAD, 3, 21000);
        RunTrace original = RunTrace.play(e, DemoInput.read(originalDemo(), 3, 21000), () -> false);
        assertEquals(Episode.Outcome.COMPLETED, original.outcome());
        RunTrace candidate = new RouteOptimizer(new LearnerRandom(1)).propose(e, original, 0, () -> false);
        assertEquals(Episode.Outcome.COMPLETED, candidate.outcome());
        assertTrue(candidate.route().tics <= 4200);
        assertTrue(candidate.metrics().blockedTics() < original.metrics().blockedTics() / 10);
        assertTrue(candidate.metrics().idleTics() < 350, "Do not replace wall pushing with idle waiting");
        assertEquals(0, e.player().cheats);
        assertFalse(Doomstat.nomonsters);
        Route.replay(e, candidate.route());
        assertEquals(Episode.Outcome.COMPLETED, e.outcome());
        Path demo = temp.resolve("fast.lmp"); Route.demo(demo, 3, candidate.route());
        assertEquals(candidate.route().tics, DemoVerifier.verify(WAD, demo, 3, 4200, candidate.route()));
    }

    @Test void trainingContinuesBeyondFirstWinAndRetainsVerifiedIncumbentOnResume() throws Exception {
        Path output = temp.resolve("experiment");
        Trainer.main(new String[] {"--wad", WAD.toString(), "--improve", originalDemo().toString(),
                "--output", output.toString(), "--attempts", "1", "--minutes", "1"});
        int firstTics = DemoInput.read(output.resolve("winning.lmp"), 3, 21000).size();
        assertTrue(firstTics <= 4200);
        Path already = temp.resolve("already-fast");
        Trainer.main(new String[] {"--wad", WAD.toString(), "--improve", output.resolve("winning.lmp").toString(), "--output", already.toString()});
        assertTrue(Files.exists(already.resolve("manifest.txt")));
        assertTrue(Files.exists(already.resolve("archive.bin")));
        assertTrue(Files.readString(output.resolve("result.txt")).contains("targetReached=true"));
        // Lowering the stopping target is allowed; unsuccessful optimization must preserve a successful artifact.
        Trainer.main(new String[] {"--wad", WAD.toString(), "--output", output.toString(), "--resume",
                "--attempts", "2", "--minutes", "1", "--target-seconds", "1"});
        int retained = DemoVerifier.verify(WAD, output.resolve("winning.lmp"), 3, 4200, null);
        assertTrue(retained <= firstTics);
        assertEquals(4, Files.readAllLines(output.resolve("attempts.csv")).size());
        assertTrue(Files.readString(output.resolve("result.txt")).contains("targetReached=false"));
    }
}
