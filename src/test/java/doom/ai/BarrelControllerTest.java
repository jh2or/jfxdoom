package doom.ai;
import doom.play.*;
import doom.render.Line;
import doom.system.Doomdef;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class BarrelControllerTest {
    private Line[] lines; private Thinker first, last;
    private Mobj barrel;
    @BeforeEach void setup() {
        lines=PSetup.lines; PSetup.lines=new Line[0]; first=PTick.thinkercap.next;last=PTick.thinkercap.prev;PTick.pInitThinkers();
        barrel=new Mobj();barrel.type=Info.MT_BARREL;barrel.health=20;barrel.radius=10*65536;
        barrel.flags=Mobj.MF_SOLID|Mobj.MF_SHOOTABLE;barrel.x=96*65536;PTick.pAddThinker(barrel);
    }
    @AfterEach void restore() {PSetup.lines=lines;PTick.thinkercap.next=first;PTick.thinkercap.prev=last;}
    @Test void retreatsBeforeFiringAndStopsOnceTheObstructionIsDestroyed() {
        BarrelController c=new BarrelController();
        var action=c.choose(MotionProbeTest.at(0,0),200,0,0);
        assertTrue(action.forward()<0);assertEquals(0,action.buttons()&Doomdef.BT_ATTACK);
        action=c.choose(MotionProbeTest.at(-128,0),200,0,40);
        assertNotEquals(0,action.buttons()&Doomdef.BT_ATTACK);
        barrel.health=0;assertNull(c.choose(MotionProbeTest.at(-128,0),200,0,50));
    }
    @Test void ignoresOffRouteBarrelsAndBoundsAnUnsuccessfulAttempt() {
        BarrelController c=new BarrelController();barrel.y=80*65536;
        assertNull(c.choose(MotionProbeTest.at(0,0),200,0,0));
        barrel.y=0;assertNotNull(c.choose(MotionProbeTest.at(0,0),200,0,1));
        assertNull(c.choose(MotionProbeTest.at(0,0),200,0,281));
        assertNull(c.choose(MotionProbeTest.at(0,0),200,0,630));
    }
}
