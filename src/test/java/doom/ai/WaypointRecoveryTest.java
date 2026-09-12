package doom.ai;

import doom.play.*;
import doom.render.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WaypointRecoveryTest {
    private Line[] lines;
    private Side[] sides;
    private Subsector[] subsectors;
    private int nodes;
    private Thinker first,last;
    @BeforeEach void setup() {
        lines=PSetup.lines;sides=PSetup.sides;subsectors=PSetup.subsectors;nodes=PSetup.numnodes;
        first=PTick.thinkercap.next;last=PTick.thinkercap.prev;PTick.pInitThinkers();
        Sector sector=new Sector();sector.ceilingheight=128*65536;
        Subsector sub=new Subsector();sub.sector=sector;
        PSetup.numnodes=0;PSetup.subsectors=new Subsector[]{sub};PSetup.sides=new Side[0];
        PSetup.lines=new Line[]{MotionProbeTest.line(0,0,64,0),MotionProbeTest.line(64,0,64,512),MotionProbeTest.line(64,512,0,512),MotionProbeTest.line(0,512,0,0)};
    }
    @AfterEach void restore() {PSetup.lines=lines;PSetup.sides=sides;PSetup.subsectors=subsectors;PSetup.numnodes=nodes;PTick.thinkercap.next=first;PTick.thinkercap.prev=last;}
    private RunTrace.Sample goal() {return new RunTrace.Sample(100,32,480,0,0,100,0);}
    @Test void waitsExactlyTwoQuietUnproductiveSecondsThenReconnects() {
        WaypointRecovery r=new WaypointRecovery();var o=MotionProbeTest.at(32,32);
        assertNull(r.choose(o,0,0,goal()));assertNull(r.choose(o,69,0,goal()));
        var point=r.choose(o,70,0,goal());assertNotNull(point);assertTrue(point.y()>32);
        assertEquals(1,r.attempts());
        assertEquals(Math.hypot(point.x()-o.x(),point.y()-o.y()),new MotionProbe(o).distance(point.x(),point.y()),.01);
    }
    @Test void enemyAppearanceRestartsQuietTimerAndInterruptsRecovery() {
        WaypointRecovery r=new WaypointRecovery();var o=MotionProbeTest.at(32,32);r.choose(o,0,0,goal());
        Mobj enemy=new Mobj();enemy.health=60;
        var combat=new Observation(32,32,0,100,50,3,8,enemy,0,56,16,0,0,1,0);
        assertNull(r.choose(combat,69,0,goal()));
        assertNull(r.choose(o,70,0,goal()));assertNull(r.choose(o,139,0,goal()));assertNotNull(r.choose(o,140,0,goal()));
        assertNull(r.choose(combat,141,0,goal()));assertFalse(r.active());
    }
    @Test void realProgressPostponesRecoveryAndFailuresHaveAFixedBudget() {
        WaypointRecovery r=new WaypointRecovery();var o=MotionProbeTest.at(32,32);r.choose(o,0,0,goal());
        var moved=MotionProbeTest.at(32,64);assertNull(r.choose(moved,69,0,goal()));assertNull(r.choose(moved,138,0,goal()));
        assertNotNull(r.choose(moved,139,0,goal()));
        r.choose(moved,314,0,goal());r.choose(moved,489,0,goal());
        assertEquals(3,r.attempts());assertNull(r.choose(moved,664,0,goal()));assertNull(r.choose(moved,1000,0,goal()));
        assertNull(r.choose(moved,1001,1,goal()));assertEquals(0,r.attempts());
    }
    @Test void lockedDoorStillBlocksReconnectionToTheKnownWaypoint() {
        Line door=MotionProbeTest.line(0,256,64,256);door.backsector=new Sector();
        PSetup.lines=Arrays.copyOf(PSetup.lines,5);PSetup.lines[4]=door;
        var o=new Observation(32,32,0,100,50,3,8,null,0,56,16,0,0,1,0,Inventory.PISTOL_START,List.of(),0,Set.of(door));
        var r=new WaypointRecovery();r.choose(o,0,0,goal());assertNull(r.choose(o,70,0,goal()));
    }
    @Test void returningToWaypointsPreservesFailedFightCooldown() {
        PSetup.lines = new Line[0];
        Mobj enemy = new Mobj(); enemy.type = Info.MT_POSSESSED; enemy.health = 60; enemy.x = 450 * 65536;
        var o = new Observation(0,0,0,100,50,3,8,enemy,0,56,16,0,0,1,0);
        var combat = new SurvivalCombat();
        assertNotNull(combat.choose(o, new MotionProbe(o), 0));
        assertNull(combat.choose(o, new MotionProbe(o), 175));
        combat.releaseFocus();
        assertNull(combat.choose(o, new MotionProbe(o), 250));
        assertNotNull(combat.choose(o, new MotionProbe(o), 385));
    }
}
