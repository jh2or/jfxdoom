package doom.play;

import doom.fixed.Fixed;
import doom.render.RData;
import doom.render.RMain;
import doom.render3d.Fx3SubsectorPolys;
import doom.system.*;
import doom.testing.TestWads;
import doom.wad.WWad;
import doom.wad.ZZone;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DynamicLevelTest {
    @TempDir Path temp;

    @BeforeEach void reset() {
        TestWads.require(TestWads.find());
        WWad.wShutdown();
        RData.resetForTests();
        DMain.numwadfiles = 0;
        DMain.advancedemo = false;
        Doomstat.dynamicLevels = Doomstat.demoplayback = Doomstat.netgame = false;
        Doomstat.deathmatch = 0;
        GGame.demorecording = false;
        PTick.gametic = 0;
        doom.ui.MMenu.menuactive = false;
        doom.ui.AmMap.automapactive = false;
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            GGame.players[i] = new Player();
            GGame.playeringame[i] = false;
        }
    }

    @AfterEach void close() {
        Doomstat.dynamicLevels = Doomstat.demoplayback = false;
        Doomstat.nomonsters = false;
        GGame.gameaction = GGame.ga_nothing;
        doom.ui.StStuff.stStopped = true;
        doom.ui.StStuff.sbar = null;
        doom.sound.ISound.iShutdownSound();
        doom.sound.ISound.iShutdownMusic();
        WWad.wShutdown();
    }

    private void boot(String... flags) throws Exception {
        List<String> args = new ArrayList<>(List.of("-iwad", TestWads.find().toString(),
                "-config", temp.resolve("test.cfg").toString()));
        args.addAll(List.of(flags));
        DMain.dDoomMain(args.toArray(String[]::new));
    }

    @Test void bootsGeneratedGeometryWithWadAssetsAndLiveMonsters() throws Exception {
        boot("-dynamic", "-skill", "2");
        assertTrue(DMain.autostart);
        assertTrue(Doomstat.dynamicLevels);
        assertEquals(Doomstat.GS_LEVEL, Doomstat.gamestate);
        assertEquals(Doomstat.sk_easy, Doomstat.gameskill);
        assertEquals(1, PSetup.numsectors);
        assertEquals(4, PSetup.numlines);
        assertEquals(0, PSetup.numnodes);
        assertEquals(1, PSetup.numsubsectors);
        assertEquals(6, Doomstat.totalkills);
        assertEquals(Fixed.fromInt(-512), GGame.players[0].mo.x);
        assertEquals(100, GGame.players[0].health);
        assertTrue(GGame.players[0].weaponowned[Doomdef.wp_pistol]);
        assertTrue(PSetup.sides[0].midtexture > 0);
        assertEquals(4096, WWad.wLumpLength(RData.firstflat + PSetup.sectors[0].floorpic));
        List<Mobj> monsters = new ArrayList<>();
        for (Thinker t = PTick.thinkercap.next; t != PTick.thinkercap; t = t.next) {
            if (t instanceof Mobj mo && (mo.flags & Mobj.MF_COUNTKILL) != 0) {
                monsters.add(mo);
                assertTrue(PSight.pCheckSight(mo, GGame.players[0].mo));
            }
        }
        assertEquals(6, monsters.size());
        int oldX = monsters.getFirst().x;
        for (int i = 0; i < 35; i++) GGame.gTicker();
        assertTrue(monsters.stream().anyMatch(m -> m.target == GGame.players[0].mo));
        assertNotEquals(oldX, monsters.getFirst().x, "Monsters must advance toward the player");
        assertDoesNotThrow(() -> RMain.rRenderPlayerView(GGame.players[0]));
        double[][] polygons = Fx3SubsectorPolys.buildAll();
        assertEquals(1, polygons.length);
        assertEquals(8, polygons[0].length, "3D renderer receives a closed four-corner floor");
    }

    @Test void movementPickupsAndAllFourWallsWorkWithoutMonsters() throws Exception {
        boot("-dynamic", "-nomonsters");
        assertEquals(0, Doomstat.totalkills);
        Player player = GGame.players[0];
        Mobj mo = player.mo;
        for (int x = -504; x <= -384; x += 8)
            assertTrue(PMap.pTryMove(mo, Fixed.fromInt(x), 0));
        assertTrue(player.weaponowned[Doomdef.wp_shotgun]);
        for (int[] direction : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            assertTrue(PMap.pTryMove(mo, 0, 0));
            for (int distance = 8; distance <= 744; distance += 8)
                assertTrue(PMap.pTryMove(mo, Fixed.fromInt(distance * direction[0]),
                        Fixed.fromInt(distance * direction[1])));
            assertFalse(PMap.pTryMove(mo, Fixed.fromInt(760 * direction[0]),
                    Fixed.fromInt(760 * direction[1])), "Player radius must collide with every wall");
        }
    }

    @Test void reloadAndSaveReconstructArenaAndRejectStockMapSaves() throws Exception {
        boot("-dynamic", "-nomonsters");
        byte[] saved = SaveGame.save();
        int count = WWad.numlumps;
        Object oldSector = PSetup.sectors[0];
        GGame.gInitNew(Doomstat.sk_medium, 1, 2);
        GGame.gDoLoadLevel();
        assertNotSame(oldSector, PSetup.sectors[0]);
        assertEquals(count, WWad.numlumps, "Reload must not append duplicate lumps");
        SaveGame.load(saved);
        assertArrayEquals(saved, SaveGame.save());
        Doomstat.dynamicLevels = false;
        assertThrows(java.io.IOException.class, () -> SaveGame.load(saved));
        GGame.gInitNew(Doomstat.sk_medium, 1, 1);
        GGame.gDoLoadLevel();
        assertTrue(PSetup.numlines > 4);
        byte[] stock = SaveGame.save();
        Doomstat.dynamicLevels = true;
        assertThrows(java.io.IOException.class, () -> SaveGame.load(stock));
    }

    @Test void ordinaryLaunchResetsModeAndDoesNotMountGeneratedMap() throws Exception {
        Doomstat.dynamicLevels = true;
        boot("-warp", "1", "1");
        assertFalse(Doomstat.dynamicLevels);
        assertEquals(-1, WWad.wCheckNumForName(DynamicLevel.MAP_NAME));
        assertTrue(PSetup.numlines > 4);
    }

    @Test void titleDemosStillLoadTheirOriginalMaps() throws Exception {
        boot("-dynamic");
        GGame.defdemoname = "demo1";
        GGame.gDoPlayDemo();
        GGame.gDoLoadLevel();
        assertTrue(Doomstat.demoplayback);
        assertTrue(PSetup.numlines > 4);
    }

    @Test void explicitDemoOptionsAreRejected() {
        for (String flag : new String[] {"-record", "-playdemo", "-timedemo"}) {
            assertThrows(ISystem.DoomError.class,
                    () -> DMain.dDoomMain(new String[] {"-dynamic", flag, "demo1"}));
        }
    }
}
