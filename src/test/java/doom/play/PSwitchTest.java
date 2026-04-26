package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.Sector;
import doom.render.Side;
import doom.system.GGame;
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
        PSetup.sectors = null;
        PSetup.numsectors = 0;
        PTick.pInitThinkers();
        PMap.changeSectorResult = false;
        GGame.gameaction = GGame.ga_nothing;
        GGame.secretexit = 0;
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

    // ----- P_UseSpecialLine -----
    //
    // Helper world: one tag-7 sector with a single side and a back side
    // for door-style triggers. Most use-line specials only need a sector
    // matching the line's tag.

    private static Line useLineWorld(int special) {
        Sector sec = new Sector();
        sec.floorheight = 0;
        sec.ceilingheight = 200 * Fixed.FRACUNIT;
        sec.tag = 7;
        sec.lines = new Line[0];
        sec.linecount = 0;

        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Side back = new Side();
        back.sector = sec;
        PSetup.sides = new Side[] { new Side(), back };
        PSetup.numsides = 2;

        Line line = new Line();
        line.special = special;
        line.tag = 7;
        line.sidenum[0] = 0;
        line.sidenum[1] = 1;
        return line;
    }

    private static Mobj playerMobj() {
        Mobj m = new Mobj();
        m.player = new Player();
        return m;
    }

    private static Mobj monsterMobj() {
        return new Mobj(); // no .player
    }

    // Using the back side of an unrecognized special → false, no dispatch.
    @Test
    void useSpecialLineBackSideUnknownSpecialReturnsFalse() {
        Line line = useLineWorld(101);

        boolean rtn = PSwitch.pUseSpecialLine(playerMobj(), line, /*side*/1);

        assertFalse(rtn);
    }

    // Monster on a non-MANUAL special → false (only manual doors are
    // monster-usable; line.special=101 is "raise floor", not in the
    // allow-list).
    @Test
    void useSpecialLineMonsterOnNonManualReturnsFalse() {
        Line line = useLineWorld(101);

        boolean rtn = PSwitch.pUseSpecialLine(monsterMobj(), line, 0);

        assertFalse(rtn);
    }

    // Monster on a MANUAL door (line.special=1) → true (EV_VerticalDoor
    // is invoked; the door spawns).
    @Test
    void useSpecialLineMonsterOnManualDoorDispatches() {
        Line line = useLineWorld(1);

        boolean rtn = PSwitch.pUseSpecialLine(monsterMobj(), line, 0);

        assertTrue(rtn);
        assertNotNull(PSetup.sectors[0].specialdata); // door was spawned
    }

    // Monster on ML_SECRET line → false even for a MANUAL door.
    @Test
    void useSpecialLineMonsterCannotOpenSecretDoor() {
        Line line = useLineWorld(1);
        line.flags = Line.ML_SECRET;

        boolean rtn = PSwitch.pUseSpecialLine(monsterMobj(), line, 0);

        assertFalse(rtn);
    }

    // line.special=11 (Exit Level) → flips GGame.gameaction.
    @Test
    void useSpecialLineExitLevelTrigger() {
        Line line = useLineWorld(11);

        PSwitch.pUseSpecialLine(playerMobj(), line, 0);

        assertEquals(GGame.ga_completed, GGame.gameaction);
        assertEquals(0, GGame.secretexit);
    }

    // line.special=51 (Secret Exit) → secretexit latched.
    @Test
    void useSpecialLineSecretExitTrigger() {
        Line line = useLineWorld(51);

        PSwitch.pUseSpecialLine(playerMobj(), line, 0);

        assertEquals(GGame.ga_completed, GGame.gameaction);
        assertEquals(1, GGame.secretexit);
    }

    // line.special=29 → EV_DoDoor(normal). Spawns a door + flips
    // ChangeSwitchTexture (we observe the door spawn).
    @Test
    void useSpecialLineRaiseDoorSpawnsDoorThinker() {
        Line line = useLineWorld(29);

        boolean rtn = PSwitch.pUseSpecialLine(playerMobj(), line, 0);

        assertTrue(rtn);
        assertNotNull(PSetup.sectors[0].specialdata);
    }

    // line.special=23 → EV_DoFloor(lowerFloorToLowest). Spawns a
    // floor-mover thinker.
    @Test
    void useSpecialLineLowerFloorToLowestSpawnsFloor() {
        Line line = useLineWorld(23);

        boolean rtn = PSwitch.pUseSpecialLine(playerMobj(), line, 0);

        assertTrue(rtn);
        assertNotNull(PSetup.sectors[0].specialdata);
        assertTrue(PSetup.sectors[0].specialdata instanceof PFloor.FloorMove);
    }

    // line.special=18 → EV_DoFloor(raiseFloorToNearest). Same shape.
    @Test
    void useSpecialLineRaiseToNearestSpawnsFloor() {
        Line line = useLineWorld(18);

        boolean rtn = PSwitch.pUseSpecialLine(playerMobj(), line, 0);

        assertTrue(rtn);
        assertTrue(PSetup.sectors[0].specialdata instanceof PFloor.FloorMove);
    }

    // line.special=41 → EV_DoCeiling(lowerToFloor).
    @Test
    void useSpecialLineCeilingLowerToFloorSpawnsCeiling() {
        Line line = useLineWorld(41);

        boolean rtn = PSwitch.pUseSpecialLine(playerMobj(), line, 0);

        assertTrue(rtn);
        assertTrue(PSetup.sectors[0].specialdata instanceof PCeilng.Ceiling);
    }

    // line.special=21 → EV_DoPlat(downWaitUpStay).
    @Test
    void useSpecialLineDownWaitUpStaySpawnsPlat() {
        Line line = useLineWorld(21);

        boolean rtn = PSwitch.pUseSpecialLine(playerMobj(), line, 0);

        assertTrue(rtn);
        assertTrue(PSetup.sectors[0].specialdata instanceof PPlats.Plat);
    }

    // ----- EV_DoDonut -----

    // No matching tag → 0.
    @Test
    void evDoDonutNoMatchingTagReturnsZero() {
        Sector s = new Sector();
        s.tag = 99;
        PSetup.sectors = new Sector[] { s };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.tag = 1; // doesn't match

        assertEquals(0, PSpec.evDoDonut(line));
    }

    // Three-sector donut: trigger-tagged inner s1, ring s2, outer s3.
    // Verify both floormove_t records spawn with the right destheights
    // and types (donutRaise on ring, lowerFloor on hole).
    @Test
    void evDoDonutSpawnsTwoFloorMovers() {
        // Layout:
        //  s1 (inner, tag=5, floor=200, pic=1)
        //   - line0 (two-sided) → s2
        //  s2 (ring, floor=100, pic=2)
        //   - line0 → s1 (skipped: backsector == s1)
        //   - line1 (one-sided OK by C bug) → s3
        //  s3 (outer, floor=50, pic=3, special=42)
        Sector s3 = new Sector();
        s3.floorheight = 50;
        s3.floorpic = 3;
        s3.special = 42;

        Sector s2 = new Sector();
        s2.floorheight = 100;
        s2.floorpic = 2;

        Sector s1 = new Sector();
        s1.floorheight = 200;
        s1.floorpic = 1;
        s1.tag = 5;

        // s1.lines[0] connects s1 ↔ s2, two-sided.
        Line l01 = new Line();
        l01.flags = Line.ML_TWOSIDED;
        l01.frontsector = s1;
        l01.backsector = s2;
        s1.lines = new Line[] { l01 };
        s1.linecount = 1;

        // s2 has two lines: one back to s1 (skipped) and one to s3.
        Line l10 = new Line();
        l10.flags = Line.ML_TWOSIDED;
        l10.frontsector = s2;
        l10.backsector = s1; // → skipped because backsector == s1

        Line l23 = new Line();
        l23.flags = Line.ML_TWOSIDED;
        l23.frontsector = s2;
        l23.backsector = s3;

        s2.lines = new Line[] { l10, l23 };
        s2.linecount = 2;

        PSetup.sectors = new Sector[] { s1, s2, s3 };
        PSetup.numsectors = 3;

        Line trigger = new Line();
        trigger.tag = 5;

        int rtn = PSpec.evDoDonut(trigger);

        assertEquals(1, rtn);

        // Inner (s1) gets a lowerFloor mover, dest = s3.floorheight.
        PFloor.FloorMove inner = (PFloor.FloorMove) s1.specialdata;
        assertNotNull(inner);
        assertEquals(PFloor.lowerFloor, inner.type);
        assertEquals(-1, inner.direction);
        assertEquals(50, inner.floordestheight);
        assertEquals(PSpec.FLOORSPEED / 2, inner.speed);

        // Ring (s2) gets a donutRaise mover, dest = s3.floorheight,
        // texture/special copied from s3.
        PFloor.FloorMove ring = (PFloor.FloorMove) s2.specialdata;
        assertNotNull(ring);
        assertEquals(PFloor.donutRaise, ring.type);
        assertEquals(1, ring.direction);
        assertEquals(50, ring.floordestheight);
        assertEquals(3, ring.texture);     // s3.floorpic
        assertEquals(0, ring.newspecial);  // hardcoded 0 in C
    }

    // Already-active inner sector (specialdata != null) → skipped, rtn=0.
    @Test
    void evDoDonutSkipsAlreadyActiveSector() {
        Sector s1 = new Sector();
        s1.tag = 5;
        s1.specialdata = new Object();
        s1.lines = new Line[0];
        s1.linecount = 0;

        PSetup.sectors = new Sector[] { s1 };
        PSetup.numsectors = 1;

        Line trigger = new Line();
        trigger.tag = 5;

        assertEquals(0, PSpec.evDoDonut(trigger));
    }
}
