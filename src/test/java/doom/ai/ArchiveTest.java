package doom.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveTest {
    @TempDir Path temp;
    private static Archive.Cell cell(int x) { return new Archive.Cell(x, 0, 0, 3, 2, 3, 8); }
    private TrainingConfig config(int size, int nodes) {
        return new TrainingConfig(Path.of("unused.wad"), temp, 3, 21000, 10, 1, 42, size, nodes, 128, false, null, null, null, 4200);
    }

    @Test void sharesPrefixesPreservesTradeoffsAndEnforcesBothBounds() {
        Archive a = new Archive(4, 4, 128);
        Route root = new Route(null, Action.IDLE, 4, 1);
        Route faster = new Route(root, Action.IDLE, 4, 2);
        Route healthier = new Route(faster, Action.IDLE, 4, 3);
        a.offer(cell(0), faster, 80); a.offer(cell(0), healthier, 99);
        assertEquals(2, a.size()); assertEquals(3, a.nodes());
        assertFalse(a.offer(cell(0), healthier, 70));
        for (int i = 1; i < 50; i++) {
            a.offer(cell(i), new Route(root, Action.IDLE, 4, i), 100);
            assertTrue(a.size() <= 4); assertTrue(a.nodes() <= 4);
        }
        assertTrue(a.entries().stream().allMatch(e -> Route.steps(e.route).size() >= 2));
    }

    @Test void checkpointRetainsSharedRoutesSelectionCountsAndRandomStream() throws Exception {
        TrainingConfig config = config(10, 100);
        Checkpoint.Progress p = new Checkpoint.Progress(config);
        Route root = new Route(null, Action.IDLE, 4, 7);
        p.archive.offer(cell(0), new Route(root, new Action(25, -24, -256, 3), 8, 8), 90);
        p.archive.offer(cell(1), new Route(root, Action.IDLE, 8, 9), 100);
        p.archive.visit(cell(0)); p.archive.select(p.random); p.attempts = 12;
        p.best = root;
        for (int i = 0; i < 120; i++) p.best = new Route(p.best, Action.IDLE, 32, i);
        p.optimizations = 7; p.successes = 2; p.pruned = 5;
        Path path = temp.resolve("archive.bin");
        Checkpoint.save(path, "same-engine", p);
        Checkpoint.Progress loaded = Checkpoint.load(path, "same-engine", config);
        assertEquals(p.archive.nodes(), loaded.archive.nodes());
        assertSame(loaded.archive.entries().get(0).route.parent, loaded.archive.entries().get(1).route.parent);
        assertEquals(12, loaded.attempts);
        assertEquals(p.best.tics, loaded.best.tics);
        assertEquals(p.best.fingerprint, loaded.best.fingerprint);
        assertEquals(7, loaded.optimizations); assertEquals(2, loaded.successes); assertEquals(5, loaded.pruned);
        for (int i = 0; i < 100; i++) {
            Archive.Entry a = p.archive.select(p.random), b = loaded.archive.select(loaded.random);
            assertEquals(a == null ? null : a.cell, b == null ? null : b.cell);
        }
        assertThrows(java.io.IOException.class, () -> Checkpoint.load(path, "different-engine", config));
        byte[] bytes = Files.readAllBytes(path); bytes[bytes.length / 2] ^= 1; Files.write(path, bytes);
        assertThrows(java.io.IOException.class, () -> Checkpoint.load(path, "same-engine", config));
    }

    @Test void validatesInputsAndPreservesSignedDemoCommands() {
        Action a = new Action(-50, -40, -1280, 3);
        assertEquals(a, Action.unpack(a.packed()));
        assertThrows(IllegalArgumentException.class, () -> new Action(51, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Action(0, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Action(0, 0, 0, 128));
        assertThrows(IllegalArgumentException.class, () -> TrainingConfig.parse(new String[] {"--wad", "a", "--minutes", "NaN"}));
        assertThrows(IllegalArgumentException.class, () -> TrainingConfig.parse(new String[] {"--wad", "a", "--unknown", "1"}));
        assertThrows(IllegalArgumentException.class, () -> TrainingConfig.parse(new String[] {"--wad", "a", "--verify", "a.lmp", "--improve", "b.lmp"}));
    }
}
