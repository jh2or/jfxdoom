package doom.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.play.Mobj;
import doom.play.PSetup;
import doom.play.Player;
import doom.render.Line;
import doom.render.Sector;
import doom.render.Vertex;
import doom.system.DEvent;
import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.GGame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AmMapTest {

    private static Vertex vertex(int x, int y) {
        Vertex v = new Vertex();
        v.x = x;
        v.y = y;
        return v;
    }

    @BeforeEach
    void resetState() {
        AmMap.automapactive = false;
        AmMap.stopped = true;
        AmMap.cheating = 0;
        AmMap.grid = 0;
        AmMap.followplayer = 1;
        AmMap.markpointnum = 0;
        for (int i = 0; i < AmMap.AM_NUMMARKPOINTS; i++) {
            AmMap.markpoints[i].x = -1;
            AmMap.markpoints[i].y = -1;
        }
        AmMap.m_paninc.x = 0;
        AmMap.m_paninc.y = 0;
        AmMap.f_x = 0;
        AmMap.f_y = 0;
        AmMap.f_w = 320;
        AmMap.f_h = 168;
        AmMap.scale_mtof = AmMap.INITSCALEMTOF;
        AmMap.scale_ftom = Fixed.div(Fixed.FRACUNIT, AmMap.scale_mtof);
        AmMap.mtof_zoommul = Fixed.FRACUNIT;
        AmMap.ftom_zoommul = Fixed.FRACUNIT;
        AmMap.fb = new byte[AmMap.f_w * AmMap.f_h];
        AmMap.amclock = 0;
        AmMap.lightlev = 0;
        AmMap.m_x = AmMap.m_y = 0;
        AmMap.m_w = AmMap.ftom(AmMap.f_w);
        AmMap.m_h = AmMap.ftom(AmMap.f_h);
        AmMap.m_x2 = AmMap.m_x + AmMap.m_w;
        AmMap.m_y2 = AmMap.m_y + AmMap.m_h;
        AmMap.min_x = -1024 * Fixed.FRACUNIT;
        AmMap.min_y = -1024 * Fixed.FRACUNIT;
        AmMap.max_x =  1024 * Fixed.FRACUNIT;
        AmMap.max_y =  1024 * Fixed.FRACUNIT;
        AmMap.min_scale_mtof = AmMap.INITSCALEMTOF;
        AmMap.max_scale_mtof = AmMap.INITSCALEMTOF * 16;

        Player p = new Player();
        p.mo = new Mobj();
        p.mo.x = 0;
        p.mo.y = 0;
        p.mo.angle = 0;
        GGame.players[0] = p;
        GGame.consoleplayer = 0;
        GGame.playeringame[0] = true;
        for (int i = 1; i < Doomdef.MAXPLAYERS; i++) {
            GGame.playeringame[i] = false;
            GGame.players[i] = new Player();
        }
        Doomstat.netgame = false;
        Doomstat.deathmatch = 0;
        Doomstat.gamemap = 1;
        Doomstat.gameepisode = 1;
        Doomstat.viewactive = true;

        AmMap.plr = p;
    }

    // ---- coordinate helpers ----

    // mtof + ftom should be approximately inverses. The `mtof`
    // half right-shifts by FRACBITS, so the round-trip loses at
    // most one mtof unit's worth of map-coords (= scale_ftom).
    @Test
    void mtofFtomRoundTripsApproximately() {
        int input = 500 * Fixed.FRACUNIT;
        int roundtrip = AmMap.ftom(AmMap.mtof(input));
        // One mtof-unit of slack in map-coords.
        assertTrue(Math.abs(roundtrip - input) <= AmMap.scale_ftom,
                "diff=" + (roundtrip - input) + " tol=" + AmMap.scale_ftom);
    }

    // ---- amAddMark / amClearMarks ----

    // Adding marks fills the slot at current centre and bumps the index.
    @Test
    void addMarkRecordsCenterAndIncrementsIndex() {
        AmMap.m_x = -10 * Fixed.FRACUNIT;
        AmMap.m_y = -20 * Fixed.FRACUNIT;
        AmMap.m_w = 200 * Fixed.FRACUNIT;
        AmMap.m_h = 100 * Fixed.FRACUNIT;
        AmMap.amAddMark();
        assertEquals(1, AmMap.markpointnum);
        // Centre = m_x + m_w/2 = -10 + 100 = 90; m_y + m_h/2 = -20 + 50 = 30.
        assertEquals(90 * Fixed.FRACUNIT, AmMap.markpoints[0].x);
        assertEquals(30 * Fixed.FRACUNIT, AmMap.markpoints[0].y);
    }

    // Mark index wraps at AM_NUMMARKPOINTS.
    @Test
    void addMarkWrapsAtMaxPoints() {
        AmMap.markpointnum = AmMap.AM_NUMMARKPOINTS - 1;
        AmMap.amAddMark();
        assertEquals(0, AmMap.markpointnum);
    }

    // Clear resets all slot.x to -1 (the "empty" sentinel).
    @Test
    void clearMarksResetsAllSlots() {
        AmMap.markpoints[0].x = 5;
        AmMap.markpoints[1].x = 7;
        AmMap.markpointnum = 3;
        AmMap.amClearMarks();
        for (int i = 0; i < AmMap.AM_NUMMARKPOINTS; i++) {
            assertEquals(-1, AmMap.markpoints[i].x);
        }
        assertEquals(0, AmMap.markpointnum);
    }

    // ---- amFindMinMaxBoundaries ----

    // World-AABB derived from PSetup.vertexes drives min/max scale.
    @Test
    void findMinMaxBoundariesReadsVertexes() {
        PSetup.vertexes = new Vertex[] {
                vertex(-100 * Fixed.FRACUNIT, -50 * Fixed.FRACUNIT),
                vertex( 200 * Fixed.FRACUNIT,  300 * Fixed.FRACUNIT),
                vertex(  50 * Fixed.FRACUNIT,  100 * Fixed.FRACUNIT),
        };
        PSetup.numvertexes = 3;
        AmMap.amFindMinMaxBoundaries();
        assertEquals(-100 * Fixed.FRACUNIT, AmMap.min_x);
        assertEquals( 200 * Fixed.FRACUNIT, AmMap.max_x);
        assertEquals( -50 * Fixed.FRACUNIT, AmMap.min_y);
        assertEquals( 300 * Fixed.FRACUNIT, AmMap.max_y);
        assertEquals(2 * AmMap.PLAYERRADIUS, AmMap.min_w);
    }

    // ---- amResponder ----

    // TAB from idle starts the automap.
    @Test
    void responderTabStartsAutomap() {
        PSetup.vertexes = new Vertex[] {
                vertex(-100 * Fixed.FRACUNIT, -100 * Fixed.FRACUNIT),
                vertex( 100 * Fixed.FRACUNIT,  100 * Fixed.FRACUNIT),
        };
        PSetup.numvertexes = 2;
        DEvent ev = new DEvent();
        ev.type  = DEvent.EV_KEYDOWN;
        ev.data1 = AmMap.AM_STARTKEY;
        assertFalse(AmMap.automapactive);
        boolean ate = AmMap.amResponder(ev);
        assertTrue(ate);
        assertTrue(AmMap.automapactive);
        assertFalse(Doomstat.viewactive);
    }

    // TAB while active stops the automap.
    @Test
    void responderTabStopsAutomap() {
        AmMap.automapactive = true;
        AmMap.stopped = false;
        DEvent ev = new DEvent();
        ev.type  = DEvent.EV_KEYDOWN;
        ev.data1 = AmMap.AM_ENDKEY;
        AmMap.amResponder(ev);
        assertFalse(AmMap.automapactive);
        assertTrue(Doomstat.viewactive);
    }

    // F toggles follow-mode and lights player.message.
    @Test
    void responderFToggleFollow() {
        AmMap.automapactive = true;
        AmMap.followplayer = 1;
        DEvent ev = new DEvent();
        ev.type  = DEvent.EV_KEYDOWN;
        ev.data1 = AmMap.AM_FOLLOWKEY;
        AmMap.amResponder(ev);
        assertEquals(0, AmMap.followplayer);
        assertEquals("Follow Mode OFF", AmMap.plr.message);
    }

    // G toggles the grid + sets the message.
    @Test
    void responderGToggleGrid() {
        AmMap.automapactive = true;
        AmMap.grid = 0;
        DEvent ev = new DEvent();
        ev.type  = DEvent.EV_KEYDOWN;
        ev.data1 = AmMap.AM_GRIDKEY;
        AmMap.amResponder(ev);
        assertEquals(1, AmMap.grid);
        assertEquals("Grid ON", AmMap.plr.message);
    }

    // M drops a mark + lights the message.
    @Test
    void responderMAddsMark() {
        AmMap.automapactive = true;
        AmMap.markpointnum = 0;
        DEvent ev = new DEvent();
        ev.type  = DEvent.EV_KEYDOWN;
        ev.data1 = AmMap.AM_MARKKEY;
        AmMap.amResponder(ev);
        assertEquals(1, AmMap.markpointnum);
        assertTrue(AmMap.plr.message.startsWith("Marked Spot"));
    }

    // C clears marks + lights the message.
    @Test
    void responderCClearsMarks() {
        AmMap.automapactive = true;
        AmMap.markpoints[0].x = 100;
        AmMap.markpointnum = 1;
        DEvent ev = new DEvent();
        ev.type  = DEvent.EV_KEYDOWN;
        ev.data1 = AmMap.AM_CLEARMARKKEY;
        AmMap.amResponder(ev);
        assertEquals(0, AmMap.markpointnum);
        assertEquals(-1, AmMap.markpoints[0].x);
        assertEquals("All Marks Cleared", AmMap.plr.message);
    }

    // Right arrow with follow-off sets m_paninc.x to +FTOM(F_PANINC).
    @Test
    void responderRightArrowPansWhenFollowOff() {
        AmMap.automapactive = true;
        AmMap.followplayer = 0;
        AmMap.m_paninc.x = 0;
        DEvent ev = new DEvent();
        ev.type  = DEvent.EV_KEYDOWN;
        ev.data1 = AmMap.AM_PANRIGHTKEY;
        AmMap.amResponder(ev);
        assertNotEquals(0, AmMap.m_paninc.x);
        assertTrue(AmMap.m_paninc.x > 0);
    }

    // Releasing arrow zeros m_paninc.x.
    @Test
    void responderArrowReleaseZerosPan() {
        AmMap.automapactive = true;
        AmMap.followplayer = 0;
        AmMap.m_paninc.x = AmMap.ftom(AmMap.F_PANINC);
        DEvent ev = new DEvent();
        ev.type  = DEvent.EV_KEYUP;
        ev.data1 = AmMap.AM_PANRIGHTKEY;
        AmMap.amResponder(ev);
        assertEquals(0, AmMap.m_paninc.x);
    }

    // = key zooms in (mtof_zoommul = M_ZOOMIN).
    @Test
    void responderEqualsZoomsIn() {
        AmMap.automapactive = true;
        AmMap.mtof_zoommul = Fixed.FRACUNIT;
        AmMap.ftom_zoommul = Fixed.FRACUNIT;
        DEvent ev = new DEvent();
        ev.type  = DEvent.EV_KEYDOWN;
        ev.data1 = AmMap.AM_ZOOMINKEY;
        AmMap.amResponder(ev);
        assertEquals(AmMap.M_ZOOMIN, AmMap.mtof_zoommul);
        assertEquals(AmMap.M_ZOOMOUT, AmMap.ftom_zoommul);
    }

    // Zoom key release returns to neutral.
    @Test
    void responderZoomReleaseReturnsToNeutral() {
        AmMap.automapactive = true;
        AmMap.mtof_zoommul = AmMap.M_ZOOMIN;
        AmMap.ftom_zoommul = AmMap.M_ZOOMOUT;
        DEvent ev = new DEvent();
        ev.type  = DEvent.EV_KEYUP;
        ev.data1 = AmMap.AM_ZOOMINKEY;
        AmMap.amResponder(ev);
        assertEquals(Fixed.FRACUNIT, AmMap.mtof_zoommul);
        assertEquals(Fixed.FRACUNIT, AmMap.ftom_zoommul);
    }

    // ---- amTicker ----

    // Ticker bails when inactive (no clock bump).
    @Test
    void tickerNoOpWhenInactive() {
        AmMap.automapactive = false;
        AmMap.amclock = 0;
        AmMap.amTicker();
        assertEquals(0, AmMap.amclock);
    }

    // Ticker bumps clock while active.
    @Test
    void tickerBumpsClockWhenActive() {
        AmMap.automapactive = true;
        AmMap.amclock = 5;
        AmMap.amTicker();
        assertEquals(6, AmMap.amclock);
    }

    // doFollowPlayer locks the map onto the player's mobj position
    // (quantized to the pixel-snap grid).
    @Test
    void doFollowPlayerTracksMobj() {
        AmMap.plr.mo.x = 256 * Fixed.FRACUNIT;
        AmMap.plr.mo.y = 128 * Fixed.FRACUNIT;
        AmMap.f_oldloc.x = -1;
        AmMap.f_oldloc.y = -1;
        AmMap.amDoFollowPlayer();
        // f_oldloc snaps to mobj coords.
        assertEquals(AmMap.plr.mo.x, AmMap.f_oldloc.x);
        assertEquals(AmMap.plr.mo.y, AmMap.f_oldloc.y);
    }

    // ---- amClipMline ----

    // Wholly outside lines reject trivially.
    @Test
    void clipMlineTriviallyRejectsLineAboveMap() {
        AmMap.m_x = 0;
        AmMap.m_y = 0;
        AmMap.m_x2 = 100 * Fixed.FRACUNIT;
        AmMap.m_y2 = 100 * Fixed.FRACUNIT;
        AmMap.Mline ml = new AmMap.Mline();
        ml.a.x = 50 * Fixed.FRACUNIT;
        ml.a.y = 200 * Fixed.FRACUNIT;
        ml.b.x = 60 * Fixed.FRACUNIT;
        ml.b.y = 300 * Fixed.FRACUNIT;
        AmMap.Fline fl = new AmMap.Fline();
        assertFalse(AmMap.amClipMline(ml, fl));
    }

    // Wholly inside lines clip to themselves.
    @Test
    void clipMlineKeepsInsideLines() {
        AmMap.m_x = -200 * Fixed.FRACUNIT;
        AmMap.m_y = -200 * Fixed.FRACUNIT;
        AmMap.m_x2 = 200 * Fixed.FRACUNIT;
        AmMap.m_y2 = 200 * Fixed.FRACUNIT;
        AmMap.m_w = AmMap.m_x2 - AmMap.m_x;
        AmMap.m_h = AmMap.m_y2 - AmMap.m_y;
        AmMap.Mline ml = new AmMap.Mline();
        ml.a.x = -50 * Fixed.FRACUNIT;
        ml.a.y = -50 * Fixed.FRACUNIT;
        ml.b.x = 50 * Fixed.FRACUNIT;
        ml.b.y = 50 * Fixed.FRACUNIT;
        AmMap.Fline fl = new AmMap.Fline();
        assertTrue(AmMap.amClipMline(ml, fl));
        // Frame-buffer coordinates are inside the window.
        assertTrue(fl.a.x >= 0 && fl.a.x < AmMap.f_w);
        assertTrue(fl.a.y >= 0 && fl.a.y < AmMap.f_h);
    }

    // ---- amRotate ----

    // Rotating (1, 0) by 90 degrees yields ~(0, 1).
    @Test
    void rotateNinetyDegrees() {
        int[] xy = { Fixed.FRACUNIT, 0 };
        AmMap.amRotate(xy, doom.render.Tables.ANG90);
        // x ≈ 0, y ≈ FRACUNIT
        assertTrue(Math.abs(xy[0]) < (1 << 10));
        assertTrue(Math.abs(xy[1] - Fixed.FRACUNIT) < (1 << 10));
    }

    // ---- amClearFB ----

    @Test
    void clearFbFillsFrameBufferWithColor() {
        AmMap.f_w = 320;
        AmMap.f_h = 10;
        AmMap.fb = new byte[AmMap.f_w * AmMap.f_h];
        AmMap.amClearFB(42);
        for (int i = 0; i < AmMap.fb.length; i++) {
            assertEquals(42, AmMap.fb[i] & 0xFF);
        }
    }

    // ---- amDrawWalls picks wall colour based on flags + sector deltas ----

    // Solid wall (no backsector) → WALLCOLORS.
    @Test
    void drawWallsRendersSolidWallsInRed() {
        Sector front = new Sector();
        front.floorheight = 0;
        front.ceilingheight = 128 * Fixed.FRACUNIT;
        Line ln = new Line();
        ln.v1 = vertex(-50 * Fixed.FRACUNIT, -50 * Fixed.FRACUNIT);
        ln.v2 = vertex( 50 * Fixed.FRACUNIT,  50 * Fixed.FRACUNIT);
        ln.flags = Line.ML_MAPPED;
        ln.special = 0;
        ln.frontsector = front;
        ln.backsector = null;

        PSetup.lines = new Line[] { ln };
        PSetup.numlines = 1;

        AmMap.m_x = -200 * Fixed.FRACUNIT;
        AmMap.m_y = -200 * Fixed.FRACUNIT;
        AmMap.m_x2 = 200 * Fixed.FRACUNIT;
        AmMap.m_y2 = 200 * Fixed.FRACUNIT;
        AmMap.m_w = AmMap.m_x2 - AmMap.m_x;
        AmMap.m_h = AmMap.m_y2 - AmMap.m_y;
        AmMap.lightlev = 0;
        AmMap.cheating = 0;
        AmMap.fb = new byte[AmMap.f_w * AmMap.f_h];
        AmMap.amDrawWalls();
        // The line should have left some pixels painted in WALLCOLORS.
        int found = 0;
        for (int i = 0; i < AmMap.fb.length; i++) {
            if ((AmMap.fb[i] & 0xFF) == AmMap.WALLCOLORS) { found++; }
        }
        assertTrue(found > 0, "expected red wall pixels");
    }
}
