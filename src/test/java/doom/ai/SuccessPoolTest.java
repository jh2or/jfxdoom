package doom.ai;

import doom.system.*;
import doom.wad.WWad;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SuccessPoolTest {
    @TempDir Path temp;
    @AfterEach void cleanup() {
        GGame.demorecording = Doomstat.demoplayback = Doomstat.singledemo = false;
        GGame.gameaction = GGame.ga_nothing; DMain.advancedemo = false;
        doom.ui.StStuff.stStopped = true; doom.ui.StStuff.sbar = null; WWad.wShutdown();
    }
    @Test void targetIsStrictAndDeadOrUnfinishedTrialsNeverQualify() {
        assertTrue(SuccessPool.qualifies(Episode.Outcome.COMPLETED, 1, 2099, 2100));
        assertFalse(SuccessPool.qualifies(Episode.Outcome.COMPLETED, 1, 2100, 2100));
        assertFalse(SuccessPool.qualifies(Episode.Outcome.COMPLETED, 0, 2000, 2100));
        assertFalse(SuccessPool.qualifies(Episode.Outcome.TIMEOUT, 100, 2000, 2100));
        assertFalse(SuccessPool.qualifies(Episode.Outcome.RUNNING, 100, 2000, 2100));
    }
    @Test void retainsNonRecordSuccessesDeduplicatesReplaysAndValidatesSavedFiles() throws Exception {
        Path wad = Path.of("reference-wads/DOOM.WAD"); assumeTrue(Files.exists(wad));
        doom.play.PMap.changeSectorOverride = false;
        Path demo = temp.resolve("original.lmp");
        try (var input = getClass().getResourceAsStream("/ai/e1m1-learned.lmp")) {
            assumeTrue(input != null); Files.copy(input, demo);
        }
        TrainingConfig c = TrainingConfig.parse(new String[] {"--wad", wad.toString(), "--output", temp.toString(), "--successes", "3", "--target-seconds", "600"});
        Episode episode = new Episode(wad, 3, 21000);
        RunTrace original = RunTrace.play(episode, DemoInput.read(demo, 3, 21000), () -> false);
        RunTrace fast = new RouteOptimizer(new LearnerRandom(1)).propose(episode, original, 0, () -> false);
        assertTrue(fast.route().tics < original.route().tics);
        SuccessPool pool = new SuccessPool(c);
        assertTrue(pool.offer(episode, fast, 1));
        assertTrue(pool.offer(episode, original, 2), "A qualifying slower success must still branch");
        assertFalse(pool.offer(episode, original, 3));
        SuccessPool resumed = new SuccessPool(c);
        assertEquals(2, resumed.size()); assertFalse(resumed.full());
        assertFalse(resumed.offer(episode, fast, 4));
        Files.write(temp.resolve("s001.lmp"), new byte[] {0});
        assertThrows(java.io.IOException.class, () -> new SuccessPool(c));
    }
    @Test void campaignOptionsKeepBranchCountPerParentAndAllowArbitraryStart() {
        var o = CampaignTrainer.parse(new String[] {"--wad", "doom.wad", "--from", "E1M2", "--through", "E1M3", "--branches", "3", "--workers", "3", "--rounds", "8"});
        assertEquals(new Level(1, 2), o.from()); assertEquals(3, o.branches()); assertEquals(8, o.rounds());
        assertThrows(IllegalArgumentException.class, () -> CampaignTrainer.parse(new String[] {"--wad", "doom.wad", "--from", "E2M1", "--through", "E1M3"}));
        assertThrows(IllegalArgumentException.class, () -> TrainingConfig.parse(new String[] {"--wad", "doom.wad", "--successes", "3", "--first-completion"}));
    }
}
