package doom.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.system.Doomdef;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RThingsTest {

    @BeforeEach
    void resetRenderer() {
        // R_Init scaffolding for projection / centerxfrac / scalelight.
        RMain.rSetViewSize(11, 0);
        RMain.rExecuteSetViewSize();
        RMain.rInitLightTables();

        RMain.viewx = 0;
        RMain.viewy = 0;
        RMain.viewz = 41 * Fixed.FRACUNIT;
        RMain.viewangle = 0;
        RMain.fixedcolormap = -1;
        RMain.extralight = 0;

        RBsp.rClearDrawSegs();
        RBsp.rClearClipSegs();

        RThings.rClearSprites();
    }

    // ---- R_NewVisSprite ----

    @Test
    void newVisSpritePoolBumps() {
        Vissprite a = RThings.rNewVisSprite();
        Vissprite b = RThings.rNewVisSprite();
        assertNotNull(a);
        assertNotNull(b);
        // Distinct entries from the pool.
        assertTrue(a != b);
        assertEquals(2, RThings.vsP);
    }

    // Pool overflow returns the sentinel without bumping vsP past
    // MAXVISSPRITES.
    @Test
    void newVisSpritePoolOverflow() {
        for (int i = 0; i < RThings.MAXVISSPRITES; i++) {
            RThings.rNewVisSprite();
        }
        Vissprite over = RThings.rNewVisSprite();
        assertNotNull(over);
        // Same sentinel returned for further requests.
        assertSame(over, RThings.rNewVisSprite());
        assertEquals(RThings.MAXVISSPRITES, RThings.vsP);
    }

    // ---- R_ClearSprites ----

    @Test
    void clearSpritesResetsPool() {
        RThings.rNewVisSprite();
        RThings.rNewVisSprite();
        assertEquals(2, RThings.vsP);

        RThings.rClearSprites();
        assertEquals(0, RThings.vsP);
    }

    // ---- R_SortVisSprites: scales sorted ascending ----

    @Test
    void sortVisSpritesAscendingByScale() {
        // Three sprites: scales 100, 50, 200 → sorted 50/100/200.
        Vissprite a = RThings.rNewVisSprite(); a.scale = 100;
        Vissprite b = RThings.rNewVisSprite(); b.scale = 50;
        Vissprite c = RThings.rNewVisSprite(); c.scale = 200;

        RThings.rSortVisSprites();

        // The vsprsortedhead is package-private; instead walk the
        // sorted list by indexing the pool and verifying the scale
        // ordering through the next pointers.
        // We expect: head -> b -> a -> c -> head.
        // After sorting, b.next should be a, a.next should be c.
        assertEquals(b.scale, 50);
        assertEquals(a.scale, 100);
        assertEquals(c.scale, 200);
        // Walk from b: b → a → c.
        assertSame(a, b.next);
        assertSame(c, a.next);
    }

    // Empty list: rSortVisSprites returns cleanly.
    @Test
    void sortVisSpritesEmptyDoesNotCrash() {
        RThings.rClearSprites();
        RThings.rSortVisSprites();
        assertEquals(0, RThings.vsP);
    }

    // ---- Constants ----

    @Test
    void constantsMatchCSource() {
        assertEquals(Fixed.FRACUNIT * 4, RThings.MINZ);
        assertEquals(100, RThings.BASEYCENTER);
        assertEquals(128, RThings.MAXVISSPRITES);
        assertEquals(Doomdef.RENDER_WIDTH, RThings.negonearray.length);
        // negonearray seeded with -1.
        assertEquals(-1, RThings.negonearray[0]);
        assertEquals(-1, RThings.negonearray[Doomdef.RENDER_WIDTH - 1]);
    }

    // ---- R_AddSprites: skip already-visited sectors ----

    @Test
    void addSpritesSkipsVisitedSector() {
        Sector sec = new Sector();
        sec.lightlevel = 192;
        sec.thinglist = null;
        sec.validcount = RMain.validcount; // already visited

        RThings.rAddSprites(sec);
        // No vissprites allocated.
        assertEquals(0, RThings.vsP);
    }

    // ---- R_InitSpriteDefs minimal smoke ----

    // Empty namelist → numsprites = 0, sprites null.
    @Test
    void initSpriteDefsEmptyNamelist() {
        RThings.numsprites = 0;
        RThings.sprites = null;
        RThings.rInitSpriteDefs(new String[0]);
        assertEquals(0, RThings.numsprites);
        assertNull(RThings.sprites);
    }

    // ---- R_DrawMasked: orchestrator runs cleanly with no sprites
    //                   and no maskedtexturecol drawsegs ----

    @Test
    void drawMaskedNoOpWithEmptyState() {
        RMain.viewangleoffset = 1; // skip rDrawPlayerSprites branch
        RThings.rClearSprites();
        // No draws happen — just verify no exception.
        RThings.rDrawMasked();
    }
}
