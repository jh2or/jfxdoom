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
        return new Observation(x, y, 0, 100, 50, 3, 8, null, 0, 56, 16, 0, 0, 1, 0);
    }
    static Line line(int ax, int ay, int bx, int by) {
        Line l = new Line(); l.sidenum[0] = l.sidenum[1] = -1;
        l.v1 = new Vertex(); l.v1.x = ax * 65536; l.v1.y = ay * 65536;
        l.v2 = new Vertex(); l.v2.x = bx * 65536; l.v2.y = by * 65536;
        l.dx = l.v2.x - l.v1.x; l.dy = l.v2.y - l.v1.y;
        l.frontsector = new Sector(); l.frontsector.ceilingheight = 128 * 65536;
        return l;
    }

    @Test void passageTargetsCrossWideReachableOpeningsAndRespectThePlayerFootprint() {
        Line opening = line(96, -64, 96, 64);
        opening.backsector = new doom.render.Sector(); opening.backsector.ceilingheight = 128 * 65536;
        PSetup.lines = new Line[] {opening};
        var passages = new MotionProbe(at(0, 0), true).passages();
        assertEquals(1, passages.size()); assertEquals(160, passages.getFirst().x(), .001);
        assertEquals(32, new MotionProbe(at(192, 0), true).passages().getFirst().x(), .001);
        PSetup.lines = new Line[] {opening, line(48, -100, 48, 100)};
        assertTrue(new MotionProbe(at(0, 0), true).passages().isEmpty());
        PSetup.lines = new Line[] {opening}; opening.backsector.floorheight = 64 * 65536;
        assertTrue(new MotionProbe(at(0, 0), true).passages().isEmpty());
    }

    @Test void inertialPredictionRejectsSlidingOffALedgeWhileTurning() {
        Line edge = line(48, -100, 48, 100);
        edge.backsector = new Sector();
        edge.frontsector.ceilingheight = edge.backsector.ceilingheight = 128 * 65536;
        edge.backsector.floorheight = -120 * 65536;
        PSetup.lines = new Line[] {edge};
        var moving = new Observation(20, 0, Math.PI / 2, 100, 50, 3, 8, null,
                0, 56, 16, 8, 0, 1, 0);
        assertFalse(LocalController.safeMotion(moving, new MotionProbe(moving, true), new Action(25, 0, 0, 0), 2));
        var command = new LocalController().towards(moving, new MotionProbe(moving, true), 20, 112, 0, false).action();
        assertTrue(command.side() < 0, "Brake eastward momentum even while facing north");
    }

    @Test void explorationAvoidsDeepDropsButReplayOptimizationCanUseThem() {
        Line edge = line(48, -100, 48, 100);
        edge.backsector = new Sector();
        edge.frontsector.ceilingheight = edge.backsector.ceilingheight = 128 * 65536;
        edge.backsector.floorheight = -120 * 65536;
        PSetup.lines = new Line[] {edge};
        assertEquals(96, new MotionProbe(at(0, 0)).distance(96, 0));
        assertTrue(new MotionProbe(at(0, 0), true).distance(96, 0) < 48);
        edge.backsector.floorheight = -16 * 65536;
        assertEquals(96, new MotionProbe(at(0, 0), true).distance(96, 0));
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

    @Test void climbsSuccessiveLegalRisersButRejectsASingleTallStepAndLowCeiling() {
        Line[] stairs = new Line[8];
        for (int i = 0; i < stairs.length; i++) {
            Line l = line(32 + i * 16, -64, 32 + i * 16, 64);
            l.frontsector.floorheight = i * 8 * 65536;
            l.backsector = new Sector(); l.backsector.floorheight = (i + 1) * 8 * 65536;
            l.backsector.ceilingheight = 128 * 65536;
            stairs[i] = l;
        }
        PSetup.lines = stairs;
        assertEquals(176, new MotionProbe(at(0, 0)).distance(176, 0));
        stairs[0].backsector.floorheight = 32 * 65536;
        assertTrue(new MotionProbe(at(0, 0)).distance(176, 0) < 32);
        stairs[0].backsector.floorheight = 8 * 65536;
        stairs[4].backsector.ceilingheight = 64 * 65536;
        assertTrue(new MotionProbe(at(0, 0)).distance(176, 0) < 96);
    }

    @Test void distantStairRisersAreNotDoorTargetsInEitherDirection() {
        Line[] stairs = new Line[5];
        for (int i = 0; i < stairs.length; i++) {
            Line l = line(32 + 32 * i, -64, 32 + 32 * i, 64);
            l.frontsector.floorheight = i * 16 * 65536;
            l.backsector = new Sector(); l.backsector.floorheight = (i + 1) * 16 * 65536;
            l.frontsector.ceilingheight = l.backsector.ceilingheight = 256 * 65536;
            stairs[i] = l;
        }
        PSetup.lines = stairs;
        MotionProbe up = new MotionProbe(at(0, 0));
        assertTrue(up.blocks(stairs[2]), "A remote stair is above the present feet, but is reachable in stages");
        assertEquals(192, up.distance(192, 0));
        assertNull(up.door(224)); assertNull(up.useTarget(0, java.util.Set.of()));
        var top = new Observation(192, 0, Math.PI, 100, 50, 3, 8, null, 80, 56, 16, 0, 0, 1, 0);
        MotionProbe down = new MotionProbe(top);
        assertNull(down.door(224)); assertNull(down.useTarget(Math.PI, java.util.Set.of()));
        stairs[3].backsector.ceilingheight = stairs[3].backsector.floorheight;
        assertSame(stairs[3], up.door(224), "A real closed door is still approached across the stairs");
    }

    @Test void descendsSuccessiveStairsUnderTheirLowerCeilings() {
        Line[] stairs = new Line[5];
        for (int i = 0; i < stairs.length; i++) {
            Line l = line(32 + i * 32, -64, 32 + i * 32, 64);
            l.frontsector.floorheight = (64 - i * 8) * 65536;
            l.frontsector.ceilingheight = (128 - i * 8) * 65536;
            l.backsector = new Sector(); l.backsector.floorheight = (56 - i * 8) * 65536;
            l.backsector.ceilingheight = (120 - i * 8) * 65536;
            stairs[i] = l;
        }
        PSetup.lines = stairs;
        var o = new Observation(0, 0, 0, 100, 50, 3, 8, null, 64, 56, 16, 0, 0, 1, 0);
        assertEquals(192, new MotionProbe(o, true).distance(192, 0));
        stairs[3].backsector.ceilingheight -= 8 * 65536;
        assertTrue(new MotionProbe(o, true).distance(192, 0) < 128);
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
