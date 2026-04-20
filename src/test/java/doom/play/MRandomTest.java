package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MRandomTest {

    @BeforeEach
    void reset() {
        MRandom.mClearRandom();
    }

    @Test
    void tableHas256Entries() {
        assertEquals(256, MRandom.rndtable.length);
        // Spot-check first and last entries from m_random.c.
        assertEquals(0, MRandom.rndtable[0]);
        assertEquals(8, MRandom.rndtable[1]);
        assertEquals(249, MRandom.rndtable[255]);
    }

    @Test
    void allTableValuesInByteRange() {
        for (int v : MRandom.rndtable) {
            assertTrue(v >= 0 && v <= 255, "value out of byte range: " + v);
        }
    }

    // M_Random increments first, then returns — so the first call returns rndtable[1].
    @Test
    void mRandomReturnsNextTableEntry() {
        assertEquals(MRandom.rndtable[1], MRandom.mRandom());
        assertEquals(MRandom.rndtable[2], MRandom.mRandom());
        assertEquals(MRandom.rndtable[3], MRandom.mRandom());
    }

    @Test
    void pRandomReturnsNextTableEntry() {
        assertEquals(MRandom.rndtable[1], MRandom.pRandom());
        assertEquals(MRandom.rndtable[2], MRandom.pRandom());
    }

    // Ensure the two streams are independent — per the "Which one is deterministic?"
    // comment in C, they must not share an index.
    @Test
    void mAndPStreamsAreIndependent() {
        MRandom.mRandom(); // rndindex = 1
        MRandom.mRandom(); // rndindex = 2
        // prndindex still 0 here.
        assertEquals(MRandom.rndtable[1], MRandom.pRandom());
    }

    // Index wraps at 256.
    @Test
    void indexWrapsAfter256Calls() {
        for (int i = 0; i < 256; i++) {
            MRandom.mRandom();
        }
        // After 256 calls rndindex is back to 0, next call returns rndtable[1].
        assertEquals(MRandom.rndtable[1], MRandom.mRandom());
    }

    @Test
    void clearResetsBothStreams() {
        MRandom.mRandom();
        MRandom.pRandom();
        MRandom.mClearRandom();
        assertEquals(0, MRandom.rndindex);
        assertEquals(0, MRandom.prndindex);
    }
}
