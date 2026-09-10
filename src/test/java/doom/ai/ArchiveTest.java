package doom.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveTest {
    @TempDir Path temp;
    private static Archive.Cell cell(int x) { return new Archive.Cell(x, 0, 0, 3, 2, 3, 8, 0); }
    private TrainingConfig config(int size, int nodes) {
        return new TrainingConfig(Path.of("unused.wad"), temp, 3, 21000, 10, 1, 42, size, nodes, 128, false, null, null, null, 4200, Level.E1M1);
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

    @Test void retainsDistantFrontierWhenEarlyRoomVariantsFillArchive() {
        Archive a = new Archive(4, 100, 128);
        Route root = new Route(null, Action.IDLE, 4, 1);
        for (int facing = 0; facing < 4; facing++)
            a.offer(new Archive.Cell(0, 0, facing, 3, 2, 3, 8, 0), root, 100);
        Route distant = root;
        for (int i = 0; i < 100; i++) distant = new Route(distant, Action.IDLE, 32, i);
        // Use a larger node budget: this tests entry eviction, not a route that cannot fit.
        Archive roomy = new Archive(4, 200, 128);
        for (var e : a.entries()) roomy.offer(e.cell, e.route, e.health);
        assertTrue(roomy.offer(cell(20), distant, 10));
        assertEquals(4, roomy.size());
        assertTrue(roomy.entries().stream().anyMatch(e -> e.cell.x() == 20));
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

    @Test void preservesKeyDetoursAndReopensExplorationAfterPickupAcrossCheckpoints() throws Exception {
        var config = config(10, 100); var progress = new Checkpoint.Progress(config);
        var without = cell(0);
        var with = new Archive.Cell(0, 0, 0, 3, 2, 3, 8, 4);
        Route fast = new Route(null, Action.IDLE, 4, 1);
        Route detour = new Route(fast, Action.IDLE, 20, 2);
        assertTrue(progress.archive.offer(without, fast, 100));
        assertTrue(progress.archive.offer(with, detour, 60), "Key acquisition must survive a slower/lower-health arrival");
        progress.archive.visit(without);
        assertEquals(1, progress.archive.visits(0, 0, 0));
        assertEquals(0, progress.archive.visits(0, 0, 4));
        progress.archive.visit(with);
        Path checkpoint = temp.resolve("keys.bin"); Checkpoint.save(checkpoint, config.identity(), progress);
        var restored = Checkpoint.load(checkpoint, config.identity(), config);
        assertEquals(2, restored.archive.size());
        assertEquals(1, restored.archive.visits(0, 0, 0));
        assertEquals(1, restored.archive.visits(0, 0, 4));
        assertTrue(restored.archive.entries().stream().anyMatch(e -> e.cell.keys() == 4));
        var otherMap = TrainingConfig.parse(new String[] {"--wad", "unused.wad", "--map", "E1M2"});
        assertThrows(java.io.IOException.class, () -> Checkpoint.load(checkpoint, otherMap.identity(), otherMap));
    }

    @Test void retainsArmorAndShellSuppliesAcrossCheckpoint() throws Exception {
        var config = config(10, 100); var p = new Checkpoint.Progress(config);
        Route fast = new Route(null, Action.IDLE, 4, 1), supplied = new Route(fast, Action.IDLE, 12, 2);
        var bare = new Archive.Cell(0, 0, 0, 3, 2, 7, 8, 0, 0, 0);
        var armored = new Archive.Cell(0, 0, 0, 3, 2, 7, 8, 0, 20, 0);
        var armed = new Archive.Cell(0, 0, 0, 3, 2, 7, 8, 0, 0, 1);
        p.archive.offer(bare, fast, 100);
        assertTrue(p.archive.offer(armored, supplied, 80));
        assertTrue(p.archive.offer(armed, supplied, 80));
        Path file = temp.resolve("equipment.bin"); Checkpoint.save(file, config.identity(), p);
        var restored = Checkpoint.load(file, config.identity(), config).archive;
        assertEquals(3, restored.size());
        assertTrue(restored.entries().stream().anyMatch(e -> e.cell.armor() == 20));
        assertTrue(restored.entries().stream().anyMatch(e -> e.cell.shells() == 1));
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
