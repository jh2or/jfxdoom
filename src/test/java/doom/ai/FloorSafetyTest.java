package doom.ai;

import doom.play.*;
import doom.render.*;
import doom.system.Doomdef;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FloorSafetyTest {
    private Line[] savedLines;
    private Side[] savedSides;
    private Node[] savedNodes;
    private Subsector[] savedSubsectors;
    private int savedCount, savedTic;
    private Thinker first, last;
    private Sector dry, toxic;

    @BeforeEach void setup() {
        savedTic = PTick.leveltime; PTick.leveltime = 31;
        savedLines = PSetup.lines; savedSides = PSetup.sides; savedNodes = PSetup.nodes;
        savedSubsectors = PSetup.subsectors; savedCount = PSetup.numnodes;
        first = PTick.thinkercap.next; last = PTick.thinkercap.prev; PTick.pInitThinkers();
        dry = new Sector(); toxic = new Sector();
        dry.ceilingheight = toxic.ceilingheight = 128 * 65536;
        dry.floorpic = toxic.floorpic = Integer.MIN_VALUE; toxic.special = 16;
        Subsector a = new Subsector(), b = new Subsector(); a.sector = dry; b.sector = toxic;
        PSetup.subsectors = new Subsector[]{a, b}; PSetup.sides = new Side[0];
        // Toxic rectangle x=96..160 below y=96, with a safe path around its north side.
        Node top = partition(0,96,1,0,32769,32768);
        Node left = partition(96,0,0,1,0,32768);
        Node right = partition(160,0,0,1,32768,1);
        PSetup.nodes = new Node[]{top,left,right}; PSetup.numnodes = 3;
        PSetup.lines = new Line[]{MotionProbeTest.line(0,0,256,0),MotionProbeTest.line(256,0,256,256),
                MotionProbeTest.line(256,256,0,256),MotionProbeTest.line(0,256,0,0)};
    }
    @AfterEach void restore() {
        PTick.leveltime = savedTic;
        PSetup.lines = savedLines; PSetup.sides = savedSides; PSetup.nodes = savedNodes;
        PSetup.subsectors = savedSubsectors; PSetup.numnodes = savedCount;
        PTick.thinkercap.next = first; PTick.thinkercap.prev = last;
    }
    private static Node partition(int x,int y,int dx,int dy,int front,int back) {
        Node n = new Node(); n.x=x*65536; n.y=y*65536; n.dx=dx*65536; n.dy=dy*65536;
        n.children[0]=front; n.children[1]=back; return n;
    }
    private Observation at(double x,double y,double vx) {
        return new Observation(x,y,0,100,50,3,8,null,0,56,16,vx,0,1,0);
    }

    @Test void recognizesActualDamageEvenWithoutAToxicTexture() {
        for (int special : new int[]{4,5,7,11,16}) {
            toxic.special = special; assertTrue(FloorSafety.hazardous(120,48));
        }
        for (int special : new int[]{0,1,8,9}) {
            toxic.special = special; assertFalse(FloorSafety.hazardous(120,48));
        }
    }
    @Test void detectsAHazardBetweenTwoSafeEndpoints() {
        assertFalse(FloorSafety.hazardous(32,48)); assertFalse(FloorSafety.hazardous(224,48));
        assertEquals(64,FloorSafety.exposure(32,48,224,48),4);
        assertEquals(0,FloorSafety.exposure(32,128,224,128));
    }
    @Test void brakesCombatMomentumBeforeItEntersToxicFloor() {
        Observation o = at(80,48,8);
        var proposed = new Explorer.Burst(new Action(50,0,0,Doomdef.BT_ATTACK),2);
        var guarded = FloorSafety.protect(o,proposed,false,32,48);
        assertTrue(guarded.action().forward()<0);
        assertEquals(1,guarded.duration());
        assertEquals(Doomdef.BT_ATTACK,guarded.action().buttons());
        double nextVelocity = o.momx()+guarded.action().forward()/32.;
        assertTrue(nextVelocity<o.momx());
    }
    @Test void permitsAPlannedCrossingAndMovementOutOfAHazard() {
        var burst = new Explorer.Burst(new Action(50,0,0,0),2);
        assertEquals(burst,FloorSafety.protect(at(80,48,0),burst,true,224,48));
        assertEquals(burst,FloorSafety.protect(at(120,48,0),burst,false,224,48));
    }
    @Test void doesNotPauseForCombatOnADamagingFloor() {
        Mobj enemy = new Mobj(); enemy.x=200*65536; enemy.y=48*65536; enemy.health=100;
        Observation o = new Observation(120,48,0,100,50,3,8,enemy,0,56,16,0,0,1,0);
        for (LearningStrategy strategy : LearningStrategy.values()) {
            var burst = new LocalController(true,2048,strategy).towards(o,new MotionProbe(o),224,48,10,true);
            assertTrue(burst.action().forward()!=0 || burst.action().side()!=0, strategy + " must keep crossing");
        }
    }
    @Test void optionalPickupDoesNotShortcutAcrossToxicFloor() {
        Mobj health = new Mobj(); health.flags=Mobj.MF_SPECIAL; health.sprite=Info.SPR_MEDI;
        health.x=180*65536; health.y=48*65536;
        Observation o = new Observation(80,48,0,50,50,3,8,null,0,56,16,0,0,1,0,
                Inventory.PISTOL_START,List.of(health),0,Set.of());
        assertNull(new PickupController().choose(o,new MotionProbe(o),80,192,0));
    }
    @Test void smoothingKeepsTheSafeDetourAroundAToxicRectangle() {
        GeometryNavigator nav = new GeometryNavigator();
        var destination = new GeometryNavigator.Point(224,48);
        Observation o = at(32,48,0);
        for (int tic=0; tic<350 && Math.hypot(o.x()-224,o.y()-48)>1; tic+=8) {
            var next = nav.towards(o,tic,destination,0); assertNotNull(next);
            assertEquals(0,FloorSafety.exposure(o.x(),o.y(),next.x(),next.y()),.01,
                    "A collision-free shortcut must not cut across the toxic floor");
            o = at(next.x(),next.y(),0);
        }
        assertEquals(224,o.x(),.01); assertEquals(48,o.y(),.01);
    }
    @Test void terrainCacheReconsidersAFloorWhoseDamageWasRemoved() {
        GeometryNavigator nav = new GeometryNavigator();
        var o = at(32,48,0); var target = new GeometryNavigator.Point(224,48);
        var detour = nav.towards(o,0,target,0); assertNotNull(detour);
        assertEquals(0,FloorSafety.exposure(o.x(),o.y(),detour.x(),detour.y()),.01);
        toxic.special = 0;
        var direct = nav.towards(o,71,target,0); assertNotNull(direct);
        assertTrue(direct.x()>200,"Rebuild the cost field when floor damage changes");
    }

    @Test void routeStillCrossesWhenTheHazardSpansTheOnlyPassage() {
        PSetup.nodes[0].y = 300*65536;
        var o = at(32,48,0);
        var next = new GeometryNavigator().towards(o,0,new GeometryNavigator.Point(224,48),0);
        assertNotNull(next);
        assertTrue(next.x()>o.x());
    }
    @Test void aDamagingFinalAreaCanStillBeAnExplorationGoal() {
        dry.special = toxic.special = 11;
        assertNotNull(new GeometryNavigator().choose(at(32,32,0),0,Set.of(),Set.of()));
    }
    @Test void timedCrossingUsesTheLevelClockAndFinishesBeforeTheDamagePulse() {
        Observation o = at(48,48,0);
        int accepted = 0, rejected = 0;
        for (int phase = 0; phase < 32; phase++) {
            var plan = TimedCrossing.plan(o,new MotionProbe(o),192,48,phase);
            if (plan == null) { rejected++; continue; }
            accepted++;
            assertTrue(plan.dryTic() <= 32 - TimedCrossing.MARGIN);
            Player p = new Player(); p.health = 100; p.mo = new Mobj();
            p.mo.player = p; p.mo.health = 100; p.mo.z = 0;
            for (var frame : plan.frames()) {
                PTick.leveltime = frame.tic();
                p.mo.subsector = RMain.rPointInSubsector((int)(frame.x()*65536),(int)(frame.y()*65536));
                if (p.mo.subsector.sector.special != 0) PSpec.pPlayerInSpecialSector(p);
            }
            assertEquals(100,p.health,"Original floor damage routine must see no damaging pulse");
        }
        assertTrue(accepted > 0); assertTrue(rejected > 0);
        assertNull(TimedCrossing.plan(o,new MotionProbe(o),192,48,31));
    }

    @Test void timedCrossingAllowsAccelerationButRejectsSidewaysMomentumAndDeepFloors() {
        Observation o = at(48,48,0);
        assertNotNull(TimedCrossing.plan(o,new MotionProbe(o),192,48,1));
        Observation sliding = new Observation(48,48,0,100,50,3,8,null,0,56,16,0,4,1,0);
        assertNull(TimedCrossing.plan(sliding,new MotionProbe(sliding),192,48,1));
        toxic.floorheight = -32*65536;
        assertNull(TimedCrossing.plan(o,new MotionProbe(o),192,48,1));
    }

    @Test void shallowBasinForecastMatchesActualEngineMovementAndDamageOrder() throws Exception {
        String[] fields = {"bmaporgx","bmaporgy","bmapwidth","bmapheight","blockmaplump","blockmap","blocklinks","numlines"};
        Map<java.lang.reflect.Field,Object> saved = new LinkedHashMap<>();
        for (String name : fields) { var f = PSetup.class.getField(name); saved.put(f,f.get(null)); }
        try {
            toxic.floorheight = -24*65536;
            Line a=MotionProbeTest.line(96,0,96,96), b=MotionProbeTest.line(160,0,160,96);
            a.frontsector=dry; a.backsector=toxic; b.frontsector=toxic; b.backsector=dry;
            a.flags=b.flags=Line.ML_TWOSIDED; a.sidenum[1]=b.sidenum[1]=0;
            PSetup.lines=new Line[]{a,b}; PSetup.numlines=2;
            for (Line l : PSetup.lines) {
                l.slopetype=doom.render.Slopetype.ST_VERTICAL;
                l.bbox[doom.play.MBbox.BOXLEFT]=l.bbox[doom.play.MBbox.BOXRIGHT]=l.v1.x;
                l.bbox[doom.play.MBbox.BOXBOTTOM]=0; l.bbox[doom.play.MBbox.BOXTOP]=96*65536;
            }
            PSetup.bmaporgx=PSetup.bmaporgy=0; PSetup.bmapwidth=PSetup.bmapheight=2;
            PSetup.blockmaplump=new int[]{0,0,2,2,8,8,8,8,0,1,-1}; PSetup.blockmap=PSetup.blockmaplump;
            PSetup.blocklinks=new Mobj[4];
            Player p = new Player(); p.health=100;
            p.mo=PMobj.pSpawnMobj(48*65536,48*65536,PMobj.ONFLOORZ,Info.MT_PLAYER);
            p.mo.player=p; p.mo.health=100;
            Observation o=at(48,48,0); MotionProbe probe=new MotionProbe(o);
            assertEquals(0,probe.supportFloor(100,48));
            assertEquals(-24,probe.supportFloor(128,48));
            assertEquals(0,probe.supportFloor(150,48));
            var plan=TimedCrossing.plan(o,probe,192,48,1); assertNotNull(plan);
            boolean airborne=false;
            for (var f : plan.frames()) {
                PTick.leveltime=f.tic();
                assertEquals(f.x(),p.mo.x/65536.,.001); assertEquals(f.y(),p.mo.y/65536.,.001);
                assertEquals(f.vx(),p.mo.momx/65536.,.001); assertEquals(f.vy(),p.mo.momy/65536.,.001);
                assertEquals(f.z(),p.mo.z/65536.,.001);
                p.cmd=f.action().command();
                PUser.pMovePlayer(p);
                if (p.mo.subsector.sector.special!=0) PSpec.pPlayerInSpecialSector(p);
                PMobj.pXYMovement(p.mo);
                if (p.mo.z!=p.mo.floorz || p.mo.momz!=0) PMobj.pZMovement(p.mo);
                airborne |= p.mo.z>p.mo.floorz;
            }
            assertTrue(airborne,"The test must exercise the basin's brief fall");
            assertEquals(100,p.health); assertEquals(0,p.mo.z);
            assertFalse(FloorSafety.hazardous(p.mo.x/65536.,p.mo.y/65536.));
        } finally {
            for (var entry:saved.entrySet()) entry.getKey().set(null,entry.getValue());
        }
    }

    @Test void timedCrossingRequiresADryLandingAndClearPathWithoutThreats() {
        Observation o = at(48,48,0);
        assertNotNull(TimedCrossing.plan(o,new MotionProbe(o),192,48,1));
        assertNull(TimedCrossing.plan(o,new MotionProbe(o),152,48,1));
        Mobj blocker = new Mobj(); blocker.flags=Mobj.MF_SOLID; blocker.radius=16*65536;
        blocker.x=128*65536; blocker.y=48*65536; PTick.pAddThinker(blocker);
        assertNull(TimedCrossing.plan(o,new MotionProbe(o),192,48,1));
        PTick.pInitThinkers();
        PSetup.lines = new Line[]{MotionProbeTest.line(128,0,128,96)};
        assertNull(TimedCrossing.plan(o,new MotionProbe(o),192,48,1));
    }

    @Test void smoothingUsesASafeWindowAndReplansAnInterruptedShortcut() {
        PTick.leveltime = 1;
        Observation o = at(48,48,0);
        GeometryNavigator nav = new GeometryNavigator();
        var target = new GeometryNavigator.Point(192,48);
        var shortcut = nav.towards(o,1,target,0); assertNotNull(shortcut);
        assertTrue(FloorSafety.exposure(o.x(),o.y(),shortcut.x(),shortcut.y()) > 0);
        // Missed inputs consumed the window without moving. The stale shortcut cannot survive.
        PTick.leveltime = 31;
        var detour = nav.towards(o,31,target,0); assertNotNull(detour);
        assertEquals(0,FloorSafety.exposure(o.x(),o.y(),detour.x(),detour.y()),.01);
    }

    @Test void bothControllersCommitToTheForecastAndRejectUnexpectedMotion() {
        for (LearningStrategy strategy : LearningStrategy.values()) {
            PTick.leveltime = 1;
            Observation o = at(48,48,0);
            var plan = TimedCrossing.plan(o,new MotionProbe(o),192,48,1); assertNotNull(plan);
            var controller = new LocalController(true,2048,strategy);
            for (int i=0; i<plan.frames().size(); i++) {
                var f = plan.frames().get(i); PTick.leveltime = f.tic();
                o = new Observation(f.x(),f.y(),f.angle(),100,50,3,8,null,0,56,16,f.vx(),f.vy(),1,0);
                // Later waypoint changes must not interrupt a committed safe crossing.
                var burst = controller.towards(o,new MotionProbe(o),i==0?192:32,48,f.tic(),true);
                assertEquals(f.action(),burst.action()); assertEquals(1,burst.duration());
            }
            var f = plan.frames().getFirst();
            assertFalse(f.matches(at(48,48,4),f.tic()));
            assertFalse(f.matches(at(48,48,0),f.tic()+1));
            controller.reset();
            PTick.leveltime = 31;
            // Reset must discard any old crossing state.
            o = at(48,48,8);
            var reset = controller.towards(o,new MotionProbe(o),48,128,31,false);
            assertNotEquals(plan.frames().getFirst().action(),reset.action());
        }
    }

}
