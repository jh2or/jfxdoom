package doom.ai;

import doom.play.*;
import doom.render.Line;
import doom.system.Doomdef;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SurvivalStrategyTest {
    @TempDir Path temp;
    private Line[] lines;
    @BeforeEach void setup() { lines = PSetup.lines; PSetup.lines = new Line[0]; }
    @AfterEach void cleanup() { PSetup.lines = lines; }

    private RunTrace trace(int tics, int low, int end, Episode.Outcome outcome) {
        var samples = List.of(new RunTrace.Sample(0, 0, 0, 0, 0, 100, 0),
                new RunTrace.Sample(1, 0, 0, 0, 0, low, 0), new RunTrace.Sample(tics, 0, 0, 0, 0, end, 0));
        Route route = null;
        for (int remaining = tics; remaining > 0; remaining -= 32) route = new Route(route, Action.IDLE, Math.min(32, remaining), 0);
        return new RunTrace(route, samples, outcome, null);
    }
    @Test void slowerSaferCompletionWinsButHealthyTimeoutDoesNot() {
        RunTrace fast = trace(100, 5, 100, Episode.Outcome.COMPLETED);
        RunTrace safe = trace(200, 70, 80, Episode.Outcome.COMPLETED);
        assertTrue(LearningStrategy.SURVIVAL.better(safe, fast));
        assertFalse(LearningStrategy.SPEED.better(safe, fast));
        assertFalse(LearningStrategy.SURVIVAL.better(trace(300, 100, 100, Episode.Outcome.TIMEOUT), fast));
        assertFalse(LearningStrategy.SURVIVAL.better(trace(10, 0, 0, Episode.Outcome.DEAD), null));
        assertTrue(LearningStrategy.SURVIVAL.better(trace(100, 70, 80, Episode.Outcome.COMPLETED), safe));
    }
    @Test void recordsCriticalHealthEvenWhenLaterHealedAndIncludesArmorLoss() {
        SurvivalMetrics m = new SurvivalMetrics(100, 50);
        m.record(20, 10); m.record(100, 100); m.record(90, 90);
        var s = m.summary();
        assertEquals(20, s.minimumHealth()); assertEquals(90, s.healthLost());
        assertEquals(50, s.armorLost()); assertEquals(1, s.criticalTics());
        assertEquals(90, s.exitHealth()); assertEquals(90, s.exitArmor());
    }
    private Observation observation(Mobj enemy) {
        return new Observation(0, 0, 0, 100, 50, 3, 8, enemy, 0, 56, 16, 0, 0, 1, 0);
    }
    @Test void engagesOrdinaryDistantThreatWithoutAdvancingAndBoundsUnproductiveCombat() {
        Mobj enemy = new Mobj(); enemy.type = Info.MT_POSSESSED; enemy.health = 60; enemy.x = 450 * 65536;
        var o = observation(enemy); var c = new SurvivalCombat();
        Action a = c.choose(o, new MotionProbe(o), 0);
        assertNotNull(a); assertEquals(0, a.forward()); assertNotEquals(0, a.side());
        assertNotEquals(0, a.buttons() & Doomdef.BT_ATTACK);
        assertNull(c.choose(o, new MotionProbe(o), 175));
        assertNull(c.choose(o, new MotionProbe(o), 384));
        assertNotNull(c.choose(o, new MotionProbe(o), 385));
    }
    @Test void prioritizesHitscanAndNearbyMeleeAndRetreatsFromCloseEnemy() {
        Mobj imp = new Mobj(); imp.type = Info.MT_TROOP;
        Mobj gunner = new Mobj(); gunner.type = Info.MT_SHOTGUY;
        assertTrue(SurvivalCombat.priorityDistance(gunner, 300) < SurvivalCombat.priorityDistance(imp, 200));
        assertTrue(SurvivalCombat.priorityDistance(imp, 64) < SurvivalCombat.priorityDistance(gunner, 300));
        imp.x = 80 * 65536; imp.health = 60;
        var o = observation(imp);
        assertTrue(new SurvivalCombat().choose(o, new MotionProbe(o), 0).forward() < 0);
    }
    @Test void dangerMemoryReducesRetryWeightAndSurvivesCheckpoint() throws Exception {
        var config = TrainingConfig.parse(new String[]{"--wad", "unused.wad", "--strategy", "survival"});
        var progress = new Checkpoint.Progress(config);
        var cell = new Archive.Cell(1, 2, 0, 4, 2, 3, 8, 0);
        progress.archive.offer(cell, new Route(null, Action.IDLE, 1, 0), 100);
        var entry = progress.archive.entries().getFirst(); double before = progress.archive.weight(entry);
        progress.archive.recordDanger(cell, 40, true);
        assertTrue(progress.archive.weight(entry) < before);
        Path checkpoint = temp.resolve("risk.bin"); Checkpoint.save(checkpoint, config.identity(), progress);
        var restored = Checkpoint.load(checkpoint, config.identity(), config);
        assertEquals(progress.archive.danger, restored.archive.danger);
        assertEquals(progress.archive.weight(entry), restored.archive.weight(restored.archive.entries().getFirst()));
        var speed = TrainingConfig.parse(new String[]{"--wad", "unused.wad"});
        assertThrows(java.io.IOException.class, () -> Checkpoint.load(checkpoint, speed.identity(), speed));
    }
    @Test void perturbationsAreReproducibleDistinctLegalInputs() {
        Set<List<Action>> unique = new HashSet<>();
        for (int i = 0; i < 128; i++) {
            var actions = StrategyEvaluation.perturbation(2, i);
            assertEquals(actions, StrategyEvaluation.perturbation(2, i));
            assertTrue(unique.add(actions));
            for (Action action : actions) assertEquals(action, Action.unpack(action.packed()));
        }
    }
    @Test void localSupplyDetoursRespectThreatsReachabilityDeadlineAndAggregateBudget() {
        Mobj medkit = new Mobj(); medkit.sprite = Info.SPR_MEDI; medkit.flags = Mobj.MF_SPECIAL;
        medkit.radius = 20 * 65536; medkit.x = 180 * 65536;
        Observation hurt = new Observation(0, 0, 0, 70, 50, 3, 8, null, 0, 56, 16, 0, 0, 1, 0,
                Inventory.PISTOL_START, List.of(medkit));
        assertNull(new PickupController().choose(hurt, new MotionProbe(hurt), 250, 0, 0));
        PickupController c = new PickupController(true);
        assertSame(medkit, c.choose(hurt, new MotionProbe(hurt), 250, 0, 0));
        assertNull(c.choose(hurt, new MotionProbe(hurt), 250, 0, 105));
        assertNull(c.choose(hurt, new MotionProbe(hurt), 250, 0, 106));
        PSetup.lines = new Line[] {MotionProbeTest.line(100, -100, 100, 100)};
        assertNull(new PickupController(true).choose(hurt, new MotionProbe(hurt), 250, 0, 0));
        PSetup.lines = new Line[0];
        Mobj enemy = new Mobj(); enemy.health = 60;
        Observation threatened = new Observation(0, 0, 0, 70, 50, 3, 8, enemy, 0, 56, 16, 0, 0, 1, 0,
                Inventory.PISTOL_START, List.of(medkit));
        assertNull(new PickupController(true).choose(threatened, new MotionProbe(threatened), 250, 0, 0));
        c.reset();
        // A succession of reachable items must not create an unlimited collection loop.
        for (int tic = 0; tic <= 430; tic += 2) {
            Mobj next = new Mobj(); next.sprite = Info.SPR_MEDI; next.flags = Mobj.MF_SPECIAL;
            next.x = 80 * 65536; next.radius = 20 * 65536;
            Observation current = new Observation(0, 0, 0, 70, 50, 3, 8, null, 0, 56, 16, 0, 0, 1, 0,
                    Inventory.PISTOL_START, List.of(next));
            Mobj chosen = c.choose(current, new MotionProbe(current), 250, 0, tic);
            if (tic < 420) assertSame(next, chosen); else assertNull(chosen);
        }
        assertSame(medkit, c.choose(hurt, new MotionProbe(hurt), 250, 0, 2100));
    }

    @Test void progressDeadlineIgnoresCirclingButAllowsCombatAndNewGround() {
        Mobj enemy = new Mobj(); enemy.health = 60;
        var initial = observation(enemy);
        ProgressWatch watch = new ProgressWatch(initial, 0);
        watch.observe(initial, 1049); assertTrue(watch.stalled(1050));
        enemy.health = 50; watch.observe(initial, 1050); assertFalse(watch.stalled(1050));
        var moved = new Observation(100, 0, 0, 100, 50, 3, 8, enemy, 0, 56, 16, 0, 0, 1, 0);
        watch.observe(moved, 1500); watch.observe(initial, 2000);
        assertFalse(watch.stalled(2549)); assertTrue(watch.stalled(2550));
    }

    @Test void validatesStrategyAndEvaluationModes() {
        assertEquals(LearningStrategy.SPEED, TrainingConfig.parse(new String[]{"--wad", "a"}).strategy());
        assertThrows(IllegalArgumentException.class, () -> TrainingConfig.parse(new String[]{"--wad", "a", "--strategy", "unknown"}));
        assertThrows(IllegalArgumentException.class, () -> TrainingConfig.parse(new String[]{"--wad", "a", "--evaluate", "a.lmp", "--resume"}));
    }
}
