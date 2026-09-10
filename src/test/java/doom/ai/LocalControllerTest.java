package doom.ai;

import doom.play.PSetup;
import doom.render.Line;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalControllerTest {
    private Line[] original;
    @BeforeEach void keepWorld() { original = PSetup.lines; PSetup.lines = new Line[0]; }
    @AfterEach void restoreWorld() { PSetup.lines = original; }
    private Observation at(double angle, double vx, double vy) {
        return new Observation(0, 0, angle, 100, 50, 3, 8, null, 0, 56, 16, vx, vy, 1, 0);
    }

    @Test void combinesBothRunAxesWhileKeepingThrustAlongTheRouteInEitherOrientation() {
        for (int sign : new int[] {-1, 1}) {
            Observation o = at(sign * Math.atan2(40, 50), 0, 0);
            Action a = new LocalController().towards(o, new MotionProbe(o), 200, 0, 1, false).action();
            assertTrue(Math.hypot(a.forward(), a.side()) > 50, "Diagonal thrust exceeds forward running alone");
            assertEquals(sign * 40, a.side());
            double facing = o.angle() + a.turn() * 2 * Math.PI / 65536;
            double x = a.forward() * Math.cos(facing) + a.side() * Math.sin(facing);
            double y = a.forward() * Math.sin(facing) - a.side() * Math.cos(facing);
            assertTrue(x > 50); assertEquals(0, y, .1, "Sideways thrust must correct the view offset");
        }
    }

    @Test void approachesANearbyObstacleWithoutDiagonalAcceleration() {
        PSetup.lines = new Line[] {MotionProbeTest.line(70, -100, 70, 100)};
        Observation o = at(0, 8, 0);
        Action a = new LocalController().towards(o, new MotionProbe(o), 200, 0, 10, false).action();
        assertEquals(0, a.side());
    }

    @Test void nearbyDoorDoesNotPreventDetouringAroundAnUnrelatedWallCorner() {
        Line wall = MotionProbeTest.line(20, -20, 20, 20);
        Line door = MotionProbeTest.line(-40, -64, -40, 64);
        door.backsector = new doom.render.Sector();
        PSetup.lines = new Line[] {wall, door};
        Observation o = at(0, 0, 0);
        Action a = new LocalController().towards(o, new MotionProbe(o), 100, 0, 1, false).action();
        assertNotEquals(0, a.turn(), "An off-route closed door must not suppress the corner detour");
    }

    @Test void combatPauseIsBoundedAndResumesMovement() {
        var enemy = new doom.play.Mobj(); enemy.x = 100 * 65536; enemy.health = 60;
        Observation o = new Observation(0, 0, 0, 100, 50, 3, 8, enemy, 0, 56, 16, 0, 0, 1, 0);
        LocalController controller = new LocalController();
        Action fighting = controller.towards(o, new MotionProbe(o), 200, 0, 10, true).action();
        assertEquals(0, fighting.forward()); assertEquals(0, fighting.side());
        assertNotEquals(0, fighting.buttons() & doom.system.Doomdef.BT_ATTACK);
        Action moving = controller.towards(o, new MotionProbe(o), 200, 0, 151, true).action();
        assertTrue(moving.forward() != 0 || moving.side() != 0);
    }

    @Test void rejectsMomentumThatWouldCarryTheFootprintIntoASideWall() {
        PSetup.lines = new Line[] {MotionProbeTest.line(-100, 24, 200, 24)};
        Observation drifting = at(0, 4, 6), straight = at(0, 4, 0);
        Action forward = new Action(50, 0, 0, 0);
        assertFalse(LocalController.safeMotion(drifting, new MotionProbe(drifting), forward, 2));
        assertTrue(LocalController.safeMotion(straight, new MotionProbe(straight), forward, 2));
    }
}
