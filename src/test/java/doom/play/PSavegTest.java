package doom.play;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.Sector;
import doom.render.Side;
import doom.render.Subsector;
import doom.system.Doomdef;
import doom.system.GGame;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PSavegTest {

    private Sector sec;
    private Subsector ss;

    @BeforeEach
    void resetWorld() {
        // One-sector world.
        sec = new Sector();
        sec.floorheight = 0;
        sec.ceilingheight = 200 * Fixed.FRACUNIT;
        sec.floorpic = 5;
        sec.ceilingpic = 7;
        sec.lightlevel = 192;
        sec.special = 0;
        sec.tag = 0;

        ss = new Subsector();
        ss.sector = sec;

        Side sd = new Side();
        sd.textureoffset = 0;
        sd.rowoffset = 0;
        sd.toptexture = 1;
        sd.bottomtexture = 2;
        sd.midtexture = 3;
        sd.sector = sec;

        Line ld = new Line();
        ld.flags = Line.ML_TWOSIDED;
        ld.special = 0;
        ld.tag = 0;
        ld.sidenum[0] = 0;
        ld.sidenum[1] = -1;

        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;
        PSetup.subsectors = new Subsector[] { ss };
        PSetup.numsubsectors = 1;
        PSetup.numnodes = 0;
        PSetup.nodes = new doom.render.Node[0];
        PSetup.sides = new Side[] { sd };
        PSetup.numsides = 1;
        PSetup.lines = new Line[] { ld };
        PSetup.numlines = 1;
        PSetup.bmaporgx = -64 * Fixed.FRACUNIT;
        PSetup.bmaporgy = -64 * Fixed.FRACUNIT;
        PSetup.bmapwidth = 1;
        PSetup.bmapheight = 1;
        PSetup.blocklinks = new Mobj[1];
        PSetup.blockmaplump = new int[] { -64, -64, 1, 1, 5, -1 };
        PSetup.blockmap = PSetup.blockmaplump;

        PTick.pInitThinkers();
        PMap.changeSectorOverride = true;
        PMap.changeSectorResult = false;

        // Reset roster.
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            GGame.players[i] = new Player();
            GGame.playeringame[i] = false;
        }
        GGame.consoleplayer = 0;

        // Reset active-thinker tables (PSaveg.pUnArchiveSpecials
        // appends through P_AddActiveCeiling / P_AddActivePlat).
        for (int i = 0; i < PCeilng.MAXCEILINGS; i++) {
            PCeilng.activeceilings[i] = null;
        }
        for (int i = 0; i < PPlats.MAXPLATS; i++) {
            PPlats.activeplats[i] = null;
        }
    }

    // --- Player round-trip ---

    @Test
    void playerSaveLoadPreservesAmmoAndWeapon() throws IOException {
        GGame.playeringame[0] = true;
        Player p = GGame.players[0];
        p.health = 75;
        p.armorpoints = 50;
        p.armortype = 1;
        p.readyweapon = Doomdef.wp_shotgun;
        p.weaponowned[Doomdef.wp_shotgun] = true;
        p.ammo[Doomdef.am_shell] = 32;
        p.maxammo[Doomdef.am_shell] = 50;
        p.cards[Doomdef.it_bluecard] = true;
        p.killcount = 12;
        p.itemcount = 7;
        p.cheats = Player.CF_GODMODE;

        byte[] saved = PSaveg.save();

        // Mutate state.
        p.health = 1;
        p.armorpoints = 0;
        p.weaponowned[Doomdef.wp_shotgun] = false;
        p.ammo[Doomdef.am_shell] = 0;
        p.cards[Doomdef.it_bluecard] = false;
        p.cheats = 0;

        PSaveg.load(saved);

        assertEquals(75, p.health);
        assertEquals(50, p.armorpoints);
        assertEquals(1, p.armortype);
        assertEquals(Doomdef.wp_shotgun, p.readyweapon);
        assertTrue(p.weaponowned[Doomdef.wp_shotgun]);
        assertEquals(32, p.ammo[Doomdef.am_shell]);
        assertEquals(50, p.maxammo[Doomdef.am_shell]);
        assertTrue(p.cards[Doomdef.it_bluecard]);
        assertEquals(12, p.killcount);
        assertEquals(7, p.itemcount);
        assertEquals(Player.CF_GODMODE, p.cheats);
        // mo / message / attacker reset by unarchive.
        assertNull(p.mo);
        assertNull(p.message);
        assertNull(p.attacker);
    }

    // --- World round-trip ---

    @Test
    void worldSaveLoadPreservesSectorAndSideState() throws IOException {
        // Mutate sector + side after the BeforeEach defaults.
        sec.floorheight = 64 * Fixed.FRACUNIT;
        sec.ceilingheight = 256 * Fixed.FRACUNIT;
        sec.floorpic = 11;
        sec.ceilingpic = 13;
        sec.lightlevel = 128;
        sec.special = 1;
        sec.tag = 42;
        PSetup.sides[0].toptexture = 99;
        PSetup.sides[0].midtexture = 100;
        PSetup.lines[0].flags = Line.ML_TWOSIDED | Line.ML_BLOCKING;
        PSetup.lines[0].special = 31;
        PSetup.lines[0].tag = 17;

        byte[] saved = PSaveg.save();

        // Mutate.
        sec.floorheight = 0;
        sec.lightlevel = 0;
        PSetup.sides[0].toptexture = 0;
        PSetup.lines[0].flags = 0;
        PSetup.lines[0].tag = 0;

        PSaveg.load(saved);

        assertEquals(64 * Fixed.FRACUNIT, sec.floorheight);
        assertEquals(256 * Fixed.FRACUNIT, sec.ceilingheight);
        assertEquals(11, sec.floorpic);
        assertEquals(13, sec.ceilingpic);
        assertEquals(128, sec.lightlevel);
        assertEquals(1, sec.special);
        assertEquals(42, sec.tag);
        assertEquals(99, PSetup.sides[0].toptexture);
        assertEquals(100, PSetup.sides[0].midtexture);
        assertEquals(Line.ML_TWOSIDED | Line.ML_BLOCKING, PSetup.lines[0].flags);
        assertEquals(31, PSetup.lines[0].special);
        assertEquals(17, PSetup.lines[0].tag);
        // specialdata / soundtarget reset on unarchive.
        assertNull(sec.specialdata);
        assertNull(sec.soundtarget);
    }

    // --- Mobj thinker round-trip ---

    @Test
    void mobjSaveLoadRecreatesMobjThinker() throws IOException {
        // Spawn a plain mobj (no Player back-pointer).
        Mobj orig = PMobj.pSpawnMobj(10 * Fixed.FRACUNIT,
                20 * Fixed.FRACUNIT,
                PMobj.ONFLOORZ, Info.MT_POSSESSED);
        orig.health = 17;
        orig.tics = 4;
        orig.movecount = 9;

        byte[] saved = PSaveg.save();

        // Wipe the thinker chain.
        PTick.pInitThinkers();

        PSaveg.load(saved);

        // The first thinker after the cap should be the loaded mobj.
        Thinker first = PTick.thinkercap.next;
        assertNotNull(first);
        assertTrue(first instanceof Mobj, "expected Mobj, was " + first);
        Mobj loaded = (Mobj) first;
        assertEquals(orig.x, loaded.x);
        assertEquals(orig.y, loaded.y);
        assertEquals(Info.MT_POSSESSED, loaded.type);
        assertEquals(17, loaded.health);
        assertEquals(4, loaded.tics);
        assertEquals(9, loaded.movecount);
        // info / floorz / ceilingz / subsector were re-resolved.
        assertSame(Info.mobjinfo[Info.MT_POSSESSED], loaded.info);
        assertEquals(sec.floorheight, loaded.floorz);
        assertEquals(sec.ceilingheight, loaded.ceilingz);
        assertNotNull(loaded.subsector);
        // function rebound.
        assertNotNull(loaded.function.acp1);
    }

    // --- Active ceiling thinker (paused-state preserved) ---

    @Test
    void ceilingThinkerSaveLoadRoundtrips() throws IOException {
        PCeilng.Ceiling c = new PCeilng.Ceiling();
        c.type = 1;
        c.sector = sec;
        c.bottomheight = 0;
        c.topheight = 100 * Fixed.FRACUNIT;
        c.speed = Fixed.FRACUNIT;
        c.crush = false;
        c.direction = 1;
        c.tag = 5;
        c.olddirection = -1;
        c.function.acp1 = PCeilng::tMoveCeiling;
        PTick.pAddThinker(c);
        // Insert into the active table so the C-style "stasis"
        // detection has a slot to find it in.
        PCeilng.activeceilings[0] = c;

        byte[] saved = PSaveg.save();

        // Wipe.
        PTick.pInitThinkers();
        for (int i = 0; i < PCeilng.MAXCEILINGS; i++) {
            PCeilng.activeceilings[i] = null;
        }

        PSaveg.load(saved);

        Thinker first = PTick.thinkercap.next;
        assertTrue(first instanceof PCeilng.Ceiling);
        PCeilng.Ceiling loaded = (PCeilng.Ceiling) first;
        assertEquals(1, loaded.type);
        assertSame(sec, loaded.sector);
        assertEquals(100 * Fixed.FRACUNIT, loaded.topheight);
        assertEquals(5, loaded.tag);
        assertEquals(-1, loaded.olddirection);
        // sector.specialdata back-link.
        assertSame(loaded, sec.specialdata);
        // active-ceiling table re-populated.
        assertSame(loaded, PCeilng.activeceilings[0]);
    }

    // --- Empty world: round-trip preserves emptiness ---

    @Test
    void emptyWorldSaveLoadIsByteIdentical() throws IOException {
        byte[] a = PSaveg.save();
        byte[] b = PSaveg.save();
        assertArrayEquals(a, b);

        PSaveg.load(a);
        byte[] c = PSaveg.save();
        assertArrayEquals(a, c);
    }
}
