package doom.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TablesTest {

    // Hand-picked values from reference/linuxdoom-1.10/tables.c. If these
    // drift, the extract_tables.py regeneration is wrong.

    @Test
    void finetangentSizeAndEndpoints() {
        // The Java port over-allocates finetangent to 8192 to
        // absorb r_segs.c's OOB reads (the C version alias-overruns
        // into the adjacent finesine table). See deviation 40.
        assertEquals(8192, Tables.finetangent.length);
        assertEquals(-170910304, Tables.finetangent[0]);
        assertEquals(170910304, Tables.finetangent[4095]);
        // Middle: tangent crosses zero between 2047 and 2048. Verify those
        // symmetric values to catch off-by-one in extraction.
        assertEquals(-25, Tables.finetangent[2047]);
        assertEquals(25, Tables.finetangent[2048]);
        // Upper half mirrors finesine[0..4095] — the C's adjacent-
        // array OOB.
        assertEquals(Tables.finesine[0], Tables.finetangent[4096]);
        assertEquals(Tables.finesine[4095], Tables.finetangent[8191]);
    }

    @Test
    void finesineSizeAndEndpoints() {
        assertEquals(10240, Tables.finesine.length);
        assertEquals(25, Tables.finesine[0]);
        assertEquals(65535, Tables.finesine[10239]);
        // Second entry shown explicitly in the C source.
        assertEquals(75, Tables.finesine[1]);
    }

    @Test
    void tantoangleSizeAndEndpoints() {
        assertEquals(2049, Tables.tantoangle.length);
        assertEquals(0, Tables.tantoangle[0]);
        assertEquals(536870912, Tables.tantoangle[2048]);
        assertEquals(333772, Tables.tantoangle[1]);
    }

    @Test
    void bamConstants() {
        // 0x20000000 * 2 = ANG90 etc.
        assertEquals(Tables.ANG90, Tables.ANG45 * 2);
        assertEquals(Tables.ANG180, Tables.ANG45 * 4);
        assertEquals(Tables.ANG270, Tables.ANG45 * 6);
    }

    @Test
    void fineanglesConstants() {
        assertEquals(8192, Tables.FINEANGLES);
        assertEquals(8191, Tables.FINEMASK);
        assertEquals(19, Tables.ANGLETOFINESHIFT);
        assertEquals(2048, Tables.SLOPERANGE);
        assertEquals(11, Tables.SLOPEBITS);
        // FRACBITS=16, SLOPEBITS=11 → DBITS=5.
        assertEquals(5, Tables.DBITS);
    }

    // finecosine is just finesine shifted by FINEANGLES/4.
    @Test
    void finecosineIsFineSineShifted() {
        int off = Tables.FINECOSINE_OFFSET;
        assertEquals(8192 / 4, off);
        assertEquals(Tables.finesine[off], Tables.finecosine(0));
        assertEquals(Tables.finesine[off + 100], Tables.finecosine(100));
    }

    // SlopeDiv: den<512 → SLOPERANGE; else (num<<3)/(den>>8), clamped.
    @Test
    void slopeDivSmallDenReturnsRange() {
        assertEquals(Tables.SLOPERANGE, Tables.slopeDiv(1000, 0));
        assertEquals(Tables.SLOPERANGE, Tables.slopeDiv(1000, 511));
    }

    @Test
    void slopeDivNormalCase() {
        // den=65536 (>>8 = 256); num=1024 (<<3 = 8192) → 32.
        assertEquals(32, Tables.slopeDiv(1024, 65536));
    }

    @Test
    void slopeDivClampsAboveRange() {
        // num very large → quotient well above SLOPERANGE → clamp.
        assertEquals(Tables.SLOPERANGE, Tables.slopeDiv(1 << 20, 512));
    }
}
