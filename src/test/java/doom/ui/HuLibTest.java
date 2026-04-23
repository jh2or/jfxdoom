package doom.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.render.Patch;
import doom.system.Doomdef;

import org.junit.jupiter.api.Test;

class HuLibTest {

    // A font "table" just used for height probe (erase path) and
    // width probes (draw path, not exercised in these tests).
    private static Patch[] dummyFont(int charCount) {
        Patch[] arr = new Patch[charCount];
        for (int i = 0; i < charCount; i++) {
            // patch_t: width(2) height(2) leftoffset(2) topoffset(2) + int[width] cols.
            // width=1 keeps the lump minimal while giving fromLump a valid columnofs[].
            Patch p = Patch.fromLump(new byte[] {
                    1, 0,   // width 1
                    12, 0,  // height 12
                    0, 0,   // leftoffset
                    0, 0,   // topoffset
                    0, 0, 0, 0, // columnofs[0]
            });
            arr[i] = p;
        }
        return arr;
    }

    @Test
    void clearTextLineResetsLengthAndNulTerminates() {
        HuLib.TextLine t = new HuLib.TextLine();
        t.len = 5;
        t.l[0] = 'A';
        t.needsupdate = 0;

        HuLib.huLibClearTextLine(t);

        assertEquals(0, t.len);
        assertEquals(0, t.l[0]);
        assertEquals(1, t.needsupdate);
    }

    @Test
    void initTextLineStoresCoordsAndFont() {
        HuLib.TextLine t = new HuLib.TextLine();
        Patch[] font = dummyFont(64);

        HuLib.huLibInitTextLine(t, 10, 20, font, '!');

        assertEquals(10, t.x);
        assertEquals(20, t.y);
        assertSame(font, t.f);
        assertEquals('!', t.sc);
        assertEquals(0, t.len); // clearTextLine ran
    }

    @Test
    void addCharToTextLineAppendsAndMarksDirty() {
        HuLib.TextLine t = new HuLib.TextLine();

        assertTrue(HuLib.huLibAddCharToTextLine(t, 'H'));
        assertTrue(HuLib.huLibAddCharToTextLine(t, 'i'));

        assertEquals(2, t.len);
        assertEquals('H', t.l[0]);
        assertEquals('i', t.l[1]);
        assertEquals(0, t.l[2]); // nul terminator always kept
        assertEquals(4, t.needsupdate);
    }

    @Test
    void addCharToTextLineRejectsWhenAtCapacity() {
        HuLib.TextLine t = new HuLib.TextLine();
        t.len = HuLib.HU_MAXLINELENGTH;

        assertFalse(HuLib.huLibAddCharToTextLine(t, 'x'));
        assertEquals(HuLib.HU_MAXLINELENGTH, t.len);
    }

    @Test
    void delCharFromTextLineShrinksAndNulls() {
        HuLib.TextLine t = new HuLib.TextLine();
        HuLib.huLibAddCharToTextLine(t, 'H');
        HuLib.huLibAddCharToTextLine(t, 'i');

        assertTrue(HuLib.huLibDelCharFromTextLine(t));
        assertEquals(1, t.len);
        assertEquals(0, t.l[1]);
        assertEquals(4, t.needsupdate);
    }

    @Test
    void delCharFromEmptyTextLineReturnsFalse() {
        HuLib.TextLine t = new HuLib.TextLine();
        assertFalse(HuLib.huLibDelCharFromTextLine(t));
    }

    // SText: circular buffer of TextLine rows.
    @Test
    void initSTextBuildsRowsWithStaggeredY() {
        HuLib.SText s = new HuLib.SText();
        Patch[] font = dummyFont(64);
        boolean[] on = { true };

        HuLib.huLibInitSText(s, 0, 100, 3, font, '!', on);

        assertEquals(3, s.h);
        assertSame(on, s.on);
        assertTrue(s.laston);
        assertEquals(0, s.cl);
        // Each row is offset up by height+1 = 13.
        assertEquals(100, s.l[0].y);
        assertEquals(87, s.l[1].y);
        assertEquals(74, s.l[2].y);
    }

    @Test
    void addLineToSTextAdvancesCurrentLineAndWraps() {
        HuLib.SText s = new HuLib.SText();
        Patch[] font = dummyFont(64);
        HuLib.huLibInitSText(s, 0, 100, 3, font, '!', new boolean[] { true });

        HuLib.huLibAddLineToSText(s);
        assertEquals(1, s.cl);
        HuLib.huLibAddLineToSText(s);
        assertEquals(2, s.cl);
        HuLib.huLibAddLineToSText(s);
        assertEquals(0, s.cl); // wrap at h

        for (int i = 0; i < 3; i++) {
            assertEquals(4, s.l[i].needsupdate);
        }
    }

    @Test
    void addMessageToSTextWritesPrefixThenMessage() {
        HuLib.SText s = new HuLib.SText();
        Patch[] font = dummyFont(64);
        HuLib.huLibInitSText(s, 0, 100, 2, font, '!', new boolean[] { true });

        HuLib.huLibAddMessageToSText(s, "> ", "ok");

        HuLib.TextLine cur = s.l[s.cl];
        assertEquals(4, cur.len);
        assertEquals('>', cur.l[0]);
        assertEquals(' ', cur.l[1]);
        assertEquals('o', cur.l[2]);
        assertEquals('k', cur.l[3]);
        assertEquals(0, cur.l[4]);
    }

    @Test
    void addMessageToSTextAcceptsNullPrefix() {
        HuLib.SText s = new HuLib.SText();
        Patch[] font = dummyFont(64);
        HuLib.huLibInitSText(s, 0, 100, 2, font, '!', new boolean[] { true });

        HuLib.huLibAddMessageToSText(s, null, "hi");

        HuLib.TextLine cur = s.l[s.cl];
        assertEquals(2, cur.len);
    }

    // IText + key handling.
    @Test
    void initITextResetsState() {
        HuLib.IText it = new HuLib.IText();
        HuLib.TextLine prev = it.l;
        Patch[] font = dummyFont(64);
        boolean[] on = { true };

        HuLib.huLibInitIText(it, 5, 7, font, '!', on);

        assertSame(prev, it.l); // reused, not reallocated
        assertEquals(0, it.lm);
        assertSame(on, it.on);
        assertTrue(it.laston);
        assertEquals(5, it.l.x);
    }

    @Test
    void addPrefixToITextSetsLeftMargin() {
        HuLib.IText it = new HuLib.IText();
        HuLib.huLibInitIText(it, 0, 0, dummyFont(64), '!', new boolean[] { true });

        HuLib.huLibAddPrefixToIText(it, "say:");

        assertEquals(4, it.l.len);
        assertEquals(4, it.lm);
    }

    @Test
    void delCharFromITextHonoursLeftMargin() {
        HuLib.IText it = new HuLib.IText();
        HuLib.huLibInitIText(it, 0, 0, dummyFont(64), '!', new boolean[] { true });
        HuLib.huLibAddPrefixToIText(it, "p:");
        HuLib.huLibAddCharToTextLine(it.l, 'a');
        HuLib.huLibAddCharToTextLine(it.l, 'b');

        HuLib.huLibDelCharFromIText(it); // drops 'b'
        assertEquals(3, it.l.len);
        HuLib.huLibDelCharFromIText(it); // drops 'a'
        assertEquals(2, it.l.len);
        HuLib.huLibDelCharFromIText(it); // bounded by lm=2
        assertEquals(2, it.l.len);
    }

    @Test
    void eraseLineFromITextDrainsPastMargin() {
        HuLib.IText it = new HuLib.IText();
        HuLib.huLibInitIText(it, 0, 0, dummyFont(64), '!', new boolean[] { true });
        HuLib.huLibAddPrefixToIText(it, "p:");
        HuLib.huLibAddCharToTextLine(it.l, 'x');
        HuLib.huLibAddCharToTextLine(it.l, 'y');
        HuLib.huLibAddCharToTextLine(it.l, 'z');

        HuLib.huLibEraseLineFromIText(it);

        assertEquals(2, it.l.len); // only margin survives
        assertEquals(2, it.lm);
    }

    @Test
    void resetITextClearsMarginAndBuffer() {
        HuLib.IText it = new HuLib.IText();
        HuLib.huLibInitIText(it, 0, 0, dummyFont(64), '!', new boolean[] { true });
        HuLib.huLibAddPrefixToIText(it, "p:");
        HuLib.huLibAddCharToTextLine(it.l, 'x');

        HuLib.huLibResetIText(it);

        assertEquals(0, it.l.len);
        assertEquals(0, it.lm);
    }

    @Test
    void keyInITextAcceptsPrintables() {
        HuLib.IText it = new HuLib.IText();
        HuLib.huLibInitIText(it, 0, 0, dummyFont(64), '!', new boolean[] { true });

        assertTrue(HuLib.huLibKeyInIText(it, 'A'));
        assertTrue(HuLib.huLibKeyInIText(it, ' '));
        assertTrue(HuLib.huLibKeyInIText(it, '_'));

        assertEquals(3, it.l.len);
        assertEquals('A', it.l.l[0]);
        assertEquals(' ', it.l.l[1]);
        assertEquals('_', it.l.l[2]);
    }

    @Test
    void keyInITextBackspaceDeletes() {
        HuLib.IText it = new HuLib.IText();
        HuLib.huLibInitIText(it, 0, 0, dummyFont(64), '!', new boolean[] { true });
        HuLib.huLibKeyInIText(it, 'X');

        assertTrue(HuLib.huLibKeyInIText(it, Doomdef.KEY_BACKSPACE));
        assertEquals(0, it.l.len);
    }

    @Test
    void keyInITextEnterIsEatenButBuffersUnchanged() {
        HuLib.IText it = new HuLib.IText();
        HuLib.huLibInitIText(it, 0, 0, dummyFont(64), '!', new boolean[] { true });
        HuLib.huLibKeyInIText(it, 'X');

        assertTrue(HuLib.huLibKeyInIText(it, Doomdef.KEY_ENTER));
        assertEquals(1, it.l.len); // unchanged
    }

    @Test
    void keyInITextRejectsControlKeys() {
        HuLib.IText it = new HuLib.IText();
        HuLib.huLibInitIText(it, 0, 0, dummyFont(64), '!', new boolean[] { true });

        assertFalse(HuLib.huLibKeyInIText(it, 0x01)); // not eaten
        assertFalse(HuLib.huLibKeyInIText(it, '`')); // above '_' (0x5F, 0x60 rejected)
        assertEquals(0, it.l.len);
    }

    // Sanity: TextLine capacity is declared at +1 for the nul.
    @Test
    void textLineBufferLeavesRoomForNulTerminator() {
        HuLib.TextLine t = new HuLib.TextLine();
        assertEquals(HuLib.HU_MAXLINELENGTH + 1, t.l.length);
    }

    // SText row initializers create fresh TextLine instances per slot.
    @Test
    void initSTextAllocatesDistinctRows() {
        HuLib.SText s = new HuLib.SText();
        HuLib.huLibInitSText(s, 0, 0, 3, dummyFont(64), '!', new boolean[] { true });

        assertNotSame(s.l[0], s.l[1]);
        assertNotSame(s.l[1], s.l[2]);
    }
}
