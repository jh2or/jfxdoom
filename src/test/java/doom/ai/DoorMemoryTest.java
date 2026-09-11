package doom.ai;

import doom.play.PSetup;
import doom.render.Line;
import doom.system.Dstrings;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DoorMemoryTest {
    @Test void unlocksOnlyForTheRequiredColorIncludingSkullsAndKeepsSnapshotsImmutable() {
        DoorMemory memory = new DoorMemory(); Line red = new Line(), blue = new Line(), yellow = new Line();
        memory.denied(red, Dstrings.PD_REDK); memory.denied(blue, Dstrings.PD_BLUEO); memory.denied(yellow, Dstrings.PD_YELLOWK);
        Set<Line> before = memory.blocked(0);
        assertEquals(Set.of(red, blue, yellow), before);
        assertEquals(Set.of(red, blue), memory.blocked(2));
        assertEquals(Set.of(red), memory.blocked(3));
        assertEquals(Set.of(blue, yellow), memory.blocked(4));
        assertTrue(memory.blocked(7).isEmpty()); assertTrue(memory.blocked(56).isEmpty());
        assertEquals(3, before.size()); assertThrows(UnsupportedOperationException.class, before::clear);
        memory.reset(); assertTrue(memory.blocked(0).isEmpty());
    }

    @Test void ignoresOrdinaryMessagesAndDoesNotUseLockedDoorEvenAfterControllerRestart() {
        DoorMemory memory = new DoorMemory(); Line door = MotionProbeTest.line(48, -64, 48, 64);
        door.backsector = new doom.render.Sector();
        memory.denied(door, "Picked up a red keycard."); assertTrue(memory.blocked(0).isEmpty());
        memory.denied(null, Dstrings.PD_REDK); assertTrue(memory.blocked(0).isEmpty());
        memory.denied(door, Dstrings.PD_REDK);
        Line[] old = PSetup.lines;
        try {
            PSetup.lines = new Line[]{door};
            var o = new Observation(0,0,0,100,50,3,8,null,0,56,16,0,0,1,2,
                    Inventory.PISTOL_START,List.of(),999,memory.blocked(2));
            MotionProbe probe = new MotionProbe(o); UseController use = new UseController();
            assertNull(probe.door(96));
            assertSame(door, probe.useTarget(0, Set.of()), "The locked door still intercepts the Use ray");
            for (int tic = 0; tic < 500; tic++) {
                if (tic == 250) use.reset();
                assertFalse(use.press(probe, 0, tic));
            }
            var unlocked = new MotionProbe(MotionProbeTest.at(0, 0));
            assertTrue(use.press(unlocked, 0, 501));
        } finally { PSetup.lines = old; }
    }
}
