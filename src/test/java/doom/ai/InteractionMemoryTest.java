package doom.ai;
import doom.play.PSetup;
import doom.render.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class InteractionMemoryTest {
    private Line[] lines; private Side[] sides;
    @BeforeEach void setup() {
        lines=PSetup.lines;sides=PSetup.sides;
        Line line=MotionProbeTest.line(64,-32,64,32);line.sidenum[0]=0;
        PSetup.lines=new Line[]{line};PSetup.sides=new Side[]{new Side()};
        PSetup.sides[0].midtexture=1;
    }
    @AfterEach void restore() { PSetup.lines=lines;PSetup.sides=sides; }
    @Test void remembersOnlyObservedLocalUseChangesAndKeepsOldSnapshotsImmutable() {
        InteractionMemory memory=new InteractionMemory();var initial=memory.activated();
        var before=memory.before(0,0,true);PSetup.sides[0].midtexture=2;memory.after(before);
        assertTrue(memory.activated().contains(0));assertTrue(initial.isEmpty());assertEquals(0,memory.lastUsed());
        int hash=memory.hash();assertNotEquals(0,hash);
        before=memory.before(0,0,true);PSetup.sides[0].midtexture=1;memory.after(before);
        assertEquals(hash,memory.hash(),"Repeated uses do not invent new progress");
        memory.reset();before=memory.before(0,0,false);PSetup.sides[0].midtexture=2;memory.after(before);
        assertTrue(memory.activated().isEmpty());assertEquals(-1,memory.lastUsed());
        before=memory.before(500,0,true);PSetup.sides[0].midtexture=1;memory.after(before);
        assertTrue(memory.activated().isEmpty(),"Remote changes are not observed switches");
    }
    @Test void archiveKeepsSwitchProgressSeparateFromEarlierVisitsToTheSamePlace() {
        Archive a=new Archive(16,128,128);
        var before=new Archive.Cell(0,0,0,4,2,3,8,0,0,0,0);
        var after=new Archive.Cell(0,0,0,4,2,3,8,0,0,0,123);
        assertTrue(a.offer(before,new Route(null,Action.IDLE,1,1),100));
        assertTrue(a.offer(after,new Route(null,Action.IDLE,2,2),100));
        assertEquals(2,a.size());a.visit(before);
        assertEquals(1,a.visits(0,0,0,0));assertEquals(0,a.visits(0,0,0,123));
    }
}
