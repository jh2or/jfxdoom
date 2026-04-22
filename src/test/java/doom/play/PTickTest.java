package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PTickTest {

    @BeforeEach
    void reset() {
        PTick.leveltime = 0;
        PTick.pInitThinkers();
    }

    // After init the sentinel is a one-node circular list pointing at
    // itself (p_tick.c line 55).
    @Test
    void initEmptiesList() {
        assertSame(PTick.thinkercap, PTick.thinkercap.next);
        assertSame(PTick.thinkercap, PTick.thinkercap.prev);
    }

    // Adding appends at the tail, just before the cap sentinel.
    @Test
    void addAppendsAtTail() {
        Thinker a = new Thinker();
        Thinker b = new Thinker();

        PTick.pAddThinker(a);
        PTick.pAddThinker(b);

        // order: cap -> a -> b -> cap
        assertSame(a, PTick.thinkercap.next);
        assertSame(b, a.next);
        assertSame(PTick.thinkercap, b.next);
        // reverse pointers
        assertSame(b, PTick.thinkercap.prev);
        assertSame(a, b.prev);
        assertSame(PTick.thinkercap, a.prev);
    }

    // pRemoveThinker just marks — it does NOT unlink.
    @Test
    void removeJustMarks() {
        Thinker a = new Thinker();
        PTick.pAddThinker(a);
        assertFalse(a.removed);

        PTick.pRemoveThinker(a);

        assertTrue(a.removed);
        // still in the list
        assertSame(a, PTick.thinkercap.next);
        assertSame(PTick.thinkercap, a.next);
    }

    // pRunThinkers invokes acp1 on every non-removed thinker, in order.
    @Test
    void runThinkersInvokesEveryEntry() {
        List<Thinker> invoked = new ArrayList<>();
        Thinker a = new Thinker();
        Thinker b = new Thinker();
        Thinker c = new Thinker();
        a.function.acp1 = invoked::add;
        b.function.acp1 = invoked::add;
        c.function.acp1 = invoked::add;
        PTick.pAddThinker(a);
        PTick.pAddThinker(b);
        PTick.pAddThinker(c);

        PTick.pRunThinkers();

        assertEquals(3, invoked.size());
        assertSame(a, invoked.get(0));
        assertSame(b, invoked.get(1));
        assertSame(c, invoked.get(2));
    }

    // A thinker with a null acp1 is skipped — no NPE, no side effect.
    @Test
    void runThinkersSkipsNullAcp1() {
        Thinker a = new Thinker();
        Thinker b = new Thinker();
        List<Thinker> invoked = new ArrayList<>();
        a.function.acp1 = null;
        b.function.acp1 = invoked::add;
        PTick.pAddThinker(a);
        PTick.pAddThinker(b);

        PTick.pRunThinkers();

        assertEquals(1, invoked.size());
        assertSame(b, invoked.get(0));
    }

    // A removed thinker is unlinked on the same run and never called.
    @Test
    void runThinkersUnlinksRemoved() {
        Thinker a = new Thinker();
        Thinker b = new Thinker();
        Thinker c = new Thinker();
        List<Thinker> invoked = new ArrayList<>();
        a.function.acp1 = invoked::add;
        b.function.acp1 = invoked::add;
        c.function.acp1 = invoked::add;
        PTick.pAddThinker(a);
        PTick.pAddThinker(b);
        PTick.pAddThinker(c);
        PTick.pRemoveThinker(b);

        PTick.pRunThinkers();

        // b was unlinked before its function could fire.
        assertEquals(2, invoked.size());
        assertSame(a, invoked.get(0));
        assertSame(c, invoked.get(1));

        // list is now cap -> a -> c -> cap
        assertSame(a, PTick.thinkercap.next);
        assertSame(c, a.next);
        assertSame(PTick.thinkercap, c.next);
    }

    // Removing the head and tail works too — sentinel stays linked.
    @Test
    void runThinkersHandlesHeadAndTailRemoval() {
        Thinker a = new Thinker();
        Thinker b = new Thinker();
        Thinker c = new Thinker();
        List<Thinker> invoked = new ArrayList<>();
        b.function.acp1 = invoked::add;
        PTick.pAddThinker(a);
        PTick.pAddThinker(b);
        PTick.pAddThinker(c);
        PTick.pRemoveThinker(a);
        PTick.pRemoveThinker(c);

        PTick.pRunThinkers();

        assertEquals(1, invoked.size());
        assertSame(b, invoked.get(0));
        assertSame(b, PTick.thinkercap.next);
        assertSame(b, PTick.thinkercap.prev);
        assertSame(PTick.thinkercap, b.next);
        assertSame(PTick.thinkercap, b.prev);
    }

    // Empty list is a no-op — does not enter the loop, does not fault.
    @Test
    void runThinkersEmptyListIsNoOp() {
        // No throw means success.
        PTick.pRunThinkers();
        assertSame(PTick.thinkercap, PTick.thinkercap.next);
    }

    // pAllocateThinker is a no-op stub (p_tick.c line 93 — empty body).
    @Test
    void allocateThinkerIsNoOp() {
        Thinker t = new Thinker();
        PTick.pAllocateThinker(t);
        // body is empty; the only "contract" is no crash and no list
        // mutation.
        assertSame(PTick.thinkercap, PTick.thinkercap.next);
    }
}
