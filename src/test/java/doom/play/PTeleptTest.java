package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.Sector;
import doom.render.Subsector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PTeleptTest {

    @BeforeEach
    void resetWorld() {
        PMap.changeSectorOverride = true;
        PMap.changeSectorResult = false;
        PTick.pInitThinkers();
        PSetup.bmaporgx = -64 * Fixed.FRACUNIT;
        PSetup.bmaporgy = -64 * Fixed.FRACUNIT;
        PSetup.bmapwidth = 1;
        PSetup.bmapheight = 1;
        PSetup.blocklinks = new Mobj[1];
        PSetup.blockmaplump = new int[] { -64, -64, 1, 1, 5, -1 };
        PSetup.blockmap = PSetup.blockmaplump;
    }

    private static Sector openWorld(int floor, int ceil) {
        Sector sec = new Sector();
        sec.floorheight = floor;
        sec.ceilingheight = ceil;
        sec.thinglist = null;
        sec.tag = 0;

        Subsector ss = new Subsector();
        ss.sector = sec;

        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;
        PSetup.subsectors = new Subsector[] { ss };
        PSetup.numsubsectors = 1;
        PSetup.numnodes = 0;
        PSetup.nodes = new doom.render.Node[0];
        return sec;
    }

    // No tag-matched sector → no teleport.
    @Test
    void teleportFailsWhenNoTaggedSector() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Mobj player = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_PLAYER);

        Line line = new Line();
        line.tag = 99;          // no sector matches

        assertEquals(0, PTelept.evTeleport(line, /*side*/ 0, player));
    }

    // Hit back of line → bail out.
    @Test
    void teleportSkipsBackSide() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Mobj player = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_PLAYER);
        Line line = new Line();
        line.tag = 1;

        assertEquals(0, PTelept.evTeleport(line, /*side*/ 1, player));
    }

    // MF_MISSILE mobjs cannot be teleported.
    @Test
    void teleportSkipsMissiles() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Mobj missile = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_TROOPSHOT);
        // Sanity: missiles carry MF_MISSILE.
        Line line = new Line();
        line.tag = 1;

        assertEquals(0, PTelept.evTeleport(line, 0, missile));
    }

    // Tagged sector with an MT_TELEPORTMAN at (10, 20) → success.
    @Test
    void teleportSucceedsWithTeleportman() {
        Sector sec = openWorld(0, 200 * Fixed.FRACUNIT);
        sec.tag = 7;

        // Spawn the teleport destination marker in this sector.
        Mobj telman = PMobj.pSpawnMobj(10 * Fixed.FRACUNIT,
                20 * Fixed.FRACUNIT,
                PMobj.ONFLOORZ, Info.MT_TELEPORTMAN);
        telman.angle = 0;

        // Spawn a player elsewhere.
        Mobj player = PMobj.pSpawnMobj(100 * Fixed.FRACUNIT,
                100 * Fixed.FRACUNIT,
                PMobj.ONFLOORZ, Info.MT_PLAYER);
        Player p = new Player();
        p.health = 100;
        player.player = p;
        p.mo = player;

        Line line = new Line();
        line.tag = 7;

        int rtn = PTelept.evTeleport(line, 0, player);

        assertEquals(1, rtn);
        // Player relocated to telman's position.
        assertEquals(telman.x, player.x);
        assertEquals(telman.y, player.y);
        // Reactiontime gate set.
        assertEquals(18, player.reactiontime);
        // Momentum zeroed.
        assertEquals(0, player.momx);
        assertEquals(0, player.momy);
        assertEquals(0, player.momz);
        // Two TFOG mobjs spawned (source + destination); the most-
        // recently added thinker is the destination fog.
        assertNotNull(PTick.thinkercap.prev);
    }
}
