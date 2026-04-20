package doom.system;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// doomdef.c has no executable content — it only #includes "doomdef.h".
// The Java Doomdef class holds the screen / timing constants that lower
// modules (V_Video, R_Data) depend on; this test pins those values to
// their doomdef.h originals.
class DoomdefTest {

    @Test
    void screenDimensionsMatchC() {
        assertEquals(320, Doomdef.BASE_WIDTH);
        assertEquals(320, Doomdef.SCREENWIDTH);
        assertEquals(200, Doomdef.SCREENHEIGHT);
    }

    @Test
    void scalingConstants() {
        assertEquals(1, Doomdef.SCREEN_MUL);
        assertEquals(0.625, Doomdef.INV_ASPECT_RATIO);
    }

    @Test
    void playerAndTimingConstants() {
        assertEquals(4, Doomdef.MAXPLAYERS);
        assertEquals(35, Doomdef.TICRATE);
    }
}
