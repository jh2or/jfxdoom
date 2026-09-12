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
    private RData.Texture[] textures;
    @BeforeEach void setup() {
        textures = RData.textures;
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
        RData.textures = textures;
        PSetup.lines = lines; PSetup.sides = sides; PSetup.subsectors = subsectors; PSetup.numnodes = nodes;
        PTick.thinkercap.next = first; PTick.thinkercap.prev = last;
    }
    @Test void walksAroundAPillarBeforeAimingAtItsSwitch() {
        PTick.pInitThinkers();
        RData.Texture texture = new RData.Texture(); texture.name = "SW1TEST";
        RData.textures = new RData.Texture[]{texture};
        Side side = new Side(); PSetup.sides = new Side[]{side};
        Line panel = MotionProbeTest.line(256,192,256,320); panel.sidenum[0] = 0;
        PSetup.lines = new Line[]{MotionProbeTest.line(0,0,512,0), MotionProbeTest.line(512,0,512,512),
                MotionProbeTest.line(512,512,0,512), MotionProbeTest.line(0,512,0,0),
                MotionProbeTest.line(192,192,256,192), panel,
                MotionProbeTest.line(256,320,192,320), MotionProbeTest.line(192,320,192,192)};
        GeometryNavigator navigator = new GeometryNavigator();
        Observation o = MotionProbeTest.at(128,256);
        boolean detoured = false, usable = false;
        for (int tic = 0; tic < 350; tic += 8) {
            var point = navigator.choose(o,tic,Set.of(),Set.of());
            assertNotNull(point);
            MotionProbe probe = new MotionProbe(o);
            if (probe.canUse(panel,point.x(),point.y())) { usable = true; break; }
            double distance = Math.hypot(point.x()-o.x(),point.y()-o.y());
            assertEquals(distance,probe.distance(point.x(),point.y()),.01,"Every approach waypoint must be walkable");
            if (point.y() < 176 || point.y() > 336) detoured = true;
            o = MotionProbeTest.at(point.x(),point.y());
        }
        assertTrue(detoured,"Approach the other face by going around the pillar");
        assertTrue(usable,"Finish at a position that can use the switch");
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

    @Test void closedDoorWithASmallThresholdRemainsAPlannableApproach() {
        Line door = barrier();
        door.frontsector = PSetup.subsectors[0].sector;
        door.backsector = new Sector();
        door.backsector.floorheight = door.backsector.ceilingheight = 8*65536;
        door.flags = Line.ML_TWOSIDED;
        var o = MotionProbeTest.at(32,192);
        var target = new GeometryNavigator.Point(32,480);
        assertNotNull(new GeometryNavigator().towards(o,0,target,0),
                "Plan an approach to a closed door even when its threshold is a legal step");
        assertTrue(new MotionProbe(o).distance(32,320)<128,
                "Actual movement must still stop at the closed door");
        door.backsector.floorheight = door.backsector.ceilingheight = 40*65536;
        assertNull(new GeometryNavigator().towards(o,0,target,0),
                "A door hypothesis must not authorize climbing a tall riser");
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

    @Test void tallRiserRequiresGeometryToChangeBeforeTheRouteCanCrossIt() {
        Line lift = barrier(); lift.backsector = new Sector();
        lift.frontsector.ceilingheight = lift.backsector.ceilingheight = 128 * 65536;
        lift.backsector.floorheight = 64 * 65536;
        var o = MotionProbeTest.at(32,32);
        var destination = new GeometryNavigator.Point(32,480);
        GeometryNavigator navigator = new GeometryNavigator();
        assertNull(navigator.towards(o,0,destination,0),"A possible lift is not a walkable step");
        lift.backsector.floorheight = 0;
        assertNotNull(navigator.towards(o,71,destination,0),"After the lift lowers, walking may proceed");
    }

    @Test void cachedEdgesFollowMovingFloorsAndCeilingsInBothDirections() {
        Line lift = barrier(); lift.backsector = new Sector();
        lift.frontsector.ceilingheight = lift.backsector.ceilingheight = 128 * 65536;
        lift.backsector.floorheight = 16 * 65536;
        var o = MotionProbeTest.at(32,32);
        var destination = new GeometryNavigator.Point(32,480);
        GeometryNavigator navigator = new GeometryNavigator();
        assertNotNull(navigator.towards(o,0,destination,0));
        assertNotNull(navigator.towards(o,71,destination,0), "An unchanged graph can be searched again");
        lift.backsector.floorheight = 64 * 65536;
        assertNull(navigator.towards(o,142,destination,0), "Do not reuse a climbable edge after the lift rises");
        lift.backsector.floorheight = 16 * 65536;
        assertNotNull(navigator.towards(o,213,destination,0), "Do not retain an invalid edge after the lift lowers");
        lift.backsector.ceilingheight = 48 * 65536;
        assertNull(navigator.towards(o,284,destination,0), "A lowered ceiling blocks the cached passage");
        lift.backsector.ceilingheight = 128 * 65536;
        assertNotNull(navigator.towards(o,355,destination,0));
    }

    @Test void solidObjectsAreALiveOverlayOnCachedConnections() {
        GeometryNavigator navigator = new GeometryNavigator();
        var o = MotionProbeTest.at(32,32);
        var destination = new GeometryNavigator.Point(32,480);
        assertNotNull(navigator.towards(o,0,destination,0));
        Mobj obstacle = new Mobj(); obstacle.flags = Mobj.MF_SOLID;
        obstacle.x = 32 * 65536; obstacle.y = 256 * 65536; obstacle.radius = 16 * 65536;
        PTick.pAddThinker(obstacle);
        assertNull(navigator.towards(o,71,destination,0));
        obstacle.flags = 0;
        assertNotNull(navigator.towards(o,142,destination,0));
    }

    @Test void objectiveConnectionsFollowMovedAndReplacedPickups() {
        GeometryNavigator navigator = new GeometryNavigator();
        var o = MotionProbeTest.at(32,272);
        Mobj key = (Mobj) PTick.thinkercap.next;
        assertTrue(navigator.choose(o,0,Set.of(),Set.of()).y() > o.y());
        key.y = 48 * 65536;
        assertTrue(navigator.choose(o,71,Set.of(),Set.of()).y() < o.y(), "Reattach a moved pickup");
        key.flags = 0;
        Mobj replacement = new Mobj(); replacement.flags = Mobj.MF_SPECIAL; replacement.sprite = Info.SPR_RKEY;
        replacement.x = 32 * 65536; replacement.y = 480 * 65536; PTick.pAddThinker(replacement);
        assertTrue(navigator.choose(o,142,Set.of(),Set.of()).y() > o.y(), "New items get their own connections");
    }

    @Test void cachedSwitchApproachesRespectTemporaryObstacles() {
        PTick.pInitThinkers();
        RData.Texture texture = new RData.Texture(); texture.name = "SW1TEST";
        RData.textures = new RData.Texture[]{texture}; PSetup.sides = new Side[]{new Side()};
        Line panel = MotionProbeTest.line(0,512,64,512); panel.sidenum[0] = 0;
        corridor[2] = panel;
        GeometryNavigator navigator = new GeometryNavigator();
        var o = MotionProbeTest.at(32,304);
        assertNotNull(navigator.choose(o,0,Set.of(),Set.of()));
        assertSame(panel,navigator.interactionTarget());
        Mobj obstacle = new Mobj(); obstacle.flags = Mobj.MF_SOLID;
        obstacle.x = 32 * 65536; obstacle.y = 472 * 65536; obstacle.radius = 24 * 65536;
        PTick.pAddThinker(obstacle);
        navigator.choose(o,71,Set.of(),Set.of());
        assertNotSame(panel,navigator.interactionTarget(), "Blocked standing positions cannot remain eligible");
        obstacle.flags = 0;
        navigator.choose(o,142,Set.of(),Set.of());
        assertSame(panel,navigator.interactionTarget(), "Temporary blockage must not erase valid connections");
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
