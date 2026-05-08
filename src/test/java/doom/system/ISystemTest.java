package doom.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.play.Ticcmd;

import org.junit.jupiter.api.Test;

class ISystemTest {

    // I_Error throws a typed DoomError carrying the formatted
    // message — callers can catch and assert on it.
    @Test
    void iErrorThrowsTypedException() {
        ISystem.DoomError ex = assertThrows(ISystem.DoomError.class,
                () -> ISystem.iError("bad thing %d", 42));
        assertEquals("bad thing 42", ex.getMessage());
    }

    // No-arg form skips formatting and returns the literal.
    @Test
    void iErrorNoArgsSkipsFormatting() {
        ISystem.DoomError ex = assertThrows(ISystem.DoomError.class,
                () -> ISystem.iError("simple"));
        assertEquals("simple", ex.getMessage());
    }

    // I_Tactile is a no-op; just verify it doesn't throw.
    @Test
    void iTactileIsNoOp() {
        ISystem.iTactile(40, 10, 80);
    }

    // I_BaseTiccmd returns the shared scratch ticcmd. Repeated
    // calls return the same instance.
    @Test
    void iBaseTiccmdReturnsSharedInstance() {
        Ticcmd a = ISystem.iBaseTiccmd();
        Ticcmd b = ISystem.iBaseTiccmd();
        assertNotNull(a);
        assertSame(a, b);
    }

    // I_GetHeapSize reports mb_used * 1MB (matching the C constant).
    @Test
    void iGetHeapSizeMatchesConfiguredBudget() {
        ISystem.mbUsed = 6;
        assertEquals(6 * 1024 * 1024, ISystem.iGetHeapSize());
    }

    // I_ZoneBase returns a zero-length byte[] — the Java port
    // doesn't pre-allocate the zone slab.
    @Test
    void iZoneBaseReturnsEmpty() {
        byte[] base = ISystem.iZoneBase();
        assertNotNull(base);
        assertEquals(0, base.length);
    }

    // I_AllocLow returns a zero-init byte[] of the requested size.
    @Test
    void iAllocLowZeroInits() {
        byte[] block = ISystem.iAllocLow(64);
        assertEquals(64, block.length);
        for (int i = 0; i < 64; i++) {
            assertEquals(0, block[i]);
        }
    }

    // I_GetTime is monotonic — successive calls return non-
    // decreasing tic counts.
    @Test
    void iGetTimeIsMonotonic() {
        int t0 = ISystem.iGetTime();
        int t1 = ISystem.iGetTime();
        assertTrue(t1 >= t0,
                "iGetTime not monotonic: " + t0 + " then " + t1);
    }

    // I_BeginRead / I_EndRead are no-ops in the C; preserved.
    @Test
    void iBeginEndReadAreNoOps() {
        ISystem.iBeginRead();
        ISystem.iEndRead();
    }

    // I_Init / I_Quit dispatch into ISound / DNet / IVideo.
    // All are stubs; the test just verifies no throw on the
    // top-level entry / exit dance.
    @Test
    void iInitAndQuitDoNotThrow() {
        ISystem.iInit();
        ISystem.iQuit();
    }
}
