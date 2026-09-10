package doom.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LevelTest {
    @TempDir Path temp;
    @Test void selectsMapAndKeepsE1M1AsDefault() {
        var first = TrainingConfig.parse(new String[] {"--wad", "doom.wad"});
        var second = TrainingConfig.parse(new String[] {"--wad", "doom.wad", "--map", "e1m2", "--target-seconds", "150"});
        assertEquals(Level.E1M1, first.level());
        assertEquals(new Level(1, 2), second.level());
        assertEquals(75, second.level().parSeconds());
        assertEquals(5250, second.targetTics());
        assertEquals(Path.of("training/e1m2"), second.output());
        assertNotEquals(first.identity(), second.identity());
        for (String map : List.of("E0M1", "E1M0", "E4M1", "E1M10", "MAP02"))
            assertThrows(IllegalArgumentException.class, () -> Level.parse(map));
    }

    @Test void demoHeaderIdentifiesTheTargetAndWrongMapIsRejected() throws Exception {
        Level target = new Level(1, 2);
        Route route = new Route(null, new Action(50, 40, 256, 0), 3, 0);
        Path demo = temp.resolve("map2.lmp"); Route.demo(demo, 3, route, target);
        assertEquals(2, Files.readAllBytes(demo)[3]);
        assertEquals(3, DemoInput.read(demo, 3, 21000, target).size());
        assertThrows(java.io.IOException.class, () -> DemoInput.read(demo, 3, 21000));
        assertThrows(java.io.IOException.class, () -> DemoVerifier.verify(Path.of("unused.wad"), demo, 3, 21000, null));
    }

    @Test void retainedSwitchRequiresAnObservedActivationBeforeAdvancing() {
        var path = List.of(new RunTrace.Sample(0, 0, 0, 0, 0, 100, 0),
                new RunTrace.Sample(1, 10, 0, 0, 1, 100, 0, 12),
                new RunTrace.Sample(2, 20, 0, 0, 1, 100, 4));
        assertEquals(1, RouteOptimizer.interactionFrontier(path, 0, 0, java.util.Set.of()));
        assertEquals(1, RouteOptimizer.interactionFrontier(path, 1, 0, java.util.Set.of()));
        assertEquals(2, RouteOptimizer.interactionFrontier(path, 0, 0, java.util.Set.of(12)));
    }

    @Test void shortcutsCannotSkipAnUncollectedKey() {
        var path = List.of(new RunTrace.Sample(0, 0, 0, 0, 0, 100, 0),
                new RunTrace.Sample(1, 10, 0, 0, 0, 100, 0),
                new RunTrace.Sample(2, 20, 0, 0, 0, 100, 4),
                new RunTrace.Sample(3, 0, 0, 0, 0, 100, 4));
        assertEquals(2, RouteOptimizer.keyFrontier(path, 0, 0));
        assertEquals(2, RouteOptimizer.keyFrontier(path, 2, 0));
        assertEquals(3, RouteOptimizer.keyFrontier(path, 0, 4));
    }
}
