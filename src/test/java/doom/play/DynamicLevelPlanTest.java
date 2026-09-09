package doom.play;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DynamicLevelPlanTest {
    @Test void thousandSeedsHaveConnectedProgressionALoopAndAnOptionalReward() {
        Set<String> connections = new HashSet<>();
        for (long seed = -500; seed < 500; seed++) {
            DynamicLevel.Layout plan = DynamicLevel.plan(seed);
            assertEquals(9, plan.rooms().size());
            assertTrue(plan.connections().size() >= 9);
            assertEquals(1, plan.connections().stream()
                    .filter(e -> e.a() == DynamicLevel.EXIT || e.b() == DynamicLevel.EXIT).count());
            assertEquals(1, plan.connections().stream()
                    .filter(e -> e.a() == DynamicLevel.REWARD || e.b() == DynamicLevel.REWARD).count());
            DynamicLevel.Room key = plan.rooms().get(plan.keyRoom());
            DynamicLevel.Room exit = plan.rooms().get(DynamicLevel.EXIT);
            boolean[][] locked = DynamicLevel.reachable(plan, false);
            boolean[][] unlocked = DynamicLevel.reachable(plan, true);
            assertTrue(locked[key.y()][key.x()]);
            assertFalse(locked[exit.y()][exit.x()]);
            assertTrue(unlocked[exit.y()][exit.x()]);
            connections.add(plan.connections().toString());
        }
        assertTrue(connections.size() > 20, "Seeds must vary route structure, not just textures");
    }

    @Test void fullWidthSeedsRepeatWithoutConsumingGameplayRandomness() {
        int gameplay = MRandom.prndindex, menu = MRandom.rndindex;
        for (long seed : new long[] {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 48}) {
            DynamicLevel.Layout a = DynamicLevel.plan(seed), b = DynamicLevel.plan(seed);
            assertEquals(a.rooms(), b.rooms());
            assertEquals(a.connections(), b.connections());
            assertEquals(a.areas(), b.areas());
            assertEquals(a.things(), b.things());
            for (int y = 0; y < a.cells().length; y++) assertArrayEquals(a.cells()[y], b.cells()[y]);
        }
        assertNotEquals(DynamicLevel.plan(0).things(), DynamicLevel.plan(1L << 48).things());
        assertEquals(gameplay, MRandom.prndindex);
        assertEquals(menu, MRandom.rndindex);
    }
}
