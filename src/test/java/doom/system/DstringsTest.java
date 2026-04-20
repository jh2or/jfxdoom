package doom.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DstringsTest {

    @Test
    void scalarConstantsMatchHeader() {
        assertEquals("doomsav", Dstrings.SAVEGAMENAME);
        assertEquals("devmaps", Dstrings.DEVMAPS);
        assertEquals("devdata", Dstrings.DEVDATA);
        assertEquals(22, Dstrings.NUM_QUITMESSAGES);
    }

    @Test
    void endmsgIsSizedNumQuitMessagesPlusOne() {
        assertEquals(Dstrings.NUM_QUITMESSAGES + 1, Dstrings.endmsg.length);
    }

    @Test
    void endmsgFirstEntryIsQuitmsg() {
        assertEquals(Dstrings.QUITMSG, Dstrings.endmsg[0]);
    }

    // endmsg[7] is the concatenation of two string literals in C that
    // are missing a comma between them (dstrings.c lines 45 and 48).
    // Both halves must be present.
    @Test
    void endmsgConcatenatesAtMissingComma45() {
        String joined = Dstrings.endmsg[7];
        assertNotNull(joined);
        assertTrue(joined.startsWith("go ahead and leave. see if i care."),
                "first half of concat missing: " + joined);
        assertTrue(joined.contains("you want to quit?\nthen, thou hast lost an eighth!"),
                "second half of concat missing: " + joined);
    }

    // Same for endmsg[13] — dstrings.c lines 54 and 57.
    @Test
    void endmsgConcatenatesAtMissingComma54() {
        String joined = Dstrings.endmsg[13];
        assertNotNull(joined);
        assertTrue(joined.startsWith("you're lucky i don't smack"),
                "first half of concat missing: " + joined);
        assertTrue(joined.contains("fuck you, pussy!\nget the fuck out!"),
                "second half of concat missing: " + joined);
    }

    // The C array has 21 real entries; slots 21 and 22 of the
    // NUM_QUITMESSAGES+1 = 23 slots are implicitly zero in C. In Java
    // that translates to null.
    @Test
    void endmsgTailIsNull() {
        assertNull(Dstrings.endmsg[21]);
        assertNull(Dstrings.endmsg[22]);
    }

    // Every populated entry contains at least one newline (every C
    // entry is a multiline quip).
    @Test
    void endmsgEntriesAreMultiline() {
        for (int i = 0; i <= 20; i++) {
            String s = Dstrings.endmsg[i];
            assertNotNull(s, "endmsg[" + i + "] unexpectedly null");
            // Slot 0 (QUITMSG) and most others contain \n. The one
            // exception would be if the string has no newline — none
            // should, per the C source.
            assertTrue(s.contains("\n"), "endmsg[" + i + "] has no newline: " + s);
        }
    }
}
