package doom.ai;

import doom.play.PSetup;
import doom.render.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ExplorerInteractionTest {
    private doom.play.Thinker first, last;
    private Line[] lines;
    private Side[] sides;
    private Subsector[] subsectors;
    @BeforeEach void setup() {
        first = doom.play.PTick.thinkercap.next; last = doom.play.PTick.thinkercap.prev; doom.play.PTick.pInitThinkers();
        lines = PSetup.lines; sides = PSetup.sides; subsectors = PSetup.subsectors;
        Line door = MotionProbeTest.line(96, -200, 96, 200); door.backsector = new Sector();
        PSetup.lines = new Line[]{door}; PSetup.sides = new Side[0]; PSetup.subsectors = null;
    }
    @AfterEach void restore() { doom.play.PTick.thinkercap.next = first; doom.play.PTick.thinkercap.prev = last; PSetup.lines = lines; PSetup.sides = sides; PSetup.subsectors = subsectors; }

    @Test void doesNotRepeatedlyPursueAFailedBarrierUntilKeysChange() {
        Explorer explorer = new Explorer(new LearnerRandom(1), false);
        Archive archive = new Archive(100, 1000, 128);
        Observation o = MotionProbeTest.at(0, 0);
        Action approach = explorer.choose(o, archive, 0).action();
        assertTrue(approach.forward() > 0);
        assertNotEquals(approach, explorer.choose(o, archive, 106).action());
        assertNotEquals(approach, explorer.choose(o, archive, 500).action());
        Observation withKey = new Observation(0,0,0,100,50,3,8,null,0,56,16,0,0,1,1);
        assertEquals(approach, explorer.choose(withKey, archive, 502).action());
    }
}
