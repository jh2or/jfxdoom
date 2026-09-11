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

    private void copyLocalDemo(String name, Path target) throws java.io.IOException {
        try (var stream = getClass().getResourceAsStream("/ai/" + name)) {
            assumeTrue(stream != null, "Optional ignored demo: src/test/resources/ai/" + name);
            Files.copy(stream, target);
        }
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

    @Test void suffixOptimizationReplaysThePreservedInputsAndHonorsCancellation() throws Exception {
        Episode e = new Episode(WAD, 3, 21000);
        RunTrace original = RunTrace.play(e, DemoInput.read(originalDemo(), 3, 21000), () -> false);
        var optimizer = new RouteOptimizer(new LearnerRandom(1));
        RunTrace candidate = optimizer.followFrom(e, original, 192, 120, () -> e.tics() >= 120);
        assertEquals(120, candidate.route().tics);
        assertEquals(original.samples().subList(0, 121), candidate.samples());
        assertEquals(RunTrace.actions(original.route()).subList(0, 120), RunTrace.actions(candidate.route()));
        Route.replay(e, candidate.route());
        assertEquals(Episode.Outcome.RUNNING, e.outcome());
        RunTrace cancelled = optimizer.followFrom(e, original, 192, 120, () -> true);
        assertNull(cancelled.route()); assertEquals(0, e.tics());
    }

    @Test void e1m2LearnedDemoPreservesKeysAndSwitchesAndBeatsTwicePar() throws Exception {
        Level level = new Level(1, 2);
        Path original = temp.resolve("e1m2.lmp");
        try (var input = getClass().getResourceAsStream("/ai/e1m2-learned.lmp")) { Files.copy(input, original); }
        Episode e = new Episode(WAD, 3, 21000, level);
        RunTrace learned = RunTrace.play(e, DemoInput.read(original, 3, 21000, level), () -> false);
        assertEquals(Episode.Outcome.COMPLETED, learned.outcome());
        assertTrue(learned.samples().stream().anyMatch(p -> p.keys() != 0));
        assertTrue(learned.samples().stream().anyMatch(p -> p.usedLine() >= 0));
        RunTrace candidate = learned;
        assertEquals(Episode.Outcome.COMPLETED, candidate.outcome());
        assertTrue(candidate.route().tics <= 2 * level.parSeconds() * 35);
        assertNotEquals(0, Observation.keys(e.player()));
        assertEquals(0, e.player().cheats); assertFalse(Doomstat.nomonsters);
        Route.replay(e, candidate.route());
        Path demo = temp.resolve("m2fast.lmp"); Route.demo(demo, 3, candidate.route(), level);
        assertEquals(2, Files.readAllBytes(demo)[3]);
        assertEquals(candidate.route().tics, DemoVerifier.verify(WAD, demo, 3, 5250, candidate.route(), level));
    }

    @Test void e1m3LearnedDemoUsesTheNormalExitBelowPar() throws Exception {
        Level level = new Level(1, 3);
        Path input = temp.resolve("e1m3.lmp");
        try (var stream = getClass().getResourceAsStream("/ai/e1m3-learned.lmp")) { Files.copy(stream, input); }
        Episode e = new Episode(WAD, 3, 21000, level);
        RunTrace best = RunTrace.play(e, DemoInput.read(input, 3, 21000, level), () -> false);
        assertEquals(Episode.Outcome.COMPLETED, best.outcome());
        assertEquals(0, GGame.secretexit);
        assertTrue(best.route().tics <= 2 * level.parSeconds() * 35);
        assertTrue(best.route().tics <= level.parSeconds() * 35, "Shorten the learned completion below par");
        Route.replay(e, best.route());
        assertEquals(Episode.Outcome.COMPLETED, e.outcome());
        assertEquals(0, GGame.secretexit);
        assertNotEquals(0, Observation.keys(e.player()));
        assertTrue(e.player().weaponowned[Doomdef.wp_shotgun]);
        assertEquals(0, e.player().cheats); assertFalse(Doomstat.nomonsters);
        Path demo = temp.resolve("m3fast.lmp"); Route.demo(demo, 3, best.route(), level);
        assertEquals(best.route().tics, DemoVerifier.verify(WAD, demo, 3, 2 * level.parSeconds() * 35, best.route(), level));
        assertEquals(0, GGame.secretexit);
    }

    @Test void e1m4LearnedDemoFinishesBelowParWithBothKeysAndNormalExit() throws Exception {
        Level level = new Level(1, 4);
        Path input = temp.resolve("m4fast.lmp"); // A demo lump must not shadow the E1M4 map marker.
        copyLocalDemo("e1m4-learned.lmp", input);
        Episode e = new Episode(WAD, 3, 21000, level);
        RunTrace learned = RunTrace.play(e, DemoInput.read(input, 3, 21000, level), () -> false);
        assertEquals(Episode.Outcome.COMPLETED, learned.outcome());
        assertTrue(learned.route().tics <= level.parSeconds() * 35);
        assertTrue(e.player().cards[Doomdef.it_bluecard]);
        assertTrue(e.player().cards[Doomdef.it_yellowcard]);
        assertTrue(e.player().health > 0);
        assertEquals(0, e.player().cheats); assertFalse(Doomstat.nomonsters);
        Route.replay(e, learned.route());
        assertEquals(Episode.Outcome.COMPLETED, e.outcome());
        assertEquals(learned.route().tics, DemoVerifier.verify(WAD, input, 3,
                level.parSeconds() * 35, learned.route(), level));
        assertEquals(0, GGame.secretexit);
        GGame.gDoCompleted();
        assertEquals(4, GGame.wminfo.next, "Normal exit continues to E1M5 (zero-based index 4)");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "E1M{0} learned completion")
    @org.junit.jupiter.params.provider.ValueSource(ints = {5, 6, 7, 9})
    void laterEpisodeMapsBeatTwiceParWithNormalInputsAndTransitions(int map) throws Exception {
        Level level = new Level(1, map);
        Path input = temp.resolve("learned.lmp");
        copyLocalDemo("e1m" + map + "-learned.lmp", input);
        Episode e = new Episode(WAD, 3, 21000, level);
        RunTrace learned = RunTrace.play(e, DemoInput.read(input, 3, 21000, level), () -> false);
        assertEquals(Episode.Outcome.COMPLETED, learned.outcome());
        assertTrue(learned.route().tics <= 2 * level.parSeconds() * 35);
        assertTrue(e.player().health > 0);
        assertEquals(map == 5 ? 3 : 7, Observation.keys(e.player()));
        assertEquals(0, e.player().cheats); assertFalse(Doomstat.nomonsters);
        Route.replay(e, learned.route());
        assertEquals(learned.route().tics, DemoVerifier.verify(WAD, input, 3, 21000, learned.route(), level));
        assertEquals(0, GGame.secretexit);
        GGame.gDoCompleted();
        assertEquals(map == 9 ? 3 : map, GGame.wminfo.next);
    }

    @Test void e1m8DefeatsBothBaronsAndReachesTheEpisodeFinale() throws Exception {
        Level level = new Level(1, 8);
        Path input = temp.resolve("m8win.lmp");
        copyLocalDemo("e1m8-learned.lmp", input);
        Episode e = new Episode(WAD, 3, 21000, level);
        RunTrace learned = RunTrace.play(e, DemoInput.read(input, 3, 21000, level), () -> false);
        assertEquals(Episode.Outcome.COMPLETED, learned.outcome());
        assertTrue(learned.route().tics <= 2 * level.parSeconds() * 35);
        assertTrue(e.player().health > 0 && e.player().health <= 10);
        int barons = 0;
        for (var thinker = doom.play.PTick.thinkercap.next; thinker != doom.play.PTick.thinkercap; thinker = thinker.next)
            if (thinker instanceof doom.play.Mobj actor && actor.type == doom.play.Info.MT_BRUISER) {
                barons++; assertTrue(actor.health <= 0);
            }
        assertEquals(2, barons);
        assertEquals(0, e.player().cheats); assertFalse(Doomstat.nomonsters);
        Route.replay(e, learned.route());
        assertEquals(learned.route().tics, DemoVerifier.verify(WAD, input, 3, 21000, learned.route(), level));
        GGame.gDoCompleted();
        assertEquals(GGame.ga_victory, GGame.gameaction);
    }

    @Test void revisedE1m9DemoFinishesWithinTwiceParWithoutRepeatedRedDoorRejections() throws Exception {
        Level level = new Level(1, 9); Path input = temp.resolve("clean9.lmp");
        copyLocalDemo("e1m9-stairs-fixed.lmp", input);
        Episode e = new Episode(WAD, 3, 2 * level.parSeconds() * 35, level);
        var actions = DemoInput.read(input, 3, e.limit(), level);
        int denied = 0;
        for (Action action : actions) {
            e.step(action);
            if (doom.ui.HuStuff.messageCounter == doom.ui.HuStuff.HU_MSGTIMEOUT) {
                var text = doom.ui.HuStuff.wMessage.l[doom.ui.HuStuff.wMessage.cl];
                if (doom.system.Dstrings.PD_REDK.equals(new String(text.l, 0, text.len))) denied++;
            }
        }
        assertEquals(Episode.Outcome.COMPLETED, e.outcome());
        assertEquals(1, denied, "Learn from the first rejection rather than repeating it");
        assertEquals(7, Observation.keys(e.player())); assertTrue(e.player().health > 0);
        assertEquals(0, e.player().cheats); assertFalse(Doomstat.nomonsters);
        RunTrace run = RunTrace.play(e, actions, () -> false);
        Route.replay(e, run.route());
        assertEquals(9693, DemoVerifier.verify(WAD, input, 3, e.limit(), run.route(), level));
        GGame.gDoCompleted(); assertEquals(3, GGame.wminfo.next);
    }

    @Test void replayRebuildsRedDoorMemoryAndUnrelatedProgressDoesNotUnlockIt() throws Exception {
        Level level = new Level(1, 9); Path input = temp.resolve("locked.lmp");
        copyLocalDemo("e1m9-red-door.lmp", input);
        Episode e = new Episode(WAD, 3, 21000, level);
        RunTrace prefix = RunTrace.play(e, DemoInput.read(input, 3, 21000, level), () -> false);
        assertEquals(Episode.Outcome.RUNNING, prefix.outcome()); assertEquals(544, e.tics());
        assertFalse(e.lockedDoors().isEmpty(), "Capture the real engine's first red-key denial");
        assertEquals(2, Observation.keys(e.player()));
        var locked = e.lockedDoors();
        e.player().killcount++; assertEquals(locked, Observation.read(e).lockedDoors());
        e.player().cards[Doomdef.it_bluecard] = true; assertEquals(locked, e.lockedDoors());
        e.player().cards[Doomdef.it_redcard] = true; assertTrue(e.lockedDoors().isEmpty());
        Route.replay(e, prefix.route()); assertEquals(locked.size(), e.lockedDoors().size());
        LocalController controller = new LocalController();
        for (int tic = 0; tic < 70; tic++) {
            var o = Observation.read(e); var door = e.lockedDoors().iterator().next();
            var action = controller.towards(o, new MotionProbe(o),
                    ((long) door.v1.x + door.v2.x) / 131072., ((long) door.v1.y + door.v2.y) / 131072., e.tics(), false).action();
            e.step(action);
            if (doom.ui.HuStuff.messageCounter == doom.ui.HuStuff.HU_MSGTIMEOUT) {
                var text = doom.ui.HuStuff.wMessage.l[doom.ui.HuStuff.wMessage.cl];
                assertNotEquals(doom.system.Dstrings.PD_REDK, new String(text.l, 0, text.len),
                        "Other nearby surfaces may be used, but do not hammer the denied red door again");
            }
        }
        e.reset(); assertTrue(e.lockedDoors().isEmpty());
    }

    @Test void shortensAnUnfinishedE1m3RouteWhilePreservingTheKeyAndHealth() throws Exception {
        Level level = new Level(1, 3);
        Path input = temp.resolve("keyslow.lmp");
        try (var stream = getClass().getResourceAsStream("/ai/e1m3-bluekey.lmp")) { Files.copy(stream, input); }
        Episode e = new Episode(WAD, 3, 21000, level);
        RunTrace learned = RunTrace.play(e, DemoInput.read(input, 3, 21000, level), () -> false);
        assertEquals(Episode.Outcome.RUNNING, learned.outcome(), "An intermediate discovery, not a completed demo");
        int health = e.player().health, keys = Observation.keys(e.player());
        assertNotEquals(0, keys);
        Route shorter = PrefixOptimizer.shorten(e, learned.route(), new RouteOptimizer(new LearnerRandom(1)), 13, () -> false);
        assertTrue(shorter.tics < 30 * 35);
        Route.replay(e, shorter);
        assertEquals(Episode.Outcome.RUNNING, e.outcome());
        assertEquals(keys, Observation.keys(e.player()));
        assertTrue(e.player().health >= health);
        assertSame(shorter, PrefixOptimizer.shorten(e, shorter, new RouteOptimizer(new LearnerRandom(1)), 0, () -> true));
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

    @Test void clearsARealBarrelFromOutsideTheBlastRadius() throws Exception {
        Episode e = new Episode(WAD, 3, 21000);
        Observation start = Observation.read(e);
        MotionProbe probe = new MotionProbe(start, true);
        double angle = Double.NaN;
        for (int direction = 0; direction < 16; direction++) {
            double candidate = direction * Math.PI / 8;
            if (probe.distance(start.x() + 208 * Math.cos(candidate), start.y() + 208 * Math.sin(candidate)) >= 208) {
                angle = candidate; break;
            }
        }
        assertTrue(Double.isFinite(angle), "The fixture needs a clear firing lane");
        var barrel = doom.play.PMobj.pSpawnMobj((int) ((start.x() + 208 * Math.cos(angle)) * 65536),
                (int) ((start.y() + 208 * Math.sin(angle)) * 65536), doom.play.PMobj.ONFLOORZ, doom.play.Info.MT_BARREL);
        BarrelController controller = new BarrelController();
        boolean fired = false;
        for (int tic = 0; tic < 280 && barrel.health > 0 && e.outcome() == Episode.Outcome.RUNNING; tic++) {
            var o = Observation.read(e);
            Action action = controller.choose(o, start.x() + 320 * Math.cos(angle), start.y() + 320 * Math.sin(angle), tic);
            if (action == null) break;
            if ((action.buttons() & Doomdef.BT_ATTACK) != 0) {
                fired = true;
                assertTrue(Math.hypot(barrel.x / 65536. - o.x(), barrel.y / 65536. - o.y()) >= 176);
            }
            e.step(action);
        }
        assertTrue(fired, "Fire at the observed barrel"); assertTrue(barrel.health <= 0, "Destroy it through real weapon damage");
        for (int tic = 0; tic < 35 && e.outcome() == Episode.Outcome.RUNNING; tic++) e.step(Action.IDLE);
        assertTrue(e.player().health > 0);
        assertEquals(0, e.player().cheats);
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
