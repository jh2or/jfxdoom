package doom.ai;

import doom.play.PSetup;
import doom.render.*;
import org.junit.jupiter.api.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class MechanismMemoryTest {
    private Sector[] original;
    @BeforeEach void setup() {
        original = PSetup.sectors;
        Sector sector = new Sector();
        sector.lines = new Line[]{MotionProbeTest.line(64, -32, 64, 32)};
        sector.ceilingheight = 128 * 65536;
        PSetup.sectors = new Sector[]{sector};
    }
    @AfterEach void restore() { PSetup.sectors = original; }

    @Test void recordsDirectionsOnceAndPreservesEarlierSnapshots() {
        MechanismMemory memory = new MechanismMemory(); memory.reset();
        var initial = memory.observed();
        PSetup.sectors[0].floorheight += 65536;
        PSetup.sectors[0].ceilingheight -= 65536;
        memory.observe(0, 0);
        assertEquals(Set.of(0, 3), memory.observed());
        var first = memory.observed();
        PSetup.sectors[0].floorheight -= 65536;
        PSetup.sectors[0].ceilingheight += 65536;
        memory.observe(0, 0);
        assertEquals(Set.of(0, 1, 2, 3), memory.observed());
        assertTrue(initial.isEmpty()); assertEquals(Set.of(0, 3), first);
        assertThrows(UnsupportedOperationException.class, () -> first.add(10));
        memory.reset(); assertTrue(memory.observed().isEmpty());
        memory.observe(0, 0); assertTrue(memory.observed().isEmpty());
    }

    @Test void remoteMotionDoesNotBecomeADeferredLocalEvent() {
        MechanismMemory memory = new MechanismMemory(); memory.reset();
        PSetup.sectors[0].floorheight += 65536;
        memory.observe(1000, 0); assertTrue(memory.observed().isEmpty());
        memory.observe(0, 0); assertTrue(memory.observed().isEmpty());
        PSetup.sectors[0].floorheight += 65536;
        memory.observe(0, 0); assertEquals(Set.of(0), memory.observed());
    }
}
