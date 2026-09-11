package doom.ai;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TrialImportTest {
    @TempDir Path temp;
    @AfterEach void cleanup() {
        doom.ui.StStuff.stStopped = true; doom.ui.StStuff.sbar = null;
        doom.system.GGame.gameaction = doom.system.GGame.ga_nothing;
        doom.wad.WWad.wShutdown();
    }

    @Test void replaysInputsAndArchivesOnlySurvivingPrefixes() throws Exception {
        Path wad = Path.of("reference-wads/DOOM.WAD"); assumeTrue(Files.isRegularFile(wad));
        Path demo = temp.resolve("seed.lmp");
        Route.demo(demo, 3, new Route(null, Action.IDLE, 8, 0));
        TrainingConfig c = TrainingConfig.parse(new String[]{"--wad",wad.toString(),"--seed-demo",demo.toString(),
                "--episode-tics","8","--output",temp.resolve("run").toString()});
        Episode e = new Episode(wad, 3, 8);
        Checkpoint.Progress progress = new Checkpoint.Progress(c);
        Trainer.importTrial(e, c, progress);
        assertEquals(Episode.Outcome.TIMEOUT, e.outcome());
        assertNull(progress.best);
        assertEquals(1, progress.archive.size());
        Route route = progress.archive.entries().getFirst().route;
        assertEquals(4, route.tics);
        Route.replay(e, route);
        assertEquals(Episode.Outcome.RUNNING, e.outcome());
        assertEquals(100, e.player().health);
        doom.wad.WWad.wShutdown();
    }

    @Test void seedImportCannotBeCombinedWithOtherModesOrResume() {
        assertThrows(IllegalArgumentException.class, () -> TrainingConfig.parse(new String[]{
                "--wad","unused.wad","--seed-demo","seed.lmp","--resume"}));
        assertThrows(IllegalArgumentException.class, () -> TrainingConfig.parse(new String[]{
                "--wad","unused.wad","--seed-demo","seed.lmp","--improve","win.lmp"}));
    }
}
