package doom.ai;

import doom.play.PSetup;
import doom.render.*;
import org.junit.jupiter.api.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class UseControllerTest {
    private Line[] original;
    @BeforeEach void keepWorld() { original = PSetup.lines; }
    @AfterEach void restoreWorld() { PSetup.lines = original; }
    private Line door(int x) {
        Line l = MotionProbeTest.line(x, -64, x, 64);
        l.backsector = new Sector();
        return l;
    }
    private MotionProbe probe(double x) { return new MotionProbe(MotionProbeTest.at(x, 0)); }

    @Test void pressesOnEnteringReachWithoutWaitingForPeriodicSchedule() {
        Line door = door(80); PSetup.lines = new Line[] {door};
        UseController use = new UseController();
        assertFalse(use.press(probe(0), 0, 11));
        assertTrue(use.press(probe(17), 0, 12));
        assertFalse(use.press(probe(17), 0, 13), "Release after the press");
    }

    @Test void releasesInheritedUseThenSuppressesRepressesThroughoutOpeningAndAtTop() {
        Line door = door(48); PSetup.lines = new Line[] {door};
        UseController use = new UseController();
        assertFalse(use.press(probe(0), 0, 20), "Prefix may have use held");
        assertTrue(use.press(probe(0), 0, 21));
        for (int tic = 22; tic <= 55; tic++) {
            door.backsector.ceilingheight += 2 * 65536;
            assertFalse(use.press(probe(0), 0, tic), "Never reverse an opening door");
        }
        assertFalse(use.press(probe(0), 0, 150), "Do not close a fully open door");
        door.backsector.ceilingheight -= 2 * 65536;
        assertTrue(use.press(probe(0), 0, 151), "Reopen a closing door");
    }

    @Test void waitsToObserveAnAlreadyMovingDoorAfterAnArchiveRestart() {
        Line door = door(48); door.backsector.ceilingheight = 10 * 65536;
        PSetup.lines = new Line[] {door};
        UseController use = new UseController();
        assertFalse(use.press(probe(0), 0, 100));
        door.backsector.ceilingheight += 2 * 65536;
        assertFalse(use.press(probe(0), 0, 101));
        use.reset();
        assertFalse(use.press(probe(0), 0, 102));
        door.backsector.ceilingheight += 2 * 65536;
        assertFalse(use.press(probe(0), 0, 103));
    }

    @Test void respectsFacingReachAndOcclusionAndCanTryASwitchWall() {
        Line door = door(48), wall = MotionProbeTest.line(24, -64, 24, 64);
        PSetup.lines = new Line[] {door, wall};
        assertSame(wall, probe(0).useTarget(0, Set.of()));
        assertNull(probe(0).useTarget(Math.PI, Set.of()));
        assertNull(probe(-100).useTarget(0, Set.of()));
        UseController use = new UseController();
        assertFalse(use.press(probe(0), 0, 5));
        assertTrue(use.press(probe(0), 0, 6), "Try use without peeking at a wall's special ID");
        for (int tic = 7; tic < 14; tic++) assertFalse(use.press(probe(0), 0, tic));
        assertTrue(use.press(probe(0), 0, 14), "Retry a missed activation after releasing");
    }
}
