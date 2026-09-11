package doom.ai;

import doom.play.*;
import doom.render.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GeometryNavigatorTest {
    private Line[] lines;
    private Side[] sides;
    private Subsector[] subsectors;
    private int nodes;
    private Thinker first, last;
    private Line[] corridor;
    @BeforeEach void setup() {
        lines = PSetup.lines; sides = PSetup.sides; subsectors = PSetup.subsectors; nodes = PSetup.numnodes;
        first = PTick.thinkercap.next; last = PTick.thinkercap.prev; PTick.pInitThinkers();
        Sector sector = new Sector(); sector.ceilingheight = 128 * 65536;
        Subsector sub = new Subsector(); sub.sector = sector;
        PSetup.numnodes = 0; PSetup.subsectors = new Subsector[]{sub}; PSetup.sides = new Side[0];
        corridor = new Line[]{MotionProbeTest.line(0,0,64,0), MotionProbeTest.line(64,0,64,512),
                MotionProbeTest.line(64,512,0,512), MotionProbeTest.line(0,512,0,0)};
        PSetup.lines = corridor;
        Mobj key = new Mobj(); key.flags = Mobj.MF_SPECIAL; key.sprite = Info.SPR_BKEY;
        key.x = 32 * 65536; key.y = 480 * 65536; PTick.pAddThinker(key);
    }
    @AfterEach void restore() {
        PSetup.lines = lines; PSetup.sides = sides; PSetup.subsectors = subsectors; PSetup.numnodes = nodes;
        PTick.thinkercap.next = first; PTick.thinkercap.prev = last;
    }
    private Line barrier() {
        Line wall = MotionProbeTest.line(0,256,64,256);
        PSetup.lines = Arrays.copyOf(corridor,5); PSetup.lines[4] = wall; return wall;
    }
    @Test void plansAlongANarrowCorridorToAnUnownedKey() {
        var o = MotionProbeTest.at(32,32);
        var next = new GeometryNavigator().choose(o,0,Set.of(),Set.of());
        assertNotNull(next); assertTrue(next.x() >= 16 && next.x() <= 48); assertTrue(next.y() > 32);
        double d = Math.hypot(next.x() - o.x(),next.y() - o.y());
        assertEquals(d,new MotionProbe(o).distance(next.x(),next.y()),.01);
    }
    @Test void advancesTheCachedPathAfterSkippingAheadToALaterWaypoint() {
        GeometryNavigator navigator = new GeometryNavigator();
        Observation o = MotionProbeTest.at(32,32);
        for (int tic = 0; tic < 100; tic += 8) {
            var next = navigator.choose(o, tic, Set.of(), Set.of());
            assertNotNull(next);
            assertTrue(next.y() >= o.y(), "Do not turn back toward a skipped earlier waypoint");
            o = MotionProbeTest.at(next.x(), next.y());
        }
        assertTrue(o.y() >= 448);
    }

    @Test void explicitLandmarkOverridesTheKeyGoalAndCanChangeDirection() {
        GeometryNavigator navigator = new GeometryNavigator();
        Observation o = MotionProbeTest.at(32, 272);
        var behind = navigator.towards(o, 0, new GeometryNavigator.Point(32, 48), 0);
        assertNotNull(behind);
        assertTrue(behind.y() < o.y(), "Use the demonstrated destination instead of the key ahead");
        var ahead = navigator.towards(o, 1, new GeometryNavigator.Point(32, 480), 0);
        assertNotNull(ahead);
        assertTrue(ahead.y() > o.y(), "Changing the destination invalidates the cached route");
    }

    @Test void aNewLockObservationInvalidatesTheCachedPathImmediately() {
        Line door = barrier(); door.backsector = new Sector();
        GeometryNavigator navigator = new GeometryNavigator();
        var destination = new GeometryNavigator.Point(32, 480);
        var o = MotionProbeTest.at(32, 208);
        assertNotNull(navigator.towards(o, 0, destination, 0));
        var denied = new Observation(32,208,0,100,50,3,8,null,0,56,16,0,0,1,0,
                Inventory.PISTOL_START,List.of(),0,Set.of(door));
        assertNull(navigator.towards(denied, 1, destination, 0), "Do not follow the stale route through the denied door");
        assertNotNull(navigator.towards(o, 2, destination, 0), "Reconsider the route when the lock is satisfied");
    }

    @Test void cannotCrossAWallExactlyAlignedWithTheGrid() {
        barrier();
        GeometryNavigator navigator = new GeometryNavigator();
        Observation o = MotionProbeTest.at(32, 32);
        for (int tic = 0; tic < 1000; tic += 16) {
            var point = navigator.choose(o, tic, Set.of(), Set.of());
            if (point == null) break;
            assertTrue(point.y() < 256, "A path must not cross the separating wall");
            o = MotionProbeTest.at(point.x(), point.y());
        }
    }
    @Test void testsClosedDoorsButRoutesAroundAnObservedFailedBarrier() {
        Line door = barrier(); door.backsector = new Sector();
        var o = MotionProbeTest.at(32,32);
        assertNotNull(new GeometryNavigator().choose(o,0,Set.of(),Set.of()));
        var limited = new GeometryNavigator().choose(o,0,Set.of(),Set.of(door));
        assertTrue(limited == null || limited.y() < 256);
    }
}
