package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.Sector;
import doom.render.Side;
import doom.system.ISystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PPlatsTest {

    @BeforeEach
    void reset() {
        for (int i = 0; i < PPlats.MAXPLATS; i++) {
            PPlats.activeplats[i] = null;
        }
        PTick.pInitThinkers();
        PMap.changeSectorResult = false;
        PTick.leveltime = 0;
        PSetup.numsectors = 0;
        PSetup.sectors = null;
        PSetup.numlines = 0;
        PSetup.lines = null;
        PSetup.numsides = 0;
        PSetup.sides = null;
        MRandom.mClearRandom();
    }

    // pAddActivePlat drops into the first null slot.
    @Test
    void addActivePlatFillsFirstNullSlot() {
        PPlats.Plat p = new PPlats.Plat();
        PPlats.pAddActivePlat(p);
        assertSame(p, PPlats.activeplats[0]);
    }

    // Full table → I_Error.
    @Test
    void addActivePlatThrowsWhenTableFull() {
        for (int i = 0; i < PPlats.MAXPLATS; i++) {
            PPlats.activeplats[i] = new PPlats.Plat();
        }

        ISystem.DoomError err = assertThrows(ISystem.DoomError.class,
                () -> PPlats.pAddActivePlat(new PPlats.Plat()));
        assertTrue(err.getMessage().contains("no more plats"));
    }

    // pRemoveActivePlat clears slot + specialdata + flags thinker removed.
    @Test
    void removeActivePlatClearsSlotAndSpecialData() {
        Sector sec = new Sector();
        PPlats.Plat p = new PPlats.Plat();
        p.sector = sec;
        sec.specialdata = p;
        PTick.pAddThinker(p);
        PPlats.activeplats[4] = p;

        PPlats.pRemoveActivePlat(p);

        assertNull(PPlats.activeplats[4]);
        assertNull(sec.specialdata);
        assertTrue(p.removed);
    }

    @Test
    void removeActivePlatThrowsWhenNotFound() {
        ISystem.DoomError err = assertThrows(ISystem.DoomError.class,
                () -> PPlats.pRemoveActivePlat(new PPlats.Plat()));
        assertTrue(err.getMessage().contains("can't find plat"));
    }

    // evStopPlat pauses running plats with matching tag; stored
    // oldstatus; clears acp1 (pause sentinel).
    @Test
    void evStopPlatPausesMatchingPlats() {
        PPlats.Plat a = new PPlats.Plat();
        a.tag = 3;
        a.status = PPlats.up;
        a.function.acp1 = PPlats::tPlatRaise;
        PPlats.activeplats[0] = a;

        Line line = new Line();
        line.tag = 3;

        PPlats.evStopPlat(line);

        assertEquals(PPlats.in_stasis, a.status);
        assertEquals(PPlats.up, a.oldstatus);
        assertNull(a.function.acp1);
    }

    // Already in_stasis: skipped (C: `status != in_stasis` gate).
    @Test
    void evStopPlatSkipsAlreadyInStasis() {
        PPlats.Plat a = new PPlats.Plat();
        a.tag = 3;
        a.status = PPlats.in_stasis;
        a.oldstatus = PPlats.up;
        a.function.acp1 = null;
        PPlats.activeplats[0] = a;

        Line line = new Line();
        line.tag = 3;

        PPlats.evStopPlat(line);

        // oldstatus NOT overwritten because the gate skips.
        assertEquals(PPlats.up, a.oldstatus);
    }

    // pActivateInStasis resumes matching paused plats.
    @Test
    void activateInStasisResumesMatchingPlats() {
        PPlats.Plat a = new PPlats.Plat();
        a.tag = 5;
        a.status = PPlats.in_stasis;
        a.oldstatus = PPlats.down;
        a.function.acp1 = null;
        PPlats.activeplats[0] = a;

        PPlats.pActivateInStasis(5);

        assertEquals(PPlats.down, a.status);
        assertNotNull(a.function.acp1);
    }

    // Non-matching tag: left alone.
    @Test
    void activateInStasisIgnoresOtherTags() {
        PPlats.Plat a = new PPlats.Plat();
        a.tag = 5;
        a.status = PPlats.in_stasis;
        PPlats.activeplats[0] = a;

        PPlats.pActivateInStasis(99);

        assertEquals(PPlats.in_stasis, a.status);
    }

    // T_PlatRaise: in_stasis is a no-op.
    @Test
    void tPlatRaiseInStasisIsNoOp() {
        Sector sec = new Sector();
        sec.floorheight = 50;
        PPlats.Plat p = new PPlats.Plat();
        p.sector = sec;
        p.status = PPlats.in_stasis;
        p.speed = 10;
        p.low = 20;
        p.high = 100;

        PPlats.tPlatRaise(p);

        assertEquals(50, sec.floorheight);
        assertEquals(PPlats.in_stasis, p.status);
    }

    // Waiting → decrement count; when count hits 0, status flips based
    // on whether floor is at low (→ up) or not (→ down).
    @Test
    void tPlatRaiseWaitingDecrementsCountAndFlipsToUpAtLow() {
        Sector sec = new Sector();
        sec.floorheight = 20;
        PPlats.Plat p = new PPlats.Plat();
        p.sector = sec;
        p.status = PPlats.waiting;
        p.count = 1;      // will decrement to 0 → flip
        p.low = 20;
        p.high = 100;

        PPlats.tPlatRaise(p);

        assertEquals(PPlats.up, p.status);
        assertEquals(0, p.count);
    }

    @Test
    void tPlatRaiseWaitingFlipsToDownWhenNotAtLow() {
        Sector sec = new Sector();
        sec.floorheight = 100;
        PPlats.Plat p = new PPlats.Plat();
        p.sector = sec;
        p.status = PPlats.waiting;
        p.count = 1;
        p.low = 20;
        p.high = 100;

        PPlats.tPlatRaise(p);

        assertEquals(PPlats.down, p.status);
    }

    // Waiting with count > 1: just decrement.
    @Test
    void tPlatRaiseWaitingJustDecrementsWhenCountAbove1() {
        Sector sec = new Sector();
        sec.floorheight = 50;
        PPlats.Plat p = new PPlats.Plat();
        p.sector = sec;
        p.status = PPlats.waiting;
        p.count = 5;
        p.low = 20;

        PPlats.tPlatRaise(p);

        assertEquals(PPlats.waiting, p.status);
        assertEquals(4, p.count);
    }

    // Up normal step: sector.floorheight moves by +speed.
    @Test
    void tPlatRaiseUpMovesFloorByPlatSpeed() {
        Sector sec = new Sector();
        sec.floorheight = 50;
        PPlats.Plat p = new PPlats.Plat();
        p.sector = sec;
        p.status = PPlats.up;
        p.speed = 10;
        p.high = 100;
        p.wait = 35;
        p.crush = false;
        p.type = PPlats.downWaitUpStay;

        PPlats.tPlatRaise(p);

        assertEquals(60, sec.floorheight);
        assertEquals(PPlats.up, p.status); // still rising
    }

    // Up reaches high (pastdest) with downWaitUpStay → remove + status=waiting.
    @Test
    void tPlatRaiseUpReachesHighRemovesDownWaitUpStay() {
        Sector sec = new Sector();
        sec.floorheight = 95;
        PPlats.Plat p = new PPlats.Plat();
        p.sector = sec;
        p.status = PPlats.up;
        p.speed = 10;
        p.high = 100;
        p.wait = 35;
        p.type = PPlats.downWaitUpStay;
        sec.specialdata = p;
        PTick.pAddThinker(p);
        PPlats.activeplats[0] = p;

        PPlats.tPlatRaise(p);

        assertEquals(100, sec.floorheight);
        // Status gets set to waiting before removal, and remove clears
        // slot + specialdata.
        assertEquals(PPlats.waiting, p.status);
        assertNull(PPlats.activeplats[0]);
        assertNull(sec.specialdata);
    }

    // Up hits pastdest with perpetualRaise → NOT removed (no case match).
    @Test
    void tPlatRaiseUpReachesHighKeepsPerpetualRaise() {
        Sector sec = new Sector();
        sec.floorheight = 95;
        PPlats.Plat p = new PPlats.Plat();
        p.sector = sec;
        p.status = PPlats.up;
        p.speed = 10;
        p.high = 100;
        p.wait = 35;
        p.type = PPlats.perpetualRaise;
        sec.specialdata = p;
        PTick.pAddThinker(p);
        PPlats.activeplats[0] = p;

        PPlats.tPlatRaise(p);

        assertEquals(100, sec.floorheight);
        assertEquals(PPlats.waiting, p.status);
        assertEquals(35, p.count);
        // Still in the active table.
        assertSame(p, PPlats.activeplats[0]);
        assertSame(p, sec.specialdata);
    }

    // Up blocked by crush + !crush flag → flip to down.
    @Test
    void tPlatRaiseUpBlockedFlipsToDown() {
        Sector sec = new Sector();
        sec.floorheight = 50;
        PPlats.Plat p = new PPlats.Plat();
        p.sector = sec;
        p.status = PPlats.up;
        p.speed = 10;
        p.high = 200;      // far away so normal-step path taken
        p.wait = 35;
        p.crush = false;
        p.type = PPlats.downWaitUpStay;
        PMap.changeSectorResult = true; // force T_MovePlane → crushed

        PPlats.tPlatRaise(p);

        assertEquals(PPlats.down, p.status);
        assertEquals(35, p.count);
    }

    // Down normal step.
    @Test
    void tPlatRaiseDownMovesFloorByNegSpeed() {
        Sector sec = new Sector();
        sec.floorheight = 80;
        PPlats.Plat p = new PPlats.Plat();
        p.sector = sec;
        p.status = PPlats.down;
        p.speed = 10;
        p.low = 20;

        PPlats.tPlatRaise(p);

        assertEquals(70, sec.floorheight);
        assertEquals(PPlats.down, p.status);
    }

    // Down reaches low → status=waiting, count=wait.
    @Test
    void tPlatRaiseDownReachesLowTransitionsToWaiting() {
        Sector sec = new Sector();
        sec.floorheight = 25;
        PPlats.Plat p = new PPlats.Plat();
        p.sector = sec;
        p.status = PPlats.down;
        p.speed = 10;        // overshoots 20
        p.low = 20;
        p.wait = 70;

        PPlats.tPlatRaise(p);

        assertEquals(20, sec.floorheight);
        assertEquals(PPlats.waiting, p.status);
        assertEquals(70, p.count);
    }

    // EV_DoPlat downWaitUpStay: spawns a plat, seeds high=floor, low=lowest surround (clamped).
    @Test
    void evDoPlatDownWaitUpStaySeedsLowAndHigh() {
        Sector sec = new Sector();
        sec.tag = 21;
        sec.floorheight = 100;
        sec.lines = new Line[0];
        sec.linecount = 0;     // isolated — pFindLowestFloorSurrounding returns own floor
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.tag = 21;

        int rtn = PPlats.evDoPlat(line, PPlats.downWaitUpStay, 0);

        assertEquals(1, rtn);
        PPlats.Plat p = (PPlats.Plat) sec.specialdata;
        assertNotNull(p);
        assertEquals(PPlats.PLATSPEED * 4, p.speed);
        assertEquals(100, p.low);  // clamped = own floor
        assertEquals(100, p.high);
        assertEquals(35 * PPlats.PLATWAIT, p.wait);
        assertEquals(PPlats.down, p.status);
        assertSame(p, PPlats.activeplats[0]);
    }

    // EV_DoPlat blazeDWUS: 8x speed vs downWaitUpStay's 4x.
    @Test
    void evDoPlatBlazeDwusUsesEightXSpeed() {
        Sector sec = new Sector();
        sec.tag = 22;
        sec.floorheight = 100;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.tag = 22;

        PPlats.evDoPlat(line, PPlats.blazeDWUS, 0);

        PPlats.Plat p = (PPlats.Plat) sec.specialdata;
        assertEquals(PPlats.PLATSPEED * 8, p.speed);
    }

    // EV_DoPlat raiseAndChange: floorpic copied from sides[line.sidenum[0]].sector,
    // high = floor + amount*FRACUNIT.
    @Test
    void evDoPlatRaiseAndChangeCopiesFloorPic() {
        Sector dstSec = new Sector();
        dstSec.tag = 23;
        dstSec.floorheight = 50;
        dstSec.floorpic = 7;     // original

        Sector srcSec = new Sector();
        srcSec.floorpic = 11;    // the one we want copied

        Side side = new Side();
        side.sector = srcSec;

        PSetup.sectors = new Sector[] { dstSec };
        PSetup.numsectors = 1;
        PSetup.sides = new Side[] { side };
        PSetup.numsides = 1;

        Line line = new Line();
        line.tag = 23;
        line.sidenum[0] = 0;

        PPlats.evDoPlat(line, PPlats.raiseAndChange, 24);

        assertEquals(11, dstSec.floorpic);                       // copied
        PPlats.Plat p = (PPlats.Plat) dstSec.specialdata;
        assertEquals(50 + 24 * Fixed.FRACUNIT, p.high);
        assertEquals(PPlats.PLATSPEED / 2, p.speed);
        assertEquals(PPlats.up, p.status);
    }

    // EV_DoPlat raiseToNearestAndChange: high = P_FindNextHighestFloor,
    // special cleared.
    @Test
    void evDoPlatRaiseToNearestAndChangeClearsSpecial() {
        Sector dstSec = new Sector();
        dstSec.tag = 24;
        dstSec.floorheight = 50;
        dstSec.special = 9;
        dstSec.lines = new Line[0];
        dstSec.linecount = 0;

        Sector srcSec = new Sector();
        srcSec.floorpic = 44;

        Side side = new Side();
        side.sector = srcSec;

        PSetup.sectors = new Sector[] { dstSec };
        PSetup.numsectors = 1;
        PSetup.sides = new Side[] { side };
        PSetup.numsides = 1;

        Line line = new Line();
        line.tag = 24;
        line.sidenum[0] = 0;

        PPlats.evDoPlat(line, PPlats.raiseToNearestAndChange, 0);

        assertEquals(44, dstSec.floorpic);
        assertEquals(0, dstSec.special);
    }

    // EV_DoPlat perpetualRaise uses PRandom for initial status. With
    // rndtable[0]=0 → 0 & 1 = 0 = up.
    @Test
    void evDoPlatPerpetualRaiseSeedsStatusFromPRandom() {
        Sector sec = new Sector();
        sec.tag = 25;
        sec.floorheight = 100;
        sec.ceilingheight = 200;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.tag = 25;

        PPlats.evDoPlat(line, PPlats.perpetualRaise, 0);

        PPlats.Plat p = (PPlats.Plat) sec.specialdata;
        // rndtable[0] = 0, 0 & 1 = 0 → status = up.
        assertEquals(PPlats.up, p.status);
        // Bounds clamp: low = own floor (isolated sector);
        // high = 0 seed from pFindHighestFloorSurrounding, then
        // clamped to own floor since high < floor.
        assertEquals(100, p.low);
        assertEquals(100, p.high);
    }

    // EV_DoPlat skips sectors with specialdata.
    @Test
    void evDoPlatSkipsBusySectors() {
        Sector sec = new Sector();
        sec.tag = 26;
        sec.specialdata = new Object(); // anything non-null
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.tag = 26;

        int rtn = PPlats.evDoPlat(line, PPlats.downWaitUpStay, 0);

        assertEquals(0, rtn);
        assertNull(PPlats.activeplats[0]);
    }

    // perpetualRaise also runs pActivateInStasis first (via the switch).
    // Verify an in-stasis plat with matching tag gets resumed AND a new
    // plat gets spawned.
    @Test
    void evDoPlatPerpetualRaiseActivatesInStasisPlats() {
        PPlats.Plat paused = new PPlats.Plat();
        paused.tag = 27;
        paused.status = PPlats.in_stasis;
        paused.oldstatus = PPlats.up;
        paused.function.acp1 = null;
        PPlats.activeplats[0] = paused;

        Sector sec = new Sector();
        sec.tag = 27;
        sec.floorheight = 100;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.tag = 27;

        PPlats.evDoPlat(line, PPlats.perpetualRaise, 0);

        // Resumed.
        assertEquals(PPlats.up, paused.status);
        assertNotNull(paused.function.acp1);
        // And a fresh plat got spawned for the sector.
        assertNotNull(sec.specialdata);
    }
}
