package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.render.Line;
import doom.render.Sector;
import doom.render.Side;
import doom.system.ISystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PSwitchTest {

    @BeforeEach
    void reset() {
        for (int i = 0; i < PSwitch.MAXBUTTONS; i++) {
            PSwitch.buttonlist[i].line = null;
            PSwitch.buttonlist[i].where = 0;
            PSwitch.buttonlist[i].btexture = 0;
            PSwitch.buttonlist[i].btimer = 0;
            PSwitch.buttonlist[i].soundorg = null;
        }
        for (int i = 0; i < PSwitch.switchlist.length; i++) {
            PSwitch.switchlist[i] = 0;
        }
        PSwitch.numswitches = 0;
        PSetup.sides = null;
        PSetup.numsides = 0;
    }

    // Seeds a line whose front sidedef has the given three textures.
    private static Line seededLine(int topTex, int midTex, int botTex) {
        Sector sec = new Sector();
        Side side = new Side();
        side.sector = sec;
        side.toptexture = topTex;
        side.midtexture = midTex;
        side.bottomtexture = botTex;
        PSetup.sides = new Side[] { side };
        PSetup.numsides = 1;

        Line line = new Line();
        line.frontsector = sec;
        line.sidenum[0] = 0;
        return line;
    }

    // --- P_StartButton ---

    @Test
    void startButtonFillsFirstEmptySlot() {
        Line line = seededLine(10, 20, 30);

        PSwitch.pStartButton(line, PSwitch.top, 10, PSwitch.BUTTONTIME);

        PSwitch.Button b = PSwitch.buttonlist[0];
        assertSame(line, b.line);
        assertEquals(PSwitch.top, b.where);
        assertEquals(10, b.btexture);
        assertEquals(PSwitch.BUTTONTIME, b.btimer);
        assertSame(line.frontsector.soundorg, b.soundorg);
    }

    // Already-pressed line: new call is a no-op. (C gate: same `line`
    // pointer + non-zero btimer.)
    @Test
    void startButtonSkipsWhenLineAlreadyPressed() {
        Line line = seededLine(10, 20, 30);

        PSwitch.pStartButton(line, PSwitch.top, 10, PSwitch.BUTTONTIME);
        // Tweak slot 0 so we can detect a would-be overwrite.
        PSwitch.buttonlist[0].btexture = 999;

        PSwitch.pStartButton(line, PSwitch.top, 10, PSwitch.BUTTONTIME);

        // Untouched: still 999 (and slot 1 still empty).
        assertEquals(999, PSwitch.buttonlist[0].btexture);
        assertEquals(0, PSwitch.buttonlist[1].btimer);
    }

    // Different line: takes the next free slot.
    @Test
    void startButtonUsesNextFreeSlotForDifferentLine() {
        Line l1 = seededLine(10, 20, 30);
        Line l2 = new Line();
        l2.frontsector = new Sector();
        l2.sidenum[0] = 0;

        PSwitch.pStartButton(l1, PSwitch.top, 10, PSwitch.BUTTONTIME);
        PSwitch.pStartButton(l2, PSwitch.middle, 20, PSwitch.BUTTONTIME);

        assertSame(l1, PSwitch.buttonlist[0].line);
        assertSame(l2, PSwitch.buttonlist[1].line);
    }

    // Table full → I_Error.
    @Test
    void startButtonThrowsWhenAllSlotsInUse() {
        Line l = seededLine(10, 20, 30);

        // Fill all slots with distinct lines so the "already pressed"
        // check doesn't short-circuit.
        for (int i = 0; i < PSwitch.MAXBUTTONS; i++) {
            Line other = new Line();
            other.frontsector = new Sector();
            PSwitch.buttonlist[i].line = other;
            PSwitch.buttonlist[i].btimer = 1;
        }

        ISystem.DoomError err = assertThrows(ISystem.DoomError.class,
                () -> PSwitch.pStartButton(l, PSwitch.top, 10, PSwitch.BUTTONTIME));
        assertTrue(err.getMessage().contains("no button slots left"));
    }

    // --- P_ChangeSwitchTexture ---

    // Helper to pre-populate switchlist[] as if pInitSwitchList had run
    // with these SW1<-->SW2 pairs (texture ids, not lump names).
    private static void seedSwitches(int... pairs) {
        for (int i = 0; i < pairs.length; i++) {
            PSwitch.switchlist[i] = pairs[i];
        }
        PSwitch.numswitches = pairs.length / 2;
    }

    // Top texture match → swap with pair, no button (useAgain=0).
    @Test
    void changeSwitchTextureSwapsTopTexture() {
        seedSwitches(100, 200); // 100 ↔ 200
        Line line = seededLine(100, 50, 70);

        PSwitch.pChangeSwitchTexture(line, 0);

        assertEquals(200, PSetup.sides[0].toptexture);
        assertEquals(50, PSetup.sides[0].midtexture);   // untouched
        assertEquals(70, PSetup.sides[0].bottomtexture); // untouched
        // No button queued.
        assertEquals(0, PSwitch.buttonlist[0].btimer);
    }

    // Reverse swap works via i^1: if the *off* state is at switchlist[i]
    // and the *on* state is at switchlist[i+1], pressing the off variant
    // (odd index) flips back.
    @Test
    void changeSwitchTextureSwapsTopInBothDirections() {
        seedSwitches(100, 200);
        Line line = seededLine(200, 50, 70); // starts "on" (SW2)

        PSwitch.pChangeSwitchTexture(line, 0);

        assertEquals(100, PSetup.sides[0].toptexture);   // flipped back
    }

    // Mid texture match.
    @Test
    void changeSwitchTextureSwapsMidTexture() {
        seedSwitches(100, 200, 300, 400);
        Line line = seededLine(11, 300, 22);

        PSwitch.pChangeSwitchTexture(line, 0);

        assertEquals(11, PSetup.sides[0].toptexture);
        assertEquals(400, PSetup.sides[0].midtexture);
        assertEquals(22, PSetup.sides[0].bottomtexture);
    }

    // Bottom texture match.
    @Test
    void changeSwitchTextureSwapsBottomTexture() {
        seedSwitches(100, 200);
        Line line = seededLine(11, 12, 100);

        PSwitch.pChangeSwitchTexture(line, 0);

        assertEquals(11, PSetup.sides[0].toptexture);
        assertEquals(12, PSetup.sides[0].midtexture);
        assertEquals(200, PSetup.sides[0].bottomtexture);
    }

    // useAgain=0 clears line.special.
    @Test
    void changeSwitchTextureClearsSpecialWhenNotReusable() {
        seedSwitches(100, 200);
        Line line = seededLine(100, 0, 0);
        line.special = 29;

        PSwitch.pChangeSwitchTexture(line, 0);

        assertEquals(0, line.special);
    }

    // useAgain=1 keeps line.special AND queues a button (top variant).
    @Test
    void changeSwitchTextureQueuesButtonWhenReusable() {
        seedSwitches(100, 200);
        Line line = seededLine(100, 0, 0);
        line.special = 42;

        PSwitch.pChangeSwitchTexture(line, 1);

        assertEquals(42, line.special);              // NOT cleared
        assertEquals(200, PSetup.sides[0].toptexture);
        PSwitch.Button b = PSwitch.buttonlist[0];
        assertSame(line, b.line);
        assertEquals(PSwitch.top, b.where);
        assertEquals(100, b.btexture);                // original (off) texture
        assertEquals(PSwitch.BUTTONTIME, b.btimer);
    }

    // Queued button in middle variant.
    @Test
    void changeSwitchTextureQueuesMiddleButton() {
        seedSwitches(100, 200);
        Line line = seededLine(0, 100, 0);

        PSwitch.pChangeSwitchTexture(line, 1);

        PSwitch.Button b = PSwitch.buttonlist[0];
        assertEquals(PSwitch.middle, b.where);
        assertEquals(100, b.btexture);
    }

    // Queued button in bottom variant.
    @Test
    void changeSwitchTextureQueuesBottomButton() {
        seedSwitches(100, 200);
        Line line = seededLine(0, 0, 100);

        PSwitch.pChangeSwitchTexture(line, 1);

        PSwitch.Button b = PSwitch.buttonlist[0];
        assertEquals(PSwitch.bottom, b.where);
    }

    // No match anywhere: nothing happens, but line.special still
    // cleared when useAgain=0.
    @Test
    void changeSwitchTextureNoMatchLeavesTexturesAlone() {
        seedSwitches(100, 200);
        Line line = seededLine(1, 2, 3);
        line.special = 55;

        PSwitch.pChangeSwitchTexture(line, 0);

        assertEquals(1, PSetup.sides[0].toptexture);
        assertEquals(2, PSetup.sides[0].midtexture);
        assertEquals(3, PSetup.sides[0].bottomtexture);
        assertEquals(0, line.special); // still cleared by the pre-check
    }

    // useAgain=1 AND no match: special is left alone (no button queued).
    @Test
    void changeSwitchTextureNoMatchAndReusableLeavesSpecial() {
        seedSwitches(100, 200);
        Line line = seededLine(1, 2, 3);
        line.special = 55;

        PSwitch.pChangeSwitchTexture(line, 1);

        assertEquals(55, line.special);   // not cleared
        assertEquals(0, PSwitch.buttonlist[0].btimer); // no button
    }

    // alphSwitchList terminator placement sanity.
    @Test
    void alphSwitchListTerminatorAtEnd() {
        PSwitch.SwitchDef last = PSwitch.alphSwitchList[PSwitch.alphSwitchList.length - 1];
        assertEquals(0, last.episode);
    }

    // Reset helper: button slot fields default to nulls/zeros.
    @Test
    void buttonlistSlotsAreNonNull() {
        for (int i = 0; i < PSwitch.MAXBUTTONS; i++) {
            // Slot itself is always non-null (static initializer).
            assertEquals(0, PSwitch.buttonlist[i].btimer);
            assertNull(PSwitch.buttonlist[i].line);
        }
    }

    // Slot count constants match C.
    @Test
    void sizingConstantsMatchCHeader() {
        assertEquals(50, PSwitch.MAXSWITCHES);
        assertEquals(16, PSwitch.MAXBUTTONS);
        assertEquals(35, PSwitch.BUTTONTIME);
    }
}
