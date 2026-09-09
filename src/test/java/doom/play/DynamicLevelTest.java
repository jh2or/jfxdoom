package doom.play;

import doom.fixed.Fixed;
import doom.render.RData;
import doom.render.RMain;
import doom.render.Line;
import doom.render3d.Fx3SubsectorPolys;
import doom.system.*;
import doom.testing.TestWads;
import doom.wad.WWad;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    @Test void bootsSeededMapWithSkillAppropriateMonstersAndRendering() throws Exception {
        boot("-dynamic", "-seed", "12345", "-skill", "2");
        assertTrue(DMain.autostart);
        assertTrue(Doomstat.dynamicLevels);
        assertEquals(12345, Doomstat.dynamicSeed);
        assertEquals(Doomstat.GS_LEVEL, Doomstat.gamestate);
        assertEquals(Doomstat.sk_easy, Doomstat.gameskill);
        assertTrue(PSetup.numsectors >= 19);
        assertEquals(PSetup.numsubsectors - 1, PSetup.numnodes);
        assertEquals(16, Doomstat.totalkills);
        assertEquals(100, GGame.players[0].health);
        assertTrue(GGame.players[0].weaponowned[Doomdef.wp_pistol]);
        DynamicLevel.Layout plan = DynamicLevel.plan(12345);
        // Wake a room encounter and prove sight/AI work across the generated BSP.
        DynamicLevel.Room room = plan.rooms().get(1);
        teleport(room.worldX(), room.worldY());
        Mobj monster = null;
        for (Thinker t = PTick.thinkercap.next; t != PTick.thinkercap; t = t.next) {
            if (t instanceof Mobj mo && (mo.flags & Mobj.MF_COUNTKILL) != 0
                    && mo.subsector.sector == PSetup.sectors[1]) { monster = mo; break; }
        }
        assertNotNull(monster);
        assertTrue(PSight.pCheckSight(monster, GGame.players[0].mo));
        monster.target = GGame.players[0].mo;
        PMobj.pSetMobjState(monster, monster.info.seestate);
        int oldX = monster.x, oldY = monster.y;
        for (int i = 0; i < 35; i++) GGame.gTicker();
        assertTrue(monster.x != oldX || monster.y != oldY);
        assertDoesNotThrow(() -> RMain.rRenderPlayerView(GGame.players[0]));
        GGame.gInitNew(Doomstat.sk_hard, 1, 1);
        GGame.gDoLoadLevel();
        assertEquals(32, Doomstat.totalkills);
    }

    @Test void playerCanAcquireKeyOpenDoorSaveMidOpeningAndReachExit() throws Exception {
        boot("-dynamic", "-seed", "42", "-nomonsters");
        DynamicLevel.Layout plan = DynamicLevel.plan(42);
        Player player = GGame.players[0];
        assertEquals(0, Doomstat.totalkills);
        Line door = Arrays.stream(PSetup.lines).filter(l -> l.special == 32).findFirst().orElseThrow();
        assertFalse(player.cards[Doomdef.it_bluecard]);
        PSwitch.pUseSpecialLine(player.mo, door, 0);
        assertNull(PSetup.sectors[plan.doorSector()].specialdata);
        assertEquals(0, PSetup.sectors[plan.doorSector()].ceilingheight);
        // Route through the starting weapon and then physically walk to the key.
        DynamicLevel.Room start = plan.rooms().get(0), key = plan.rooms().get(plan.keyRoom());
        walkTo(plan, start.x(), start.y(), false);
        assertTrue(player.weaponowned[Doomdef.wp_shotgun]);
        walkTo(plan, key.x(), key.y(), false);
        assertTrue(player.cards[Doomdef.it_bluecard]);
        boolean[][] reachable = DynamicLevel.reachable(plan, false);
        int ax = -1, ay = -1;
        for (int[] step : STEPS) {
            int x = plan.doorX() + step[0], y = plan.doorY() + step[1];
            if (reachable[y][x]) { ax = x; ay = y; break; }
        }
        assertTrue(ax >= 0);
        walkTo(plan, ax, ay, false);
        int dx = plan.doorX() - ax, dy = plan.doorY() - ay;
        walkWorld(ax * 128 + 64 + dx * 24, ay * 128 + 64 + dy * 24);
        player.mo.angle = dx > 0 ? 0 : dx < 0 ? 0x80000000 : dy > 0 ? 0x40000000 : 0xc0000000;
        PMap.pUseLines(player);
        assertNotNull(PSetup.sectors[plan.doorSector()].specialdata);
        for (int i = 0; i < 10; i++) GGame.gTicker();
        byte[] saved = SaveGame.save();
        int height = PSetup.sectors[plan.doorSector()].ceilingheight;
        int count = WWad.numlumps;
        DynamicLevel.restore(7, DynamicLevel.generate(7));
        GGame.gInitNew(Doomstat.sk_medium, 1, 1);
        GGame.gDoLoadLevel();
        SaveGame.load(saved);
        assertEquals(42, Doomstat.dynamicSeed);
        assertEquals(count, WWad.numlumps);
        assertEquals(height, PSetup.sectors[plan.doorSector()].ceilingheight);
        assertNotNull(PSetup.sectors[plan.doorSector()].specialdata);
        assertArrayEquals(saved, SaveGame.save());
        for (int i = 0; i < 80; i++) GGame.gTicker();
        DynamicLevel.Room exit = plan.rooms().get(DynamicLevel.EXIT);
        walkTo(plan, exit.x() + exit.rx(), exit.y(), true);
        player = GGame.players[0];
        walkWorld((exit.x() + exit.rx()) * 128 + 88, exit.worldY());
        player.mo.angle = 0;
        PMap.pUseLines(player);
        assertEquals(GGame.ga_completed, GGame.gameaction);
        GGame.gTicker();
        assertEquals(Doomstat.GS_INTERMISSION, Doomstat.gamestate);
        GGame.gameaction = GGame.ga_worlddone;
        GGame.gTicker();
        assertEquals(Doomstat.GS_DEMOSCREEN, Doomstat.gamestate);
        assertTrue(DMain.advancedemo);
    }

    @Test void bspCollisionAndBothRenderersAgreeAcrossSeeds() throws Exception {
        boot("-dynamic", "-seed", "0", "-nomonsters");
        for (long seed = 0; seed < 12; seed++) {
            DynamicLevel.Layout plan = DynamicLevel.plan(seed);
            DynamicLevel.restore(seed, DynamicLevel.generate(seed));
            GGame.gInitNew(Doomstat.sk_medium, 1, 1);
            GGame.gDoLoadLevel();
            for (int y = 0; y < DynamicLevel.SIZE; y++) for (int x = 0; x < DynamicLevel.SIZE; x++) {
                int sector = plan.cells()[y][x];
                if (sector < 0 || sector == plan.doorSector()) continue;
                int wx = x * 128 + 64, wy = y * 128 + 64;
                assertSame(PSetup.sectors[sector], RMain.rPointInSubsector(Fixed.fromInt(wx), Fixed.fromInt(wy)).sector);
                for (int[] step : STEPS) {
                    teleport(wx, wy);
                    int neighbor = plan.cells()[y + step[1]][x + step[0]];
                    assertEquals(neighbor >= 0 && neighbor != plan.doorSector(),
                            PMap.pTryMove(GGame.players[0].mo, Fixed.fromInt(wx + step[0] * 56),
                                    Fixed.fromInt(wy + step[1] * 56)), "Boundary collision, seed " + seed);
                }
            }
            double[][] polygons = Fx3SubsectorPolys.buildAll();
            for (double[] polygon : polygons) assertEquals(8, polygon.length);
            for (DynamicLevel.Room room : plan.rooms()) {
                teleport(room.worldX(), room.worldY());
                for (int direction = 0; direction < 4; direction++) {
                    GGame.players[0].mo.angle = direction << 30;
                    GGame.players[0].viewz = GGame.players[0].mo.z + Fixed.fromInt(41);
                    assertDoesNotThrow(() -> RMain.rRenderPlayerView(GGame.players[0]));
                }
            }
        }
    }

    @Test void seedReproducesAllMapBytesIndependentlyOfGameRandomState() throws Exception {
        boot("-dynamic", "-seed", "-9223372036854775808", "-nomonsters");
        assertEquals(Long.MIN_VALUE, Doomstat.dynamicSeed);
        byte[][] a = DynamicLevel.generate(Long.MIN_VALUE);
        for (int i = 0; i < 111; i++) { MRandom.pRandom(); MRandom.mRandom(); }
        int gameplay = MRandom.prndindex, menu = MRandom.rndindex;
        byte[][] b = DynamicLevel.generate(Long.MIN_VALUE);
        assertTrue(Arrays.deepEquals(a, b));
        assertFalse(Arrays.deepEquals(a, DynamicLevel.generate(Long.MAX_VALUE)));
        assertEquals(gameplay, MRandom.prndindex);
        assertEquals(menu, MRandom.rndindex);
        Object previous = PSetup.sectors[0];
        GGame.gInitNew(Doomstat.sk_medium, 1, 1);
        GGame.gDoLoadLevel();
        assertNotSame(previous, PSetup.sectors[0]);
        assertTrue(Arrays.deepEquals(a, DynamicLevel.generate(Doomstat.dynamicSeed)));
    }

    @Test void incompatibleSeededSaveLeavesSeedGeometryAndPlayersUntouched() throws Exception {
        boot("-dynamic", "-seed", "42", "-nomonsters");
        byte[] original = SaveGame.save();
        Object sector = PSetup.sectors[0], player = GGame.players[0].mo;
        // Dynamic metadata starts after the unchanged 49-byte common header.
        for (int offset : new int[] {49, 53, 61}) {
            byte[] invalid = original.clone();
            invalid[offset] ^= 1; // generator version, seed, or map digest
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(invalid, 0, invalid.length - 8);
            java.nio.ByteBuffer.wrap(invalid).putLong(invalid.length - 8, crc.getValue());
            assertThrows(java.io.IOException.class, () -> SaveGame.load(invalid));
            assertEquals(42, Doomstat.dynamicSeed);
            assertSame(sector, PSetup.sectors[0]);
            assertSame(player, GGame.players[0].mo);
            assertArrayEquals(original, SaveGame.save());
        }
    }

    @Test void saveModesRemainIsolatedAndNormalLaunchDoesNotMountGeneratedMap() throws Exception {
        Doomstat.dynamicLevels = true;
        boot("-warp", "1", "1");
        assertFalse(Doomstat.dynamicLevels);
        assertEquals(-1, WWad.wCheckNumForName(DynamicLevel.MAP_NAME));
        byte[] stock = SaveGame.save();
        Doomstat.dynamicLevels = true;
        assertThrows(java.io.IOException.class, () -> SaveGame.load(stock));
        Doomstat.dynamicSeed = 3;
        DynamicLevel.mount();
        GGame.gInitNew(Doomstat.sk_medium, 1, 1);
        GGame.gDoLoadLevel();
        byte[] generated = SaveGame.save();
        Doomstat.dynamicLevels = false;
        assertThrows(java.io.IOException.class, () -> SaveGame.load(generated));
    }

    @Test void titleDemosStillLoadTheirOriginalMaps() throws Exception {
        boot("-dynamic", "-seed", "1");
        GGame.defdemoname = "demo1";
        GGame.gDoPlayDemo();
        GGame.gDoLoadLevel();
        assertTrue(Doomstat.demoplayback);
        String map = Doomstat.gamemode == Doomstat.GameMode.commercial
                ? String.format("MAP%02d", Doomstat.gamemap)
                : "E" + Doomstat.gameepisode + "M" + Doomstat.gamemap;
        assertEquals(WWad.wLumpLength(WWad.wGetNumForName(map) + PSetup.ML_LINEDEFS) / 14,
                PSetup.numlines);
    }

    @Test void invalidSeedAndUnsupportedCombinationsFailClearly() {
        for (String[] args : new String[][] {{"-dynamic", "-seed"}, {"-seed", "12"},
                {"-dynamic", "-seed", "bad"}, {"-dynamic", "-seed", "9223372036854775808"},
                {"-dynamic", "-record", "demo1"}, {"-dynamic", "-playdemo", "demo1"},
                {"-dynamic", "-timedemo", "demo1"}, {"-dynamic", "-deathmatch"}})
            assertThrows(ISystem.DoomError.class, () -> DMain.dDoomMain(args));
    }

    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private void teleport(int x, int y) {
        Mobj player = GGame.players[0].mo;
        assertTrue(PMap.pTeleportMove(player, Fixed.fromInt(x), Fixed.fromInt(y)));
        player.z = player.floorz;
    }
    private void walkWorld(int x, int y) {
        Mobj mo = GGame.players[0].mo;
        while (mo.x != Fixed.fromInt(x) || mo.y != Fixed.fromInt(y)) {
            int dx = Math.clamp(Fixed.fromInt(x) - mo.x, -Fixed.fromInt(8), Fixed.fromInt(8));
            int dy = Math.clamp(Fixed.fromInt(y) - mo.y, -Fixed.fromInt(8), Fixed.fromInt(8));
            assertTrue(PMap.pTryMove(mo, mo.x + dx, mo.y + dy), "Route must be walkable");
            GGame.gTicker();
        }
    }
    private void walkTo(DynamicLevel.Layout plan, int tx, int ty, boolean doorOpen) {
        Mobj mo = GGame.players[0].mo;
        int size = DynamicLevel.SIZE;
        int start = (Fixed.toInt(mo.y) / 128) * size + Fixed.toInt(mo.x) / 128;
        int target = ty * size + tx;
        int[] previous = new int[size * size];
        Arrays.fill(previous, -1); previous[start] = start;
        ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(start);
        while (!queue.isEmpty() && previous[target] < 0) {
            int current = queue.remove(), x = current % size, y = current / size;
            for (int[] step : STEPS) {
                int nx = x + step[0], ny = y + step[1];
                if (nx < 0 || ny < 0 || nx >= size || ny >= size) continue;
                int next = ny * size + nx, sector = plan.cells()[ny][nx];
                if (previous[next] >= 0 || sector < 0 || (!doorOpen && sector == plan.doorSector())) continue;
                previous[next] = current; queue.add(next);
            }
        }
        assertTrue(previous[target] >= 0);
        List<Integer> path = new ArrayList<>();
        for (int at = target; at != start; at = previous[at]) path.add(at);
        path.add(start); Collections.reverse(path);
        for (int cell : path) walkWorld((cell % size) * 128 + 64, (cell / size) * 128 + 64);
    }
}
