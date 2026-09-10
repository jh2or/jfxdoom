package doom.ai;

import doom.play.*;
import doom.system.*;
import doom.wad.WWad;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EpisodeTest {
    private final Path wad = Path.of("reference-wads/DOOM.WAD");
    @TempDir Path temp;
    private Episode episode;

    @BeforeEach void boot() throws Exception {
        assumeTrue(Files.isRegularFile(wad), "Requires local Doom 1 WAD");
        PMap.changeSectorOverride = false; // Synthetic mover tests may leave their override armed.
        episode = new Episode(wad, 3, 21000);
    }
    @AfterEach void cleanup() throws Exception {
        GGame.demorecording = Doomstat.demoplayback = Doomstat.singledemo = false;
        DMain.advancedemo = false;
        GGame.gameaction = GGame.ga_nothing;
        doom.ui.StStuff.stStopped = true;
        doom.ui.StStuff.sbar = null;
        WWad.resetForTests();
    }

    @Test void selectedMapResetsToItsOwnPistolStartAndReplaysDeterministically() throws Exception {
        int firstX = episode.player().mo.x, firstY = episode.player().mo.y;
        episode = new Episode(wad, 3, 21000, new Level(1, 2));
        assertEquals(1, Doomstat.gameepisode); assertEquals(2, Doomstat.gamemap);
        assertTrue(firstX != episode.player().mo.x || firstY != episode.player().mo.y);
        long initial = Fingerprint.capture();
        Action action = new Action(50, 40, 256, 0);
        for (int i = 0; i < 12; i++) episode.step(action);
        Route route = new Route(null, action, 12, Fingerprint.capture());
        episode.player().cards[Doomdef.it_redcard] = true;
        episode.reset();
        assertEquals(2, Doomstat.gamemap);
        assertEquals(0, Observation.keys(episode.player()));
        assertEquals(100, episode.player().health);
        assertEquals(50, episode.player().ammo[0]);
        assertEquals(initial, Fingerprint.capture());
        Route.replay(episode, route);
        assertEquals(route.fingerprint, Fingerprint.capture());
        assertEquals(Episode.Outcome.RUNNING, episode.outcome());
    }

    @Test void resetClearsGameplayInputAndRandomContamination() {
        long initial = Fingerprint.capture();
        int x = episode.player().mo.x;
        GGame.gamekeydown[GGame.keyUp] = true;
        episode.step(Action.IDLE);
        assertEquals(x, episode.player().mo.x, "Supplied idle must override keyboard movement");
        episode.step(new Action(50, 0, 0, 0));
        assertNotEquals(x, episode.player().mo.x);
        episode.player().cheats = Player.CF_GODMODE;
        episode.player().ammo[0] = 999;
        doom.ui.MMenu.menuactive = true;
        Doomstat.paused = true;
        Doomstat.fastparm = Doomstat.respawnparm = Doomstat.nomonsters = true;
        MRandom.pRandom();
        episode.reset();
        assertEquals(initial, Fingerprint.capture());
        assertEquals(0, episode.player().cheats);
        assertFalse(Doomstat.paused);
        assertFalse(Doomstat.fastparm);
        assertFalse(Doomstat.nomonsters);
        assertEquals(0, episode.tics());
    }

    @Test void substantialRecordingAndPrefixHandoffMatchEveryTic() throws Exception {
        List<Action> actions = new ArrayList<>();
        List<Long> states = new ArrayList<>();
        // Real-WAD gameplay: a stationary player survives while turning/firing at approaching actors.
        // Use skill 1 for a long regression interval, independently of training's default skill 3.
        episode = new Episode(wad, 1, 21000);
        GGame.gRecordDemo(temp.resolve("round").toString()); GGame.gBeginRecording();
        for (int i = 0; i < 2100 && episode.outcome() == Episode.Outcome.RUNNING; i++) {
            Action a = new Action(0, 0, i % 200 < 30 ? -256 : 0, i % 9 == 0 ? Doomdef.BT_USE : Doomdef.BT_ATTACK);
            actions.add(a); episode.step(a); states.add(Fingerprint.capture());
        }
        assertTrue(actions.size() >= 350, "Must exercise more than the existing six-second sample");
        GGame.gCheckDemoStatus();
        assertTrue(Files.isRegularFile(temp.resolve("round.lmp")));
        episode.reset();
        for (int i = 0; i < actions.size(); i++) {
            episode.step(actions.get(i)); assertEquals(states.get(i).longValue(), Fingerprint.capture(), "direct tic " + i);
        }
        episode.reset();
        WWad.resetForTests(); DMain.dResetWadFiles();
        // Playback takes precedence even with -warp; external filename is loaded automatically.
        DMain.dDoomMain(new String[] {"-iwad", wad.toString(), "-nosound", "-warp", "1", "1",
                "-playdemo", temp.resolve("round.lmp").toString()});
        assertEquals(GGame.ga_playdemo, GGame.gameaction);
        assertFalse(DMain.advancedemo);
        GGame.gDoPlayDemo(); GGame.gDoLoadLevel(); PTick.gametic = 0;
        int split = actions.size() / 2;
        for (int i = 0; i < actions.size(); i++) {
            if (i == split) { Doomstat.demoplayback = false; GGame.demoBuffer = null; }
            GGame.gTicker(actions.get(i).command());
            assertEquals(states.get(i).longValue(), Fingerprint.capture(), "demo/handoff tic " + i);
        }
    }

    @Test void timeoutAndDeathAreTerminalAndDemoEndIsNotSuccess() throws Exception {
        episode = new Episode(wad, 3, 3);
        episode.step(Action.IDLE); episode.step(Action.IDLE);
        assertEquals(Episode.Outcome.TIMEOUT, episode.step(Action.IDLE));
        assertThrows(IllegalStateException.class, () -> episode.step(Action.IDLE));
        episode.reset();
        episode.player().health = 0;
        episode.player().playerstate = Player.PST_DEAD;
        assertEquals(Episode.Outcome.DEAD, episode.step(Action.IDLE));
        assertEquals(Episode.Outcome.COMPLETED, Episode.classify(true, false, 3, 3));
        assertEquals(Episode.Outcome.DEAD, Episode.classify(false, true, 3, 3));
        Route.demo(temp.resolve("empty.lmp"), 3, null);
        assertThrows(java.io.IOException.class, () -> DemoVerifier.verify(wad, temp.resolve("empty.lmp"), 3, 21000, null));
    }

    @Test void learnedDemoActuallyActivatesTheExitOnTheMatchingWad() throws Exception {
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(wad)));
        assumeTrue(digest.equals("6fdf361847b46228cfebd9f3af09cd844282ac75f3edbb61ca4cb27103ce2e7f"), "Fixture requires matching WAD");
        Path demo = temp.resolve("winning.lmp");
        try (var input = getClass().getResourceAsStream("/ai/e1m1-learned.lmp")) { Files.copy(input, demo); }
        assertEquals(14568, DemoVerifier.verify(wad, demo, 3, 21000, null));
    }

    @Test void prefixCountsTowardLimitAndCannotContinueAfterTimeout() throws Exception {
        episode = new Episode(wad, 3, 8);
        for (int i = 0; i < 4; i++) episode.step(Action.IDLE);
        Route prefix = new Route(null, Action.IDLE, 4, Fingerprint.capture());
        Route.replay(episode, prefix);
        assertEquals(4, episode.tics());
        for (int i = 0; i < 4; i++) episode.step(Action.IDLE);
        assertEquals(Episode.Outcome.TIMEOUT, episode.outcome());
        Route bad = new Route(null, Action.IDLE, 4, prefix.fingerprint ^ 1);
        assertThrows(IllegalStateException.class, () -> Route.replay(episode, bad));
    }
}
