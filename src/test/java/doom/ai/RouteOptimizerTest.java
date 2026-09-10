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
        assertTrue(candidate.route().tics < 771, "Improve on the original 22.03-second controller");
        assertTrue(RunTrace.actions(candidate.route()).stream().anyMatch(a -> a.forward() != 0 && a.side() != 0),
                "The verified route must actually use combined forward and strafe movement");
        assertTrue(candidate.metrics().blockedTics() < original.metrics().blockedTics() / 10);
        assertTrue(candidate.metrics().idleTics() < 350, "Do not replace wall pushing with idle waiting");
        assertEquals(0, e.player().cheats);
        assertFalse(Doomstat.nomonsters);
        Route.replay(e, candidate.route());
        assertEquals(Episode.Outcome.COMPLETED, e.outcome());
        Path demo = temp.resolve("fast.lmp"); Route.demo(demo, 3, candidate.route());
        assertEquals(candidate.route().tics, DemoVerifier.verify(WAD, demo, 3, 4200, candidate.route()));
    }

    @Test void e1m2PreservesKeysAndSwitchesAndBeatsTwicePar() throws Exception {
        Level level = new Level(1, 2);
        Path original = temp.resolve("e1m2.lmp");
        try (var input = getClass().getResourceAsStream("/ai/e1m2-learned.lmp")) { Files.copy(input, original); }
        Episode e = new Episode(WAD, 3, 21000, level);
        RunTrace learned = RunTrace.play(e, DemoInput.read(original, 3, 21000, level), () -> false);
        assertEquals(Episode.Outcome.COMPLETED, learned.outcome());
        assertTrue(learned.samples().stream().anyMatch(p -> p.keys() != 0));
        assertTrue(learned.samples().stream().anyMatch(p -> p.usedLine() >= 0));
        // The sixth route-following variant retains observed switch interactions and allows fighting.
        RunTrace candidate = new RouteOptimizer(new LearnerRandom(1)).propose(e, learned, 15, () -> false);
        assertEquals(Episode.Outcome.COMPLETED, candidate.outcome());
        assertTrue(candidate.route().tics <= 2 * level.parSeconds() * 35);
        assertNotEquals(0, Observation.keys(e.player()));
        assertEquals(0, e.player().cheats); assertFalse(Doomstat.nomonsters);
        Route.replay(e, candidate.route());
        Path demo = temp.resolve("m2fast.lmp"); Route.demo(demo, 3, candidate.route(), level);
        assertEquals(2, Files.readAllBytes(demo)[3]);
        assertEquals(candidate.route().tics, DemoVerifier.verify(WAD, demo, 3, 5250, candidate.route(), level));
    }

    @Test void killsShotgunnerCollectsItsDropAndArmorThenFiresTheShotgun() throws Exception {
        Episode e = new Episode(WAD, 3, 21000);
        var player = e.player().mo;
        int x = player.x, y = player.y;
        var soldier = doom.play.PMobj.pSpawnMobj(x, y + 96 * 65536, doom.play.PMobj.ONFLOORZ, doom.play.Info.MT_SHOTGUY);
        doom.play.PMobj.pSpawnMobj(x, y + 48 * 65536, doom.play.PMobj.ONFLOORZ, doom.play.Info.MT_MISC0);
        LocalController controller = new LocalController();
        boolean armored = false, owned = false, fired = false;
        doom.play.Mobj second = null;
        for (int i = 0; i < 350 && e.outcome() == Episode.Outcome.RUNNING; i++) {
            Observation o = Observation.read(e);
            int shells = e.player().ammo[Doomdef.am_shell];
            var burst = controller.towards(o, new MotionProbe(o), x / 65536., y / 65536. + 200, e.tics(), true);
            e.step(burst.action());
            armored |= e.player().armorpoints > 0;
            owned |= e.player().weaponowned[Doomdef.wp_shotgun];
            if (owned && second == null) second = doom.play.PMobj.pSpawnMobj(x, y + 180 * 65536,
                    doom.play.PMobj.ONFLOORZ, doom.play.Info.MT_TROOP);
            if (e.player().readyweapon == Doomdef.wp_shotgun && e.player().ammo[Doomdef.am_shell] < shells) { fired = true; break; }
        }
        assertTrue(soldier.health <= 0, "Kill the soldier using ordinary inputs");
        assertTrue(owned, "Collect the shotgun actually dropped by the engine");
        assertTrue(armored, "Collect the nearby armor through ordinary movement");
        assertTrue(fired, "Fire a shotgun shell in the following encounter");
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
