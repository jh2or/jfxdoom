package doom.ai;
import doom.play.*;
import doom.render.Line;
import doom.system.Doomdef;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class DurableCombatTest {
    private Line[] original;
    @BeforeEach void setup() { original=PSetup.lines;PSetup.lines=new Line[0]; }
    @AfterEach void restore() { PSetup.lines=original; }
    private Observation at(Mobj enemy, int ammo) {
        return new Observation(0,0,0,100,ammo,7,8,enemy,0,56,16,0,0,2,0,
                new Inventory(0,0,0,ammo/4,0,2,9),java.util.List.of());
    }
    private Mobj enemy(int health, int x) { Mobj e=new Mobj();e.health=health;e.x=x*65536;return e; }
    @Test void strafesAndKeepsFiringAsLongAsItMakesProgress() {
        Mobj enemy=enemy(1000,240);Observation o=at(enemy,200);DurableCombat c=new DurableCombat();
        Action a=c.choose(o,new MotionProbe(o,true),0);
        assertEquals(40,Math.abs(a.side()));assertEquals(0,a.forward());assertNotEquals(0,a.buttons()&Doomdef.BT_ATTACK);
        enemy.health=900;a=c.choose(o,new MotionProbe(o,true),160);
        assertNotNull(a);assertNotEquals(0,a.buttons()&Doomdef.BT_ATTACK);
        enemy.health=800;assertNotNull(c.choose(o,new MotionProbe(o,true),300));
    }
    @Test void retreatsFromMeleeAndReversesWhenASideIsBlocked() {
        Mobj enemy=enemy(1000,100);Observation o=at(enemy,200);DurableCombat c=new DurableCombat();
        assertTrue(c.choose(o,new MotionProbe(o,true),0).forward()<0);
        PSetup.lines=new Line[]{MotionProbeTest.line(-100,-18,100,-18)};
        assertTrue(c.choose(o,new MotionProbe(o,true),1).side()<0);
    }
    @Test void boundsStalledFightsAndDoesNotFightWithNoAmmoOrOrdinaryEnemies() {
        Mobj enemy=enemy(1000,240);Observation o=at(enemy,200);DurableCombat c=new DurableCombat();
        assertNotNull(c.choose(o,new MotionProbe(o,true),0));
        assertNull(c.choose(o,new MotionProbe(o,true),175));
        assertNull(c.choose(o,new MotionProbe(o,true),524));
        assertNotNull(c.choose(o,new MotionProbe(o,true),525));
        o=at(enemy,0);assertNull(c.choose(o,new MotionProbe(o,true),526));
        o=at(enemy(60,100),200);assertNull(c.choose(o,new MotionProbe(o,true),527));
    }
}
