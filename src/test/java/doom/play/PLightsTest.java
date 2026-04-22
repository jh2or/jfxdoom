package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import doom.render.Line;
import doom.render.Sector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PLightsTest {

    @BeforeEach
    void reset() {
        PTick.pInitThinkers();
        MRandom.mClearRandom();
        PSetup.numsectors = 0;
        PSetup.sectors = null;
    }

    // Builds a leaf sector with no two-sided neighbours so
    // P_FindMinSurroundingLight returns `max` unchanged.
    private static Sector leafSector(int light) {
        Sector s = new Sector();
        s.lightlevel = light;
        s.linecount = 0;
        s.lines = new Line[0];
        return s;
    }

    // T_FireFlicker: count > 1 → just decrement, no sector mutation.
    @Test
    void fireFlickerDecrementsCountBeforeFiring() {
        PLights.FireFlicker f = new PLights.FireFlicker();
        Sector s = new Sector();
        s.lightlevel = 200;
        f.sector = s;
        f.count = 3;
        f.maxlight = 200;
        f.minlight = 100;

        PLights.tFireFlicker(f);
        assertEquals(2, f.count);
        assertEquals(200, s.lightlevel);

        PLights.tFireFlicker(f);
        assertEquals(1, f.count);
        assertEquals(200, s.lightlevel);
    }

    // On the tick where --count == 0, FireFlicker resets count to 4
    // and writes either (maxlight - amount) or clamps at minlight.
    @Test
    void fireFlickerFiresAndClampsAtMinlight() {
        PLights.FireFlicker f = new PLights.FireFlicker();
        Sector s = new Sector();
        s.lightlevel = 100;
        f.sector = s;
        f.count = 1;
        f.maxlight = 100;
        f.minlight = 96;

        // P_Random is deterministic; the first call after mClearRandom
        // returns rndtable[1] = 8. (8 & 3) * 16 = 0. So lightlevel-0 = 100,
        // not < minlight(96): we expect maxlight(100) - 0 = 100.
        PLights.tFireFlicker(f);
        assertEquals(100, s.lightlevel);
        assertEquals(4, f.count);
    }

    // T_LightFlash: when sector matches maxlight, switch to minlight.
    @Test
    void lightFlashSwitchesFromMaxToMin() {
        PLights.LightFlash f = new PLights.LightFlash();
        Sector s = new Sector();
        s.lightlevel = 200;
        f.sector = s;
        f.count = 1;
        f.maxlight = 200;
        f.minlight = 50;
        f.maxtime = 64;
        f.mintime = 7;

        PLights.tLightFlash(f);
        assertEquals(50, s.lightlevel);
        // count = (P_Random() & mintime) + 1 — always >= 1.
        assertEquals(true, f.count >= 1 && f.count <= f.mintime + 1);
    }

    // T_LightFlash: when sector is not at maxlight, switch to maxlight.
    @Test
    void lightFlashSwitchesFromMinToMax() {
        PLights.LightFlash f = new PLights.LightFlash();
        Sector s = new Sector();
        s.lightlevel = 50;
        f.sector = s;
        f.count = 1;
        f.maxlight = 200;
        f.minlight = 50;
        f.maxtime = 64;
        f.mintime = 7;

        PLights.tLightFlash(f);
        assertEquals(200, s.lightlevel);
        assertEquals(true, f.count >= 1 && f.count <= f.maxtime + 1);
    }

    // T_StrobeFlash: at minlight → max, count=brighttime.
    @Test
    void strobeGoesToMaxFromMin() {
        PLights.Strobe f = new PLights.Strobe();
        Sector s = new Sector();
        s.lightlevel = 0;
        f.sector = s;
        f.count = 1;
        f.minlight = 0;
        f.maxlight = 200;
        f.darktime = PLights.SLOWDARK;
        f.brighttime = PLights.STROBEBRIGHT;

        PLights.tStrobeFlash(f);
        assertEquals(200, s.lightlevel);
        assertEquals(PLights.STROBEBRIGHT, f.count);
    }

    // T_StrobeFlash: at !minlight → min, count=darktime.
    @Test
    void strobeGoesToMinFromMax() {
        PLights.Strobe f = new PLights.Strobe();
        Sector s = new Sector();
        s.lightlevel = 200;
        f.sector = s;
        f.count = 1;
        f.minlight = 0;
        f.maxlight = 200;
        f.darktime = PLights.SLOWDARK;
        f.brighttime = PLights.STROBEBRIGHT;

        PLights.tStrobeFlash(f);
        assertEquals(0, s.lightlevel);
        assertEquals(PLights.SLOWDARK, f.count);
    }

    // T_Glow going DOWN (direction = -1): decrement by GLOWSPEED until
    // hitting minlight, at which point it bounces (direction → 1).
    @Test
    void glowDownReversesAtMinlight() {
        PLights.Glow g = new PLights.Glow();
        Sector s = new Sector();
        s.lightlevel = 16; // two tics above minlight=0
        g.sector = s;
        g.direction = -1;
        g.minlight = 0;
        g.maxlight = 200;

        // Tick 1: 16 → 8 (no reverse, 8 > 0)
        PLights.tGlow(g);
        assertEquals(8, s.lightlevel);
        assertEquals(-1, g.direction);

        // Tick 2: 8 → 0 (at minlight), then +GLOWSPEED back to 8,
        // direction flips to 1.
        PLights.tGlow(g);
        assertEquals(8, s.lightlevel);
        assertEquals(1, g.direction);
    }

    // T_Glow going UP: increment until hitting maxlight and bounce.
    @Test
    void glowUpReversesAtMaxlight() {
        PLights.Glow g = new PLights.Glow();
        Sector s = new Sector();
        s.lightlevel = 192; // one tic below maxlight=200
        g.sector = s;
        g.direction = 1;
        g.minlight = 0;
        g.maxlight = 200;

        // Tick 1: 192 → 200, == maxlight, rewind to 192, flip down.
        PLights.tGlow(g);
        assertEquals(192, s.lightlevel);
        assertEquals(-1, g.direction);
    }

    // P_SpawnFireFlicker adds a thinker to the list, binds tFireFlicker
    // as the acp1, and wires up light bounds from the sector.
    @Test
    void spawnFireFlickerWiresThinker() {
        Sector s = leafSector(160);
        s.special = 7;

        PLights.pSpawnFireFlicker(s);

        // One new thinker at tail.
        assertNotNull(PTick.thinkercap.next);
        assertSame(PTick.thinkercap, PTick.thinkercap.next.next);

        Thinker t = PTick.thinkercap.next;
        assertNotNull(t.function.acp1);

        PLights.FireFlicker f = (PLights.FireFlicker) t;
        assertSame(s, f.sector);
        assertEquals(160, f.maxlight);
        // No two-sided neighbours → findMinSurroundingLight returns
        // 160, then +16 = 176.
        assertEquals(176, f.minlight);
        assertEquals(4, f.count);
        // sector->special was reset to 0.
        assertEquals(0, s.special);
    }

    // P_SpawnLightFlash sets times, binds tLightFlash, resets special.
    @Test
    void spawnLightFlashWiresThinker() {
        Sector s = leafSector(200);
        s.special = 3;

        PLights.pSpawnLightFlash(s);

        PLights.LightFlash f = (PLights.LightFlash) PTick.thinkercap.next;
        assertSame(s, f.sector);
        assertEquals(200, f.maxlight);
        assertEquals(200, f.minlight);       // leaf => minSurrounding == max
        assertEquals(64, f.maxtime);
        assertEquals(7, f.mintime);
        assertEquals(0, s.special);
        // count in (0, maxtime+1] per (P_Random & maxtime)+1.
        assertEquals(true, f.count >= 1 && f.count <= 65);
    }

    // P_SpawnStrobeFlash with inSync=0 randomises count; also resets
    // minlight to 0 when neighbours match maxlight.
    @Test
    void spawnStrobeFlashZeroesMinlightWhenNoDarkerNeighbour() {
        Sector s = leafSector(200);
        s.special = 9;

        PLights.pSpawnStrobeFlash(s, PLights.FASTDARK, 0);

        PLights.Strobe f = (PLights.Strobe) PTick.thinkercap.next;
        assertSame(s, f.sector);
        assertEquals(PLights.FASTDARK, f.darktime);
        assertEquals(PLights.STROBEBRIGHT, f.brighttime);
        assertEquals(200, f.maxlight);
        // leaf: no darker neighbour, minlight would equal maxlight,
        // the C forces it to 0.
        assertEquals(0, f.minlight);
        assertEquals(0, s.special);
        // inSync==0 → count in [1, 8]
        assertEquals(true, f.count >= 1 && f.count <= 8);
    }

    // P_SpawnStrobeFlash with inSync=1 sets count exactly to 1.
    @Test
    void spawnStrobeFlashInSyncUsesCountOne() {
        Sector s = leafSector(200);
        PLights.pSpawnStrobeFlash(s, PLights.SLOWDARK, 1);
        PLights.Strobe f = (PLights.Strobe) PTick.thinkercap.next;
        assertEquals(1, f.count);
    }

    // P_SpawnGlowingLight wires tGlow, resets special, sets direction = -1.
    @Test
    void spawnGlowingLightWiresThinker() {
        Sector s = leafSector(128);
        s.special = 2;

        PLights.pSpawnGlowingLight(s);

        PLights.Glow g = (PLights.Glow) PTick.thinkercap.next;
        assertSame(s, g.sector);
        assertEquals(128, g.maxlight);
        assertEquals(128, g.minlight); // leaf
        assertEquals(-1, g.direction);
        assertEquals(0, s.special);
    }

    // EV_TurnTagLightsOff: lowers lightlevel of every tagged sector to
    // the darkest adjacent neighbour. Untagged sectors are untouched.
    @Test
    void evTurnTagLightsOffLowersMatchingSectors() {
        Sector a = new Sector(); a.tag = 4; a.lightlevel = 200;
        Sector b = new Sector(); b.tag = 4; b.lightlevel = 150;
        Sector c = new Sector(); c.tag = 9; c.lightlevel = 180;
        Sector dark = new Sector(); dark.lightlevel = 50;

        Line la = new Line();
        la.flags = Line.ML_TWOSIDED;
        la.frontsector = a;
        la.backsector = dark;
        Line lb = new Line();
        lb.flags = Line.ML_TWOSIDED;
        lb.frontsector = b;
        lb.backsector = dark;
        Line lc = new Line();
        lc.flags = Line.ML_TWOSIDED;
        lc.frontsector = c;
        lc.backsector = dark;

        a.lines = new Line[] { la }; a.linecount = 1;
        b.lines = new Line[] { lb }; b.linecount = 1;
        c.lines = new Line[] { lc }; c.linecount = 1;

        PSetup.sectors = new Sector[] { a, b, c };
        PSetup.numsectors = 3;

        Line trigger = new Line();
        trigger.tag = 4;

        PLights.evTurnTagLightsOff(trigger);

        assertEquals(50, a.lightlevel);
        assertEquals(50, b.lightlevel);
        assertEquals(180, c.lightlevel); // untagged, unchanged
    }

    // EV_LightTurnOn with explicit bright: every tagged sector jumps to
    // the passed value (even if it's darker).
    @Test
    void evLightTurnOnSetsExplicitBright() {
        Sector a = new Sector(); a.tag = 2; a.lightlevel = 50;
        Sector b = new Sector(); b.tag = 2; b.lightlevel = 180;
        Sector c = new Sector(); c.tag = 5; c.lightlevel = 60;

        PSetup.sectors = new Sector[] { a, b, c };
        PSetup.numsectors = 3;

        Line trigger = new Line();
        trigger.tag = 2;

        PLights.evLightTurnOn(trigger, 128);

        assertEquals(128, a.lightlevel);
        assertEquals(128, b.lightlevel);
        assertEquals(60, c.lightlevel);
    }

    // EV_LightTurnOn with bright=0: find brightest neighbour.
    @Test
    void evLightTurnOnBrightZeroFindsBrightestNeighbour() {
        Sector target = new Sector(); target.tag = 8; target.lightlevel = 20;
        Sector bright = new Sector(); bright.lightlevel = 240;
        Sector dim = new Sector();    dim.lightlevel = 100;

        Line l1 = new Line();
        l1.flags = Line.ML_TWOSIDED;
        l1.frontsector = target;
        l1.backsector = bright;
        Line l2 = new Line();
        l2.flags = Line.ML_TWOSIDED;
        l2.frontsector = target;
        l2.backsector = dim;
        target.lines = new Line[] { l1, l2 };
        target.linecount = 2;

        PSetup.sectors = new Sector[] { target };
        PSetup.numsectors = 1;

        Line trigger = new Line();
        trigger.tag = 8;

        PLights.evLightTurnOn(trigger, 0);
        assertEquals(240, target.lightlevel);
    }

    // EV_StartLightStrobing skips sectors that already have specialdata.
    @Test
    void evStartLightStrobingSkipsBusySectors() {
        Sector busy = leafSector(150); busy.tag = 3;
        busy.specialdata = new Object(); // already driven by another thinker
        Sector free = leafSector(150); free.tag = 3;

        PSetup.sectors = new Sector[] { busy, free };
        PSetup.numsectors = 2;

        Line trigger = new Line();
        trigger.tag = 3;

        PLights.evStartLightStrobing(trigger);

        // Exactly one thinker added — for `free`.
        Thinker t = PTick.thinkercap.next;
        assertNotNull(t);
        assertSame(PTick.thinkercap, t.next);
        PLights.Strobe f = (PLights.Strobe) t;
        assertSame(free, f.sector);
        assertEquals(PLights.SLOWDARK, f.darktime);
    }
}
