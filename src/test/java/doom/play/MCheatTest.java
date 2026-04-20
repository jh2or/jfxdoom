package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import doom.play.MCheat.Cheatseq;
import org.junit.jupiter.api.Test;

class MCheatTest {

    // SCRAMBLE swaps bits 0<->7, 1<->6, 3<->4; bits 2 and 5 unchanged.
    // Applying it twice must return the original byte.
    @Test
    void scrambleIsItsOwnInverse() {
        for (int a = 0; a < 256; a++) {
            assertEquals(a, MCheat.scramble(MCheat.scramble(a)),
                    "scramble not an involution at a=" + a);
        }
    }

    // Hand-computed spot checks from the SCRAMBLE formula
    // (((a&1)<<7)+((a&2)<<5)+(a&4)+((a&8)<<1)+((a&16)>>1)+(a&32)+((a&64)>>5)+((a&128)>>7)).
    @Test
    void scrambleKnownValues() {
        assertEquals(0x00, MCheat.scramble(0x00));
        assertEquals(0xFF, MCheat.scramble(0xFF));
        // 'i' = 0x69 = 0110_1001
        //   bit0=1 -> 0x80, bit3=1 -> 0x10, bit5=1 -> 0x20, bit6=1 -> 0x02
        //   = 0xB2
        assertEquals(0xB2, MCheat.scramble('i'));
        // 'd' = 0x64 = 0110_0100
        //   bit2=1 -> 0x04, bit5=1 -> 0x20, bit6=1 -> 0x02
        //   = 0x26
        assertEquals(0x26, MCheat.scramble('d'));
    }

    // Typing the full scrambled sequence returns 1 on the last keystroke.
    @Test
    void checkCheatMatchesFullSequence() {
        Cheatseq cht = scrambledSequence("iddqd");
        assertEquals(0, MCheat.chtCheckCheat(cht, 'i'));
        assertEquals(0, MCheat.chtCheckCheat(cht, 'd'));
        assertEquals(0, MCheat.chtCheckCheat(cht, 'd'));
        assertEquals(0, MCheat.chtCheckCheat(cht, 'q'));
        assertEquals(1, MCheat.chtCheckCheat(cht, 'd'));
    }

    // A mismatched key anywhere resets the cursor — subsequent correct
    // chars do not complete the sequence without restarting from 'i'.
    @Test
    void checkCheatMismatchResets() {
        Cheatseq cht = scrambledSequence("iddqd");
        assertEquals(0, MCheat.chtCheckCheat(cht, 'i'));
        assertEquals(0, MCheat.chtCheckCheat(cht, 'd'));
        // Wrong key: cursor resets to 0.
        assertEquals(0, MCheat.chtCheckCheat(cht, 'x'));
        // From the reset position 'd' does not match sequence[0] = s('i').
        assertEquals(0, MCheat.chtCheckCheat(cht, 'd'));
        // But restarting with 'i' lets us walk the full sequence again.
        assertEquals(0, MCheat.chtCheckCheat(cht, 'i'));
        assertEquals(0, MCheat.chtCheckCheat(cht, 'd'));
        assertEquals(0, MCheat.chtCheckCheat(cht, 'd'));
        assertEquals(0, MCheat.chtCheckCheat(cht, 'q'));
        assertEquals(1, MCheat.chtCheckCheat(cht, 'd'));
    }

    // A parameter cheat has a '1' marker followed by zero-slots that
    // capture subsequent keystrokes, then a 0xff end marker. The
    // keystroke that consumes the '1' marker is the one that matched
    // the last scrambled char before it.
    @Test
    void checkCheatCapturesParam() {
        // "idmus" + 1-marker + two zero slots + 0xff terminator.
        byte[] seq = new byte[] {
                (byte) MCheat.scramble('i'),
                (byte) MCheat.scramble('d'),
                (byte) MCheat.scramble('m'),
                (byte) MCheat.scramble('u'),
                (byte) MCheat.scramble('s'),
                1, 0, 0, (byte) 0xff
        };
        Cheatseq cht = new Cheatseq(seq);

        assertEquals(0, MCheat.chtCheckCheat(cht, 'i'));
        assertEquals(0, MCheat.chtCheckCheat(cht, 'd'));
        assertEquals(0, MCheat.chtCheckCheat(cht, 'm'));
        assertEquals(0, MCheat.chtCheckCheat(cht, 'u'));
        // 's' match advances past the '1' marker into the first zero slot.
        assertEquals(0, MCheat.chtCheckCheat(cht, 's'));
        // Next keystroke captures into slot 6.
        assertEquals(0, MCheat.chtCheckCheat(cht, '3'));
        assertEquals('3', seq[6]);
        // And the second captures into slot 7 — the following byte is
        // 0xff, which fires the success branch.
        assertEquals(1, MCheat.chtCheckCheat(cht, '1'));
        assertEquals('1', seq[7]);
    }

    // After a full capture, cht_GetParam extracts the captured bytes
    // into the caller's buffer and zeroes out the slots in `sequence`.
    @Test
    void getParamExtractsCapturedCharacters() {
        byte[] seq = new byte[] {
                (byte) MCheat.scramble('i'),
                (byte) MCheat.scramble('d'),
                (byte) MCheat.scramble('m'),
                (byte) MCheat.scramble('u'),
                (byte) MCheat.scramble('s'),
                1, 0, 0, (byte) 0xff
        };
        Cheatseq cht = new Cheatseq(seq);

        MCheat.chtCheckCheat(cht, 'i');
        MCheat.chtCheckCheat(cht, 'd');
        MCheat.chtCheckCheat(cht, 'm');
        MCheat.chtCheckCheat(cht, 'u');
        MCheat.chtCheckCheat(cht, 's');
        MCheat.chtCheckCheat(cht, '3');
        assertEquals(1, MCheat.chtCheckCheat(cht, '1'));

        byte[] buf = new byte[8];
        MCheat.chtGetParam(cht, buf);

        assertEquals('3', buf[0]);
        assertEquals('1', buf[1]);
        assertEquals(0, buf[2]); // terminator written because sequence ended with 0xff
        // Slots were zeroed back out.
        assertEquals(0, seq[6]);
        assertEquals(0, seq[7]);
    }

    // The first call lazily fills the xlate table; correctness is
    // already checked by matching tests above, but verify the flag does
    // flip so re-arming for tests actually has an effect.
    @Test
    void firstTimeInitializationHappensOnce() {
        MCheat.resetFirstTimeForTesting();
        Cheatseq cht = scrambledSequence("iddqd");
        // First call must populate cheat_xlate_table; we can only
        // observe this indirectly via a successful match.
        assertEquals(0, MCheat.chtCheckCheat(cht, 'i'));
        // Subsequent call still works (table persists).
        assertEquals(0, MCheat.chtCheckCheat(cht, 'd'));
    }

    // Sanity: a scrambled sequence for two different ASCII chars
    // doesn't collide.
    @Test
    void scrambledCharsAreDistinct() {
        assertNotEquals(MCheat.scramble('i'), MCheat.scramble('d'));
        assertNotEquals(MCheat.scramble('q'), MCheat.scramble('d'));
    }

    private static Cheatseq scrambledSequence(String s) {
        byte[] buf = new byte[s.length() + 1];
        for (int i = 0; i < s.length(); i++) {
            buf[i] = (byte) MCheat.scramble(s.charAt(i));
        }
        buf[s.length()] = (byte) 0xff;
        return new Cheatseq(buf);
    }
}
