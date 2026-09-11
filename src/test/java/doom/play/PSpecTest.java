package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.RData;
import doom.render.Sector;
import doom.render.Side;
import doom.system.Doomstat;
import doom.system.GGame;
import doom.system.MArgv;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PSpecTest {

    @BeforeEach
    void reset() {
        PSetup.numsectors = 0;
        PSetup.sectors = null;
        PSetup.numlines = 0;
        PSetup.lines = null;
        PSetup.numsides = 0;
        PSetup.sides = null;
        PTick.leveltime = 0;
        PTick.pInitThinkers();
        PSpec.numanims = 0;
        PSpec.numlinespecials = 0;
        PSpec.levelTimer = false;
        PSpec.levelTimeCount = 0;
        Doomstat.deathmatch = 0;
        Doomstat.totalsecret = 0;
        MArgv.myargc = 0;
        MArgv.myargv = new String[0];
        for (int i = 0; i < PSwitch.MAXBUTTONS; i++) {
            PSwitch.buttonlist[i].line = null;
            PSwitch.buttonlist[i].where = 0;
            PSwitch.buttonlist[i].btexture = 0;
            PSwitch.buttonlist[i].btimer = 0;
            PSwitch.buttonlist[i].soundorg = null;
        }
        for (int i = 0; i < PCeilng.MAXCEILINGS; i++) {
            PCeilng.activeceilings[i] = null;
        }
        for (int i = 0; i < PPlats.MAXPLATS; i++) {
            PPlats.activeplats[i] = null;
        }
        GGame.gameaction = GGame.ga_nothing;
        GGame.secretexit = 0;
        PMap.changeSectorOverride = true;
        PMap.changeSectorResult = false;
    }

    private Player playerInSector(int special) {
        Player p = new Player(); Mobj mo = new Mobj();
        Sector sector = new Sector(); sector.special = special;
        mo.subsector = new doom.render.Subsector(); mo.subsector.sector = sector;
        mo.info = new MobjInfo(0,0,100,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,100,0,0,0,0); mo.health = p.health = 100;
        mo.flags = Mobj.MF_SHOOTABLE; mo.player = p; p.mo = mo;
        GGame.players[0] = p; GGame.consoleplayer = 0;
        Doomstat.gameskill = Doomstat.sk_medium; MRandom.mClearRandom();
        return p;
    }

    @Test void specialFloorsDamageOnlyOnTheGroundAndEvery32Tics() {
        int[] specials = {5, 7, 16, 4}, damage = {10, 5, 20, 20};
        for (int i = 0; i < specials.length; i++) {
            Player p = playerInSector(specials[i]);
            p.mo.z = 1; PTick.leveltime = 0; PSpec.pPlayerInSpecialSector(p);
            assertEquals(100, p.health);
            p.mo.z = 0; PTick.leveltime = 31; PSpec.pPlayerInSpecialSector(p);
            assertEquals(100, p.health);
            PTick.leveltime = 32; PSpec.pPlayerInSpecialSector(p);
            assertEquals(100 - damage[i], p.health); assertEquals(p.health, p.mo.health);
        }
    }

    @Test void radiationSuitProtectionAndRandomLeaksMatchTheOriginalOrder() {
        for (int special : new int[] {5, 7}) {
            Player p = playerInSector(special); p.powers[doom.system.Doomdef.pw_ironfeet] = 1;
            PTick.leveltime = 0; PSpec.pPlayerInSpecialSector(p);
            assertEquals(100, p.health); assertEquals(0, MRandom.prndindex);
        }
        Player p = playerInSector(16); p.powers[doom.system.Doomdef.pw_ironfeet] = 1;
        PTick.leveltime = 1; PSpec.pPlayerInSpecialSector(p);
        assertEquals(100, p.health); assertEquals(1, MRandom.prndindex, "Leak RNG is consumed even off a damage tic");
        int leak = 1;
        while (MRandom.rndtable[leak] >= 5) leak++;
        MRandom.prndindex = leak - 1; PTick.leveltime = 32; PSpec.pPlayerInSpecialSector(p);
        assertEquals(80, p.health); assertEquals((leak + 1) & 255, MRandom.prndindex, "Damage also consumes pain RNG");
    }

    @Test void secretSectorCountsOnceWhenThePlayerLands() {
        Player p = playerInSector(9); p.mo.z = 1; PSpec.pPlayerInSpecialSector(p);
        assertEquals(0, p.secretcount); assertEquals(9, p.mo.subsector.sector.special);
        p.mo.z = 0; PSpec.pPlayerInSpecialSector(p);
        assertEquals(1, p.secretcount); assertEquals(0, p.mo.subsector.sector.special);
    }

    @Test void episodeOneFinaleRemovesGodModeAndExitsAtTenHealthOrLess() {
        Player p = playerInSector(11); p.health = p.mo.health = 11; p.cheats = Player.CF_GODMODE;
        PTick.leveltime = 0; PSpec.pPlayerInSpecialSector(p);
        assertEquals(0, p.cheats & Player.CF_GODMODE);
        assertEquals(1, p.health, "The existing final-sector damage clamp prevents ordinary death");
        assertEquals(GGame.ga_completed, GGame.gameaction); assertEquals(0, GGame.secretexit);
        GGame.gameaction = GGame.ga_nothing;
        p = playerInSector(11); p.health = p.mo.health = 10; PTick.leveltime = 1;
        PSpec.pPlayerInSpecialSector(p); assertEquals(GGame.ga_completed, GGame.gameaction);
    }

    @Test void unknownPlayerSectorSpecialFailsExplicitly() {
        Player p = playerInSector(123);
        org.junit.jupiter.api.Assertions.assertThrows(doom.system.ISystem.DoomError.class,
                () -> PSpec.pPlayerInSpecialSector(p));
    }

    // getNextSector: one-sided line (ML_TWOSIDED not set) returns null.
    @Test
    void getNextSectorReturnsNullForOneSided() {
        Line line = new Line();
        line.flags = 0;
        Sector front = new Sector();
        Sector back = new Sector();
        line.frontsector = front;
        line.backsector = back;

        assertNull(PSpec.getNextSector(line, front));
    }

    // getNextSector: when asking from front, returns back.
    @Test
    void getNextSectorReturnsBackFromFront() {
        Line line = new Line();
        line.flags = Line.ML_TWOSIDED;
        Sector front = new Sector();
        Sector back = new Sector();
        line.frontsector = front;
        line.backsector = back;

        assertSame(back, PSpec.getNextSector(line, front));
    }

    // getNextSector: when asking from back, returns front.
    @Test
    void getNextSectorReturnsFrontFromBack() {
        Line line = new Line();
        line.flags = Line.ML_TWOSIDED;
        Sector front = new Sector();
        Sector back = new Sector();
        line.frontsector = front;
        line.backsector = back;

        assertSame(front, PSpec.getNextSector(line, back));
    }

    // P_FindSectorFromLineTag: scan starting at start+1 for a tag match.
    @Test
    void findSectorFromLineTagIteratesMatches() {
        Sector s0 = new Sector(); s0.tag = 9;
        Sector s1 = new Sector(); s1.tag = 5;
        Sector s2 = new Sector(); s2.tag = 9;
        Sector s3 = new Sector(); s3.tag = 9;
        PSetup.sectors = new Sector[] { s0, s1, s2, s3 };
        PSetup.numsectors = 4;
        Line line = new Line();
        line.tag = 9;

        int a = PSpec.pFindSectorFromLineTag(line, -1);
        int b = PSpec.pFindSectorFromLineTag(line, a);
        int c = PSpec.pFindSectorFromLineTag(line, b);
        int d = PSpec.pFindSectorFromLineTag(line, c);

        assertEquals(0, a);
        assertEquals(2, b);
        assertEquals(3, c);
        assertEquals(-1, d); // no more matches
    }

    // P_FindMinSurroundingLight: a sector with two adjacent neighbours
    // picks the darker one; the original max bounds the result when
    // every neighbour is brighter.
    @Test
    void findMinSurroundingLightPicksDarkestNeighbour() {
        Sector center = new Sector();
        center.lightlevel = 200;
        Sector dark = new Sector();   dark.lightlevel = 80;
        Sector bright = new Sector(); bright.lightlevel = 240;

        Line l1 = new Line();
        l1.flags = Line.ML_TWOSIDED;
        l1.frontsector = center;
        l1.backsector = dark;
        Line l2 = new Line();
        l2.flags = Line.ML_TWOSIDED;
        l2.frontsector = center;
        l2.backsector = bright;

        center.lines = new Line[] { l1, l2 };
        center.linecount = 2;

        assertEquals(80, PSpec.pFindMinSurroundingLight(center, 200));
    }

    @Test
    void findMinSurroundingLightFallsBackToMaxWhenNoDarkerNeighbour() {
        Sector center = new Sector();
        Sector bright = new Sector(); bright.lightlevel = 250;

        Line l = new Line();
        l.flags = Line.ML_TWOSIDED;
        l.frontsector = center;
        l.backsector = bright;

        center.lines = new Line[] { l };
        center.linecount = 1;

        assertEquals(128, PSpec.pFindMinSurroundingLight(center, 128));
    }

    // One-sided neighbours (no ML_TWOSIDED) are ignored.
    @Test
    void findMinSurroundingLightSkipsOneSidedLines() {
        Sector center = new Sector();

        Line oneSided = new Line();
        oneSided.flags = 0;
        oneSided.frontsector = center;
        oneSided.backsector = null;

        center.lines = new Line[] { oneSided };
        center.linecount = 1;

        assertEquals(100, PSpec.pFindMinSurroundingLight(center, 100));
    }

    // --- Surrounding floor / ceiling walkers ---

    // Helper: two-sided line between `a` and `b`.
    private static Line twoSided(Sector front, Sector back) {
        Line l = new Line();
        l.flags = Line.ML_TWOSIDED;
        l.frontsector = front;
        l.backsector = back;
        return l;
    }

    @Test
    void findLowestFloorSurroundingSeedsWithOwnFloor() {
        Sector center = new Sector();
        center.floorheight = 100;
        Sector lower = new Sector();  lower.floorheight = 40;
        Sector higher = new Sector(); higher.floorheight = 160;

        center.lines = new Line[] { twoSided(center, lower), twoSided(center, higher) };
        center.linecount = 2;

        assertEquals(40, PSpec.pFindLowestFloorSurrounding(center));
    }

    // No neighbour darker than the seed → seed wins (own floor).
    @Test
    void findLowestFloorSurroundingFallsBackToOwnFloor() {
        Sector center = new Sector();
        center.floorheight = 100;
        Sector higher = new Sector(); higher.floorheight = 200;

        center.lines = new Line[] { twoSided(center, higher) };
        center.linecount = 1;

        assertEquals(100, PSpec.pFindLowestFloorSurrounding(center));
    }

    // Isolated sector with no two-sided neighbours returns the
    // -500 * FRACUNIT sentinel unchanged.
    @Test
    void findHighestFloorSurroundingReturnsSentinelWhenIsolated() {
        Sector center = new Sector();
        center.floorheight = 100;
        Line oneSided = new Line();
        oneSided.flags = 0;
        oneSided.frontsector = center;
        oneSided.backsector = null;

        center.lines = new Line[] { oneSided };
        center.linecount = 1;

        assertEquals(-500 * doom.fixed.Fixed.FRACUNIT,
                PSpec.pFindHighestFloorSurrounding(center));
    }

    @Test
    void findHighestFloorSurroundingPicksMaxAcrossNeighbours() {
        Sector center = new Sector();
        Sector a = new Sector(); a.floorheight = 50;
        Sector b = new Sector(); b.floorheight = 170;
        Sector c = new Sector(); c.floorheight = 90;

        center.lines = new Line[] { twoSided(center, a), twoSided(center, b), twoSided(center, c) };
        center.linecount = 3;

        assertEquals(170, PSpec.pFindHighestFloorSurrounding(center));
    }

    // P_FindNextHighestFloor: min of neighbours strictly above current.
    @Test
    void findNextHighestFloorPicksSmallestStrictlyAbove() {
        Sector center = new Sector();
        Sector a = new Sector(); a.floorheight = 50;
        Sector b = new Sector(); b.floorheight = 80;
        Sector c = new Sector(); c.floorheight = 120;
        Sector d = new Sector(); d.floorheight = 70;

        center.lines = new Line[] {
                twoSided(center, a), twoSided(center, b),
                twoSided(center, c), twoSided(center, d) };
        center.linecount = 4;

        assertEquals(70, PSpec.pFindNextHighestFloor(center, 60));
    }

    // No neighbour above current → return currentheight (the C quirk).
    @Test
    void findNextHighestFloorReturnsCurrentWhenNoneAbove() {
        Sector center = new Sector();
        Sector low = new Sector(); low.floorheight = 10;

        center.lines = new Line[] { twoSided(center, low) };
        center.linecount = 1;

        assertEquals(100, PSpec.pFindNextHighestFloor(center, 100));
    }

    @Test
    void findNextHighestFloorIgnoresOneSidedNeighbours() {
        Sector center = new Sector();
        Line oneSided = new Line();
        oneSided.flags = 0;
        oneSided.frontsector = center;
        oneSided.backsector = null;

        center.lines = new Line[] { oneSided };
        center.linecount = 1;

        assertEquals(50, PSpec.pFindNextHighestFloor(center, 50));
    }

    // MAX_ADJOINING_SECTORS cap: more than 20 candidates triggers the
    // warning + break. Result is the min of the first 20 collected.
    @Test
    void findNextHighestFloorCapsAt20Adjoining() {
        Sector center = new Sector();
        Line[] lines = new Line[25];
        // Heights 100..124, all strictly above currentheight=0.
        for (int i = 0; i < 25; i++) {
            Sector n = new Sector();
            n.floorheight = 100 + i;
            lines[i] = twoSided(center, n);
        }
        center.lines = lines;
        center.linecount = 25;

        // Collects 20 (heights 100..119), min = 100.
        assertEquals(100, PSpec.pFindNextHighestFloor(center, 0));
    }

    @Test
    void findLowestCeilingSurroundingPicksMin() {
        Sector center = new Sector();
        Sector a = new Sector(); a.ceilingheight = 300;
        Sector b = new Sector(); b.ceilingheight = 180;
        Sector c = new Sector(); c.ceilingheight = 220;

        center.lines = new Line[] { twoSided(center, a), twoSided(center, b), twoSided(center, c) };
        center.linecount = 3;

        assertEquals(180, PSpec.pFindLowestCeilingSurrounding(center));
    }

    @Test
    void findLowestCeilingSurroundingReturnsMaxIntWhenIsolated() {
        Sector center = new Sector();
        Line oneSided = new Line();
        oneSided.flags = 0;
        oneSided.frontsector = center;
        oneSided.backsector = null;

        center.lines = new Line[] { oneSided };
        center.linecount = 1;

        assertEquals(Integer.MAX_VALUE, PSpec.pFindLowestCeilingSurrounding(center));
    }

    @Test
    void findHighestCeilingSurroundingPicksMax() {
        Sector center = new Sector();
        Sector a = new Sector(); a.ceilingheight = 100;
        Sector b = new Sector(); b.ceilingheight = 400;

        center.lines = new Line[] { twoSided(center, a), twoSided(center, b) };
        center.linecount = 2;

        assertEquals(400, PSpec.pFindHighestCeilingSurrounding(center));
    }

    // Isolated sector → seed 0 is returned.
    @Test
    void findHighestCeilingSurroundingReturnsZeroWhenIsolated() {
        Sector center = new Sector();
        center.lines = new Line[0];
        center.linecount = 0;

        assertEquals(0, PSpec.pFindHighestCeilingSurrounding(center));
    }

    // --- P_UpdateSpecials ---

    // Seeds a single-side Side array with a given set of textures.
    private static void seedOneSide(int topTex, int midTex, int botTex) {
        Side side = new Side();
        side.toptexture = topTex;
        side.midtexture = midTex;
        side.bottomtexture = botTex;
        PSetup.sides = new Side[] { side };
        PSetup.numsides = 1;
    }

    // Button countdown: btimer > 1 just decrements.
    @Test
    void updateSpecialsButtonTimerJustDecrements() {
        seedOneSide(0, 0, 0);
        Line line = new Line();
        line.sidenum[0] = 0;

        PSwitch.Button b = PSwitch.buttonlist[0];
        b.line = line;
        b.where = PSwitch.top;
        b.btexture = 7;
        b.btimer = 10;

        PSpec.pUpdateSpecials();

        assertEquals(9, b.btimer);
        assertEquals(0, PSetup.sides[0].toptexture); // not yet reverted
    }

    // Button countdown: hitting 0 reverts the texture back to btexture,
    // then memsets the slot.
    @Test
    void updateSpecialsButtonTimerRestoresTopTexture() {
        seedOneSide(99, 0, 0);           // "pressed" texture
        Line line = new Line();
        line.sidenum[0] = 0;

        PSwitch.Button b = PSwitch.buttonlist[0];
        b.line = line;
        b.where = PSwitch.top;
        b.btexture = 42;                 // original "off" texture
        b.btimer = 1;

        PSpec.pUpdateSpecials();

        assertEquals(42, PSetup.sides[0].toptexture);
        // Slot cleared (memset).
        assertEquals(0, b.btimer);
        assertNull(b.line);
        assertEquals(0, b.btexture);
    }

    @Test
    void updateSpecialsButtonTimerRestoresMidTexture() {
        seedOneSide(0, 99, 0);
        Line line = new Line();
        line.sidenum[0] = 0;

        PSwitch.Button b = PSwitch.buttonlist[0];
        b.line = line;
        b.where = PSwitch.middle;
        b.btexture = 55;
        b.btimer = 1;

        PSpec.pUpdateSpecials();

        assertEquals(55, PSetup.sides[0].midtexture);
    }

    @Test
    void updateSpecialsButtonTimerRestoresBottomTexture() {
        seedOneSide(0, 0, 99);
        Line line = new Line();
        line.sidenum[0] = 0;

        PSwitch.Button b = PSwitch.buttonlist[0];
        b.line = line;
        b.where = PSwitch.bottom;
        b.btexture = 77;
        b.btimer = 1;

        PSpec.pUpdateSpecials();

        assertEquals(77, PSetup.sides[0].bottomtexture);
    }

    // Line-scroll special 48 increments textureoffset by FRACUNIT each tic.
    @Test
    void updateSpecialsLineScroll48IncrementsTextureOffset() {
        seedOneSide(0, 0, 0);
        PSetup.sides[0].textureoffset = 100;

        Line line = new Line();
        line.sidenum[0] = 0;
        line.special = 48;

        PSpec.linespeciallist[0] = line;
        PSpec.numlinespecials = 1;

        PSpec.pUpdateSpecials();

        assertEquals(100 + doom.fixed.Fixed.FRACUNIT, PSetup.sides[0].textureoffset);
    }

    // Non-scrolling special lines are left alone.
    @Test
    void updateSpecialsIgnoresNonScrollSpecials() {
        seedOneSide(0, 0, 0);
        PSetup.sides[0].textureoffset = 77;

        Line line = new Line();
        line.sidenum[0] = 0;
        line.special = 99;

        PSpec.linespeciallist[0] = line;
        PSpec.numlinespecials = 1;

        PSpec.pUpdateSpecials();

        assertEquals(77, PSetup.sides[0].textureoffset);
    }

    // Level timer: counts down when active.
    @Test
    void updateSpecialsLevelTimerDecrementsWhenActive() {
        PSpec.levelTimer = true;
        PSpec.levelTimeCount = 10;

        PSpec.pUpdateSpecials();

        assertEquals(9, PSpec.levelTimeCount);
    }

    // Level timer: inactive → not decremented.
    @Test
    void updateSpecialsLevelTimerIgnoredWhenOff() {
        PSpec.levelTimer = false;
        PSpec.levelTimeCount = 10;

        PSpec.pUpdateSpecials();

        assertEquals(10, PSpec.levelTimeCount);
    }

    // Flat/texture animation cycles texturetranslation[] for each
    // entry in `anims[]`.
    @Test
    void updateSpecialsAnimatesTextureIndicesWhenInstalled() {
        // Save + stub the RData translation arrays.
        int[] savedTex = RData.texturetranslation;
        int[] savedFlat = RData.flattranslation;
        try {
            RData.texturetranslation = new int[16];
            RData.flattranslation = new int[16];

            // Install one texture anim: basepic=5, numpics=3, speed=2.
            PSpec.Anim a = PSpec.anims[0];
            a.istexture = true;
            a.basepic = 5;
            a.numpics = 3;
            a.picnum = 7;
            a.speed = 2;
            PSpec.numanims = 1;

            PTick.leveltime = 4;

            PSpec.pUpdateSpecials();

            // For each i in [basepic, basepic+numpics):
            //   pic = basepic + ((leveltime / speed + i) % numpics)
            // leveltime/speed = 2. i=5: (2+5)%3 = 1 → pic=6. i=6: 2%3 → pic=7.
            // i=7: (2+7)%3 = 0 → pic=5.
            assertEquals(6, RData.texturetranslation[5]);
            assertEquals(7, RData.texturetranslation[6]);
            assertEquals(5, RData.texturetranslation[7]);
        } finally {
            RData.texturetranslation = savedTex;
            RData.flattranslation = savedFlat;
        }
    }

    // Flat animation: same logic, writes flattranslation[] when istexture = false.
    @Test
    void updateSpecialsAnimatesFlatIndices() {
        int[] savedTex = RData.texturetranslation;
        int[] savedFlat = RData.flattranslation;
        try {
            RData.texturetranslation = new int[16];
            RData.flattranslation = new int[16];

            PSpec.Anim a = PSpec.anims[0];
            a.istexture = false;
            a.basepic = 2;
            a.numpics = 2;
            a.picnum = 3;
            a.speed = 1;
            PSpec.numanims = 1;

            PTick.leveltime = 1;

            PSpec.pUpdateSpecials();

            // leveltime/speed = 1. i=2: (1+2)%2 = 1 → 2+1 = 3.
            //                    i=3: (1+3)%2 = 0 → 2+0 = 2.
            assertEquals(3, RData.flattranslation[2]);
            assertEquals(2, RData.flattranslation[3]);
            // texturetranslation untouched.
            assertEquals(0, RData.texturetranslation[2]);
        } finally {
            RData.texturetranslation = savedTex;
            RData.flattranslation = savedFlat;
        }
    }

    // Sanity: animdefs[] carries the expected number of real entries.
    @Test
    void animdefsCountMatchesCReference() {
        int nonNull = 0;
        for (PSpec.AnimDef d : PSpec.animdefs) {
            if (d != null) nonNull++;
        }
        // Doom 1 + Doom 2 flat animations + texture animations = 22.
        assertEquals(22, nonNull);
    }

    // Sizing constants match the C header.
    @Test
    void animSizingConstantsMatchHeader() {
        assertEquals(32, PSpec.MAXANIMS);
        assertEquals(64, PSpec.MAXLINEANIMS);
    }

    // --- P_SpawnSpecials ---

    // Sector with special == 9 → increments totalsecret but doesn't
    // spawn a thinker.
    @Test
    void spawnSpecialsSecretSectorIncrementsCounter() {
        Sector sec = new Sector();
        sec.special = 9;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;
        PSetup.lines = new Line[0];
        PSetup.numlines = 0;

        PSpec.pSpawnSpecials();

        assertEquals(1, Doomstat.totalsecret);
        assertNull(sec.specialdata); // no thinker
    }

    // Sector special 1 → spawns a LightFlash thinker.
    @Test
    void spawnSpecialsLightFlashesOnSpecial1() {
        Sector sec = new Sector();
        sec.special = 1;
        sec.lightlevel = 200;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;
        PSetup.lines = new Line[0];
        PSetup.numlines = 0;

        PSpec.pSpawnSpecials();

        // Walk the thinker list for a LightFlash.
        boolean found = false;
        for (Thinker t = PTick.thinkercap.next; t != PTick.thinkercap; t = t.next) {
            if (t instanceof PLights.LightFlash) {
                found = true;
                break;
            }
        }
        assertTrue(found);
    }

    // Sector special 4 → strobe AND preserves special (restored to 4
    // after P_SpawnStrobeFlash, which the C clears on spawn).
    @Test
    void spawnSpecialsSpecial4RestoresSpecialAfterStrobe() {
        Sector sec = new Sector();
        sec.special = 4;
        sec.lightlevel = 200;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;
        PSetup.lines = new Line[0];
        PSetup.numlines = 0;

        PSpec.pSpawnSpecials();

        // P_SpawnStrobeFlash clears special → 0; then the C re-assigns
        // special = 4. The Java port preserves this verbatim.
        assertEquals(4, sec.special);
    }

    // Sector special 10 → door-close-in-30 door. Verify type + direction.
    @Test
    void spawnSpecialsDoorCloseIn30SpawnsWaitingDoor() {
        Sector sec = new Sector();
        sec.special = 10;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;
        PSetup.lines = new Line[0];
        PSetup.numlines = 0;

        PSpec.pSpawnSpecials();

        PDoors.VLDoor d = (PDoors.VLDoor) sec.specialdata;
        assertNotNull(d);
        assertEquals(PDoors.normal, d.type);
        assertEquals(0, d.direction);         // waiting
        assertEquals(30 * 35, d.topcountdown); // 30s
    }

    // Sector special 14 → raise-in-5-minutes door.
    @Test
    void spawnSpecialsRaiseIn5MinsSpawnsInitialWaitDoor() {
        Sector sec = new Sector();
        sec.special = 14;
        sec.ceilingheight = 200;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;
        PSetup.lines = new Line[0];
        PSetup.numlines = 0;

        PSpec.pSpawnSpecials();

        PDoors.VLDoor d = (PDoors.VLDoor) sec.specialdata;
        assertNotNull(d);
        assertEquals(PDoors.raiseIn5Mins, d.type);
        assertEquals(2, d.direction);              // INITIAL WAIT
        assertEquals(5 * 60 * 35, d.topcountdown); // 5min
    }

    // Sector special 17 → fire-flicker thinker.
    @Test
    void spawnSpecialsFireFlickerOnSpecial17() {
        Sector sec = new Sector();
        sec.special = 17;
        sec.lightlevel = 200;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;
        PSetup.lines = new Line[0];
        PSetup.numlines = 0;

        PSpec.pSpawnSpecials();

        boolean found = false;
        for (Thinker t = PTick.thinkercap.next; t != PTick.thinkercap; t = t.next) {
            if (t instanceof PLights.FireFlicker) {
                found = true;
                break;
            }
        }
        assertTrue(found);
    }

    // Sector special 0 → untouched.
    @Test
    void spawnSpecialsIgnoresZeroSpecials() {
        Sector sec = new Sector();
        sec.special = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;
        PSetup.lines = new Line[0];
        PSetup.numlines = 0;

        PSpec.pSpawnSpecials();

        assertNull(sec.specialdata);
    }

    // Unknown special (not in switch) is a no-op.
    @Test
    void spawnSpecialsSkipsUnknownSectorSpecials() {
        Sector sec = new Sector();
        sec.special = 999;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;
        PSetup.lines = new Line[0];
        PSetup.numlines = 0;

        PSpec.pSpawnSpecials();

        assertNull(sec.specialdata);
    }

    // Line special 48 → appended to linespeciallist.
    @Test
    void spawnSpecialsRegistersScrollingLines() {
        Line scroll1 = new Line(); scroll1.special = 48;
        Line other   = new Line(); other.special   = 7;   // not tracked
        Line scroll2 = new Line(); scroll2.special = 48;
        PSetup.lines = new Line[] { scroll1, other, scroll2 };
        PSetup.numlines = 3;
        PSetup.sectors = new Sector[0];
        PSetup.numsectors = 0;

        PSpec.pSpawnSpecials();

        assertEquals(2, PSpec.numlinespecials);
        assertSame(scroll1, PSpec.linespeciallist[0]);
        assertSame(scroll2, PSpec.linespeciallist[1]);
    }

    // -avg needs deathmatch > 0 to activate.
    @Test
    void spawnSpecialsAvgTimerNeedsDeathmatch() {
        PSetup.sectors = new Sector[0];
        PSetup.numsectors = 0;
        PSetup.lines = new Line[0];
        PSetup.numlines = 0;
        MArgv.myargv = new String[] { "jfxdoom", "-avg" };
        MArgv.myargc = 2;
        Doomstat.deathmatch = 0; // NOT deathmatch

        PSpec.pSpawnSpecials();

        assertFalse(PSpec.levelTimer);
        assertEquals(0, PSpec.levelTimeCount);
    }

    // -avg + deathmatch → 20-minute timer.
    @Test
    void spawnSpecialsAvgTimerInDeathmatchSetsTwentyMinutes() {
        PSetup.sectors = new Sector[0];
        PSetup.numsectors = 0;
        PSetup.lines = new Line[0];
        PSetup.numlines = 0;
        MArgv.myargv = new String[] { "jfxdoom", "-avg" };
        MArgv.myargc = 2;
        Doomstat.deathmatch = 1;

        PSpec.pSpawnSpecials();

        assertTrue(PSpec.levelTimer);
        assertEquals(20 * 60 * 35, PSpec.levelTimeCount);
    }

    // -timer N + deathmatch → N-minute timer.
    @Test
    void spawnSpecialsTimerArgSetsMinutes() {
        PSetup.sectors = new Sector[0];
        PSetup.numsectors = 0;
        PSetup.lines = new Line[0];
        PSetup.numlines = 0;
        MArgv.myargv = new String[] { "jfxdoom", "-timer", "7" };
        MArgv.myargc = 3;
        Doomstat.deathmatch = 1;

        PSpec.pSpawnSpecials();

        assertTrue(PSpec.levelTimer);
        assertEquals(7 * 60 * 35, PSpec.levelTimeCount);
    }

    // -timer N without deathmatch → inactive.
    @Test
    void spawnSpecialsTimerArgNeedsDeathmatch() {
        PSetup.sectors = new Sector[0];
        PSetup.numsectors = 0;
        PSetup.lines = new Line[0];
        PSetup.numlines = 0;
        MArgv.myargv = new String[] { "jfxdoom", "-timer", "5" };
        MArgv.myargc = 3;
        Doomstat.deathmatch = 0;

        PSpec.pSpawnSpecials();

        assertFalse(PSpec.levelTimer);
    }

    // Malformed -timer argument → levelTimeCount ends up 0 (atoi parity).
    @Test
    void spawnSpecialsTimerArgLenientOnBadNumber() {
        PSetup.sectors = new Sector[0];
        PSetup.numsectors = 0;
        PSetup.lines = new Line[0];
        PSetup.numlines = 0;
        MArgv.myargv = new String[] { "jfxdoom", "-timer", "notanumber" };
        MArgv.myargc = 3;
        Doomstat.deathmatch = 2;

        PSpec.pSpawnSpecials();

        assertTrue(PSpec.levelTimer);
        assertEquals(0, PSpec.levelTimeCount);
    }

    // Spawn clears the plat / ceiling / button tables even when empty of
    // game-specials.
    @Test
    void spawnSpecialsClearsActiveTables() {
        // Pre-fill with garbage to make sure spawn wipes them.
        PCeilng.activeceilings[0] = new PCeilng.Ceiling();
        PPlats.activeplats[0] = new PPlats.Plat();
        PSwitch.buttonlist[0].btimer = 99;

        PSetup.sectors = new Sector[0];
        PSetup.numsectors = 0;
        PSetup.lines = new Line[0];
        PSetup.numlines = 0;

        PSpec.pSpawnSpecials();

        assertNull(PCeilng.activeceilings[0]);
        assertNull(PPlats.activeplats[0]);
        assertEquals(0, PSwitch.buttonlist[0].btimer);
    }

    // ----- P_CrossSpecialLine -----

    // Helper: 1-line, 1-tagged-sector world. Line[linenum] is the trigger.
    private static int crossWorld(int special, int tag) {
        Sector sec = new Sector();
        sec.floorheight = 0;
        sec.ceilingheight = 200 * Fixed.FRACUNIT;
        sec.tag = tag;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.special = special;
        line.tag = tag;
        line.sidenum[0] = -1;
        line.sidenum[1] = -1;
        PSetup.lines = new Line[] { line };
        PSetup.numlines = 1;
        return 0; // line index
    }

    private static Mobj playerMobj() {
        Mobj m = new Mobj();
        m.player = new Player();
        return m;
    }

    private static Mobj missileMobj(int type) {
        Mobj m = new Mobj();
        m.type = type;   // no .player
        return m;
    }

    private static Mobj monsterMobj() {
        Mobj m = new Mobj();
        m.type = Info.MT_TROOP;
        return m;
    }

    // Missile mobjs (rocket / plasma / BFG / fireballs) get the early
    // return — no special is dispatched.
    @Test
    void crossSpecialLineMissileNeverDispatches() {
        int idx = crossWorld(/*special*/2 /*Open Door*/, 7);

        PSpec.pCrossSpecialLine(idx, /*side*/0, missileMobj(Info.MT_ROCKET));

        // Door not spawned, special intact (the dispatch never fires).
        assertNull(PSetup.sectors[0].specialdata);
        assertEquals(2, PSetup.lines[idx].special);
    }

    // Monster on a non-allow-listed special (e.g. case 5: Raise Floor) → return.
    @Test
    void crossSpecialLineMonsterFiltersToAllowList() {
        int idx = crossWorld(5 /*Raise Floor*/, 7);

        PSpec.pCrossSpecialLine(idx, 0, monsterMobj());

        assertNull(PSetup.sectors[0].specialdata);
        assertEquals(5, PSetup.lines[idx].special);  // not cleared
    }

    // Monster on case 4 (RAISE DOOR) is allow-listed → dispatch fires.
    @Test
    void crossSpecialLineMonsterCanRaiseDoor() {
        int idx = crossWorld(4 /*Raise Door*/, 7);

        PSpec.pCrossSpecialLine(idx, 0, monsterMobj());

        assertNotNull(PSetup.sectors[0].specialdata);
        assertEquals(0, PSetup.lines[idx].special); // cleared (one-shot)
    }

    // Player on case 2 (Open Door) → door spawned + special cleared.
    @Test
    void crossSpecialLineOpenDoorTrigger() {
        int idx = crossWorld(2, 7);

        PSpec.pCrossSpecialLine(idx, 0, playerMobj());

        assertNotNull(PSetup.sectors[0].specialdata);
        assertEquals(0, PSetup.lines[idx].special);
    }

    // Player on case 86 (Open Door RETRIGGER) → spawned + special NOT cleared.
    @Test
    void crossSpecialLineOpenDoorRetriggerKeepsSpecial() {
        int idx = crossWorld(86, 7);

        PSpec.pCrossSpecialLine(idx, 0, playerMobj());

        assertNotNull(PSetup.sectors[0].specialdata);
        assertEquals(86, PSetup.lines[idx].special);
    }

    // Case 52 (EXIT!) → flips GGame.gameaction without clearing special.
    @Test
    void crossSpecialLineExitTrigger() {
        int idx = crossWorld(52, 0);

        PSpec.pCrossSpecialLine(idx, 0, playerMobj());

        assertEquals(GGame.ga_completed, GGame.gameaction);
        assertEquals(0, GGame.secretexit);
        // C never clears line.special for case 52 (no `line->special = 0`).
        assertEquals(52, PSetup.lines[idx].special);
    }

    // Case 124 (Secret EXIT) → flips secretexit latch.
    @Test
    void crossSpecialLineSecretExitTrigger() {
        int idx = crossWorld(124, 0);

        PSpec.pCrossSpecialLine(idx, 0, playerMobj());

        assertEquals(GGame.ga_completed, GGame.gameaction);
        assertEquals(1, GGame.secretexit);
    }

    // Case 40 (RaiseCeilingLowerFloor) — the unusual two-action case.
    // Both EV_DoCeiling and EV_DoFloor get called.
    @Test
    void crossSpecialLine40RaisesCeilingAndLowersFloor() {
        // Set up a sector that has a higher neighbor and matching tag.
        Sector target = new Sector();
        target.floorheight = 50 * Fixed.FRACUNIT;
        target.ceilingheight = 100 * Fixed.FRACUNIT;
        target.tag = 7;
        Sector neighbor = new Sector();
        neighbor.floorheight = 0;
        neighbor.ceilingheight = 300 * Fixed.FRACUNIT;
        neighbor.tag = 0;

        // Two-sided line connects them.
        Line conn = new Line();
        conn.flags = Line.ML_TWOSIDED;
        conn.frontsector = target;
        conn.backsector = neighbor;
        target.lines = new Line[] { conn };
        target.linecount = 1;

        PSetup.sectors = new Sector[] { target, neighbor };
        PSetup.numsectors = 2;

        Line trigger = new Line();
        trigger.special = 40;
        trigger.tag = 7;
        trigger.sidenum[0] = -1;
        trigger.sidenum[1] = -1;
        PSetup.lines = new Line[] { trigger };
        PSetup.numlines = 1;

        // case 40 fires EV_DoCeiling first (sets specialdata), then
        // EV_DoFloor — but the second call sees specialdata != null and
        // skips. So target ends with a Ceiling thinker (the C does
        // exactly the same thing).
        PSpec.pCrossSpecialLine(0, 0, playerMobj());

        assertTrue(target.specialdata instanceof PCeilng.Ceiling);
        assertEquals(0, trigger.special);
    }

    // Case 125 (TELEPORT MonsterONLY): player calls do nothing.
    @Test
    void crossSpecialLine125PlayerIgnored() {
        int idx = crossWorld(125, 7);

        PSpec.pCrossSpecialLine(idx, 0, playerMobj());

        // The C falls through the player check (player isn't in the
        // ok-list for non-trigger logic), then enters the dispatch...
        // wait actually case 125 IS in the player's allow-list because
        // the outer guard only fires when thing.player == null. With a
        // player, the outer guard is skipped entirely, dispatch runs,
        // and case 125's `if (!thing.player)` body returns without
        // teleporting OR clearing special.
        assertEquals(125, PSetup.lines[idx].special);
    }

    // ----- P_ShootSpecialLine -----

    // Player on case 24 (Raise Floor) → spawns a floor-mover. Needs a
    // valid front side because the post-dispatch call to
    // pChangeSwitchTexture reads PSetup.sides[line.sidenum[0]].
    @Test
    void shootSpecialLineRaiseFloorByPlayer() {
        Sector sec = new Sector();
        sec.floorheight = 0;
        sec.ceilingheight = 200 * Fixed.FRACUNIT;
        sec.tag = 7;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Side front = new Side();
        front.sector = sec;
        PSetup.sides = new Side[] { front };
        PSetup.numsides = 1;

        Line line = new Line();
        line.special = 24;
        line.tag = 7;
        line.sidenum[0] = 0;
        line.sidenum[1] = -1;
        line.frontsector = sec;

        PSpec.pShootSpecialLine(playerMobj(), line);

        assertTrue(sec.specialdata instanceof PFloor.FloorMove);
    }

    // Monster shooting a non-46 special → no-op.
    @Test
    void shootSpecialLineMonsterFiltersToCase46() {
        Sector sec = new Sector();
        sec.tag = 7;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.special = 24;  // not in the monster allow-list (only 46 is)
        line.tag = 7;
        line.sidenum[0] = -1;
        line.sidenum[1] = -1;

        PSpec.pShootSpecialLine(monsterMobj(), line);

        assertNull(sec.specialdata);
    }

    // Monster on case 46 (OPEN DOOR IMPACT) → allowed. Front side
    // needed for the pChangeSwitchTexture follow-up.
    @Test
    void shootSpecialLineMonsterCanOpenCase46() {
        Sector sec = new Sector();
        sec.ceilingheight = 200 * Fixed.FRACUNIT;
        sec.tag = 7;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Side front = new Side();
        front.sector = sec;
        PSetup.sides = new Side[] { front };
        PSetup.numsides = 1;

        Line line = new Line();
        line.special = 46;
        line.tag = 7;
        line.sidenum[0] = 0;
        line.sidenum[1] = -1;

        PSpec.pShootSpecialLine(monsterMobj(), line);

        assertNotNull(sec.specialdata);
    }
}
