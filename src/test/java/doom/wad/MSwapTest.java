package doom.wad;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MSwapTest {

    @Test
    void swapShortSwapsBytes() {
        assertEquals(0x3412, MSwap.swapSHORT(0x1234));
        assertEquals(0x00FF, MSwap.swapSHORT(0xFF00));
        assertEquals(0x0000, MSwap.swapSHORT(0x0000));
        assertEquals(0xFFFF, MSwap.swapSHORT(0xFFFF));
    }

    @Test
    void swapShortIsItsOwnInverse() {
        for (int v : new int[] { 0x0001, 0x1234, 0xABCD, 0x8000 }) {
            assertEquals(v, MSwap.swapSHORT(MSwap.swapSHORT(v)));
        }
    }

    @Test
    void swapLongSwapsAllFourBytes() {
        assertEquals(0x78563412, MSwap.swapLONG(0x12345678));
        assertEquals(0x00000000, MSwap.swapLONG(0x00000000));
        assertEquals(0xFFFFFFFF, MSwap.swapLONG(0xFFFFFFFF));
        assertEquals(0x000000FF, MSwap.swapLONG(0xFF000000));
    }

    @Test
    void swapLongIsItsOwnInverse() {
        for (int v : new int[] { 0x12345678, 0xAABBCCDD, 0x80000000, 0x7FFFFFFF }) {
            assertEquals(v, MSwap.swapLONG(MSwap.swapLONG(v)));
        }
    }

    @Test
    void passThroughMacrosAreIdentityOnLittleEndianHost() {
        assertEquals(0x1234, MSwap.SHORT(0x1234));
        assertEquals(0x12345678, MSwap.LONG(0x12345678));
    }
}
