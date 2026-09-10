package doom.ai;

import doom.play.PSetup;
import doom.render.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class MotionProbeTest {
    private Line[] original;
    @BeforeEach void keepWorld() { original = PSetup.lines; }
    @AfterEach void restoreWorld() { PSetup.lines = original; }

    static Observation at(double x, double y) {
        return new Observation(x, y, 0, 100, 50, 3, 8, null, 0, 56, 16, 0, 0, 1);
    }
    static Line line(int ax, int ay, int bx, int by) {
        Line l = new Line();
        l.v1 = new Vertex(); l.v1.x = ax * 65536; l.v1.y = ay * 65536;
        l.v2 = new Vertex(); l.v2.x = bx * 65536; l.v2.y = by * 65536;
        l.dx = l.v2.x - l.v1.x; l.dy = l.v2.y - l.v1.y;
        l.frontsector = new Sector(); l.frontsector.ceilingheight = 128 * 65536;
        return l;
    }

    @Test void footprintRejectsACornerThatTheCenterRayMisses() {
        PSetup.lines = new Line[] {line(50, 10, 50, 80)};
        assertEquals(1, Observation.clearance(0, 0, 100, 0, true));
        assertTrue(new MotionProbe(at(0, 0)).distance(100, 0) < 40);
    }

    @Test void allowsTravelParallelToWallAndEscapeFromExistingContact() {
        PSetup.lines = new Line[] {line(17, -200, 17, 200)};
        assertEquals(100, new MotionProbe(at(0, 0)).distance(0, 100));
        assertEquals(32, new MotionProbe(at(2, 0)).distance(-30, 0));
        assertEquals(0, new MotionProbe(at(2, 0)).distance(34, 0));
    }

    @Test void closedPassageIsBlockedUntilItActuallyOpens() {
        Line door = line(64, -100, 64, 100);
        door.backsector = new Sector();
        PSetup.lines = new Line[] {door};
        MotionProbe probe = new MotionProbe(at(0, 0));
        assertSame(door, probe.door(72));
        assertTrue(probe.distance(128, 0) < 64);
        door.backsector.ceilingheight = 128 * 65536;
        assertNull(probe.door(72));
        assertEquals(128, probe.distance(128, 0));
        door.backsector.floorheight = 32 * 65536;
        assertTrue(probe.distance(128, 0) < 64, "Cannot climb a step taller than 24 units");
    }

    @Test void measuresBlockedMovementSeparatelyFromTurningAndIdleWaiting() {
        MovementMetrics metrics = new MovementMetrics();
        metrics.record(new Action(50, 0, 0, 0), 0, 0, 0, 0);
        metrics.record(new Action(0, 0, 256, 0), 0, 0, 0, 0);
        metrics.record(Action.IDLE, 0, 0, 0, 0);
        metrics.record(new Action(50, 0, 0, 0), 0, 0, 5, 0);
        var s = metrics.summary();
        assertEquals(1, s.blockedTics()); assertEquals(2, s.movementTics());
        assertEquals(1, s.turningTics()); assertEquals(1, s.idleTics());
        assertEquals(5, s.distance());
    }
}
