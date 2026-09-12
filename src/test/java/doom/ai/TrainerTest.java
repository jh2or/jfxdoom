package doom.ai;

import doom.system.*;
import doom.wad.WWad;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TrainerTest {
    @TempDir Path temp;
    private static final Path WAD = Path.of("reference-wads/DOOM.WAD");
    @BeforeEach void requireWad() {
        assumeTrue(Files.isRegularFile(WAD), "Requires local Doom 1 WAD");
        doom.play.PMap.changeSectorOverride = false;
    }
    @AfterEach void cleanup() {
        doom.ui.StStuff.stStopped = true;
        doom.ui.StStuff.sbar = null;
        GGame.gameaction = GGame.ga_nothing;
        GGame.demorecording = Doomstat.demoplayback = Doomstat.singledemo = false;
        DMain.advancedemo = false;
        WWad.wShutdown();
    }

    private String[] args(Path output, String attempts, boolean resume) {
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of("--wad", WAD.toString(),
                "--output", output.toString(), "--attempts", attempts, "--minutes", "1",
                "--episode-tics", "256", "--archive-size", "64", "--node-limit", "128"));
        if (resume) args.add("--resume");
        return args.toArray(String[]::new);
    }

    @Test void resumedTrainingExactlyMatchesUninterruptedTraining() throws Exception {
        Path split = temp.resolve("split"), continuous = temp.resolve("continuous");
        Trainer.main(args(split, "1", false));
        Trainer.main(args(split, "3", true));
        Trainer.main(args(continuous, "4", false));
        assertArrayEquals(Files.readAllBytes(continuous.resolve("archive.bin")), Files.readAllBytes(split.resolve("archive.bin")));
        assertEquals(5, Files.readAllLines(split.resolve("attempts.csv")).size());
        assertFalse(Files.exists(split.resolve("result.txt")), "Timeouts must not report success");
        assertThrows(java.io.IOException.class, () -> Trainer.main(args(split, "1", false)));
    }

    @Test void survivalResumeRetainsDeterministicLearningAndSafetyColumns() throws Exception {
        Path split = temp.resolve("survival-split"), continuous = temp.resolve("survival-continuous");
        for (var call : new Object[][] {{split, "1", false}, {split, "3", true}, {continuous, "4", false}}) {
            var options = new java.util.ArrayList<>(java.util.List.of(args((Path) call[0], (String) call[1], (boolean) call[2])));
            options.addAll(java.util.List.of("--strategy", "survival"));
            Trainer.main(options.toArray(String[]::new));
        }
        assertArrayEquals(Files.readAllBytes(continuous.resolve("archive.bin")), Files.readAllBytes(split.resolve("archive.bin")));
        String header = Files.readAllLines(split.resolve("attempts.csv")).getFirst();
        assertTrue(header.contains("minimum_health,health_lost,armor_lost,critical_tics,exit_armor"));
        assertFalse(Files.exists(split.resolve("winning.lmp")));
    }

    @Test void evaluationUsesFullBudgetForBothStrategiesAndHonorsCancellation() throws Exception {
        Episode episode = new Episode(WAD, 3, 400);
        RunTrace shortPath = RunTrace.play(episode, java.util.List.of(Action.IDLE), () -> false);
        for (LearningStrategy strategy : LearningStrategy.values()) {
            RouteOptimizer optimizer = new RouteOptimizer(new LearnerRandom(2), strategy);
            RunTrace result = optimizer.evaluate(episode, shortPath, java.util.List.of(), () -> false);
            assertEquals(Episode.Outcome.TIMEOUT, result.outcome());
            assertEquals(400, result.metrics().tics(), "Neither route length nor stalled progress may prune evaluation");
            RunTrace cancelled = optimizer.evaluate(episode, shortPath, java.util.List.of(), () -> true);
            assertEquals(Episode.Outcome.RUNNING, cancelled.outcome());
            assertEquals(0, cancelled.metrics().tics());
        }
    }

    @Test void wallBudgetSavesWithoutInventingEpisodeSuccess() throws Exception {
        Path output = temp.resolve("budget");
        Trainer.main(new String[] {"--wad", WAD.toString(), "--output", output.toString(), "--minutes", "0.000000001"});
        assertTrue(Files.size(output.resolve("archive.bin")) > 0);
        assertFalse(Files.exists(output.resolve("result.txt")));
        assertFalse(Files.exists(output.resolve("winning.lmp")));
    }
}
