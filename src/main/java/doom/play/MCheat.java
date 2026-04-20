// Port of linuxdoom-1.10/m_cheat.c and m_cheat.h.
// Cheat sequence checking. A cheatseq_t holds the scrambled target
// sequence plus a mutable cursor that advances on each matching
// keypress; mismatches reset the cursor and a 0xff byte means "match".
package doom.play;

public final class MCheat {

    // cheatseq_t — m_cheat.h lines 34–39.
    //
    // In C `sequence` is the base pointer and `p` is a pointer into it;
    // a zero-initialized struct has p == NULL so cht_CheckCheat's first
    // call initializes p = sequence. We keep the same behavior with a
    // sentinel p = -1 meaning "not yet initialized".
    public static final class Cheatseq {
        public byte[] sequence;
        public int p;

        public Cheatseq(byte[] sequence) {
            this.sequence = sequence;
            this.p = -1;
        }
    }

    // SCRAMBLE(a) — m_cheat.h lines 30–32. Bit-permutation that swaps
    // bits 0<->7, 1<->6, 3<->4 and leaves bits 2, 5 in place. Its own
    // inverse.
    public static int scramble(int a) {
        return (((a & 1) << 7)
              + ((a & 2) << 5)
              + (a & 4)
              + ((a & 8) << 1)
              + ((a & 16) >> 1)
              + (a & 32)
              + ((a & 64) >> 5)
              + ((a & 128) >> 7)) & 0xFF;
    }

    // static int firsttime = 1; — m_cheat.c line 34.
    private static int firsttime = 1;

    // static unsigned char cheat_xlate_table[256]; — m_cheat.c line 35.
    // unsigned char → int & 0xFF per CLAUDE.md §3.
    private static final int[] cheat_xlate_table = new int[256];

    private MCheat() {
    }

    //
    // Called in st_stuff module, which handles the input.
    // Returns a 1 if the cheat was successful, 0 if failed.
    //
    // int cht_CheckCheat(cheatseq_t* cht, char key) — m_cheat.c line 43.
    public static int chtCheckCheat(Cheatseq cht, char key) {
        int i;
        int rc = 0;

        if (firsttime != 0) {
            firsttime = 0;
            for (i = 0; i < 256; i++) cheat_xlate_table[i] = scramble(i);
        }

        if (cht.p < 0)
            cht.p = 0; // initialize if first time

        int keyByte = key & 0xFF;

        if ((cht.sequence[cht.p] & 0xFF) == 0)
            cht.sequence[cht.p++] = (byte) keyByte;
        else if (cheat_xlate_table[keyByte] == (cht.sequence[cht.p] & 0xFF))
            cht.p++;
        else
            cht.p = 0;

        if ((cht.sequence[cht.p] & 0xFF) == 1)
            cht.p++;
        else if ((cht.sequence[cht.p] & 0xFF) == 0xff) // end of sequence character
        {
            cht.p = 0;
            rc = 1;
        }

        return rc;
    }

    // void cht_GetParam(cheatseq_t* cht, char* buffer) — m_cheat.c line 78.
    public static void chtGetParam(Cheatseq cht, byte[] buffer) {
        int p;
        int c;

        p = 0;
        while ((cht.sequence[p++] & 0xFF) != 1)
            ;

        int bufIdx = 0;
        do {
            c = cht.sequence[p] & 0xFF;
            buffer[bufIdx++] = (byte) c;
            cht.sequence[p++] = 0;
        }
        while (c != 0 && (cht.sequence[p] & 0xFF) != 0xff);

        if ((cht.sequence[p] & 0xFF) == 0xff)
            buffer[bufIdx] = 0;
    }

    // Test-only hook: the C's `firsttime` is a file-static that only ever
    // goes 1 -> 0. Tests that assert the lazy init need to re-arm it.
    static void resetFirstTimeForTesting() {
        firsttime = 1;
    }
}
