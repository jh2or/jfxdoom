package doom.system;

import doom.play.*;
import doom.render.RData;
import doom.wad.WWad;
import doom.wad.ZZone;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.Arrays;
import java.util.zip.CRC32;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SaveGameTest {
    @TempDir Path temp;

    @BeforeEach void boot() throws IOException {
        Path wad = Path.of("reference-wads/DOOM.WAD");
        if (!Files.exists(wad)) wad = Path.of("reference-wads/doom1.wad");
        assumeTrue(Files.exists(wad), "Requires Doom WAD in reference-wads");
        WWad.resetForTests();
        RData.resetForTests();
        ZZone.zInit();
        DMain.numwadfiles = 0;
        DMain.autostart = false;
        Doomstat.fastparm = Doomstat.nomonsters = Doomstat.netgame = false;
        Doomstat.respawnparm = Doomstat.respawnmonsters = false;
        Doomstat.deathmatch = 0;
        Doomstat.demoplayback = GGame.demorecording = false;
        GGame.consoleplayer = 0;
        PTick.gametic = 0;
        doom.ui.AmMap.automapactive = false;
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            GGame.players[i] = new Player();
            GGame.playeringame[i] = false;
        }
        DMain.dDoomMain(new String[] {"-iwad", wad.toString(), "-warp", "1", "1"});
        GGame.players[0].health = 73;
        GGame.players[0].mo.health = 73;
        PTick.leveltime = 321;
    }

    @AfterEach void closeWad() throws IOException { WWad.resetForTests(); }

    @Test void restoresDifferentLevelAndRuntimeSettings() throws IOException {
        int x = GGame.players[0].mo.x;
        PSetup.sectors[0].lightlevel = 112;
        PSetup.lines[0].special = 0;
        GGame.players[0].ammo[Doomdef.am_clip] = 27;
        Doomstat.fastparm = Doomstat.respawnparm = Doomstat.respawnmonsters = true;
        MRandom.rndindex = 17;
        MRandom.prndindex = 29;
        byte[] saved = SaveGame.save();
        GGame.gInitNew(Doomstat.sk_easy, 1, 2);
        GGame.gDoLoadLevel();
        SaveGame.load(saved);
        assertEquals(1, Doomstat.gamemap);
        assertEquals(321, PTick.leveltime);
        assertEquals(73, GGame.players[0].health);
        assertEquals(x, GGame.players[0].mo.x);
        assertEquals(17, MRandom.rndindex);
        assertEquals(29, MRandom.prndindex);
        assertTrue(Doomstat.fastparm && Doomstat.respawnparm && Doomstat.respawnmonsters);
        assertArrayEquals(saved, SaveGame.save());
        assertDoesNotThrow(DMain::dDoomLoop);
    }

    @Test void restoresMapWithOddLinedefCountAndPaddedZoneBuffer() throws IOException {
        GGame.gInitNew(Doomstat.sk_medium, 1, 2);
        GGame.gDoLoadLevel();
        int lump = WWad.wGetNumForName("E1M2") + PSetup.ML_LINEDEFS;
        assumeTrue(WWad.wLumpLength(lump) % 4 != 0, "Fixture must exercise zone padding");
        byte[] saved = SaveGame.save();
        GGame.gInitNew(Doomstat.sk_medium, 1, 1);
        GGame.gDoLoadLevel();
        SaveGame.load(saved);
        assertEquals(2, Doomstat.gamemap);
        assertArrayEquals(saved, SaveGame.save());
    }

    @Test void sameLevelReloadRecreatesBaseAndDoesNotDuplicateMovers() throws IOException {
        PCeilng.Ceiling ceiling = new PCeilng.Ceiling();
        ceiling.sector = PSetup.sectors[0];
        ceiling.type = PCeilng.crushAndRaise;
        ceiling.function.acp1 = PCeilng::tMoveCeiling;
        ceiling.sector.specialdata = ceiling;
        PTick.pAddThinker(ceiling);
        PCeilng.pAddActiveCeiling(ceiling);
        byte[] saved = SaveGame.save();
        Object oldSector = PSetup.sectors[0];
        SaveGame.load(saved);
        SaveGame.load(saved);
        assertNotSame(oldSector, PSetup.sectors[0]);
        assertEquals(1, Arrays.stream(PCeilng.activeceilings).filter(c -> c != null).count());
        assertEquals(321, PTick.leveltime);
    }

    @Test void loadingFromTitleExitsDemoAndRestoresPlayableLevel() throws IOException {
        byte[] saved = SaveGame.save();
        Doomstat.gamestate = Doomstat.GS_DEMOSCREEN;
        Doomstat.demoplayback = true;
        DMain.advancedemo = true;
        Doomstat.usergame = Doomstat.viewactive = false;
        doom.ui.AmMap.automapactive = true;
        SaveGame.load(saved);
        assertEquals(Doomstat.GS_LEVEL, Doomstat.gamestate);
        assertTrue(Doomstat.usergame && Doomstat.viewactive);
        assertFalse(Doomstat.demoplayback);
        assertFalse(DMain.advancedemo);
        assertFalse(doom.ui.AmMap.automapactive);
        assertDoesNotThrow(DMain::dDoomLoop);
    }

    @Test void corruptTruncatedLegacyAndSemanticErrorsLeaveActiveStateUntouched() throws IOException {
        byte[] saved = SaveGame.save();
        byte[] corrupt = saved.clone();
        corrupt[100] ^= 1;
        assertRejectedWithoutMutation(corrupt);
        for (int cut : new int[] {0, 7, 50, saved.length - 1})
            assertRejectedWithoutMutation(Arrays.copyOf(saved, cut));
        assertRejectedWithoutMutation(PSaveg.save());
        byte[] invalid = saved.clone();
        // Header=85 bytes; active-seat mask=4; playerstate is first player int.
        ByteBuffer.wrap(invalid).putInt(89, 9000);
        checksum(invalid);
        assertRejectedWithoutMutation(invalid);
        invalid = saved.clone();
        ByteBuffer.wrap(invalid).putInt(4, 999);
        checksum(invalid);
        assertRejectedWithoutMutation(invalid);
        invalid = saved.clone();
        invalid[49] ^= 1; // map digest, with a valid checksum
        checksum(invalid);
        assertRejectedWithoutMutation(invalid);
        java.io.ByteArrayOutputStream prefix = new java.io.ByteArrayOutputStream();
        PSaveg.out = new java.io.DataOutputStream(prefix);
        try {
            PSaveg.pArchivePlayers();
            PSaveg.pArchiveWorld();
        } finally { PSaveg.out = null; }
        invalid = saved.clone();
        ByteBuffer.wrap(invalid).putInt(85 + prefix.size() + 1 + 16, Integer.MAX_VALUE);
        checksum(invalid);
        assertRejectedWithoutMutation(invalid);
        invalid = saved.clone();
        invalid[invalid.length - 9] = (byte) 255; // unknown special marker
        checksum(invalid);
        assertRejectedWithoutMutation(invalid);
    }

    @Test void fileApiHandlesLongDescriptionsAndReportsBadLoad() throws IOException {
        Path file = temp.resolve("save.dsg");
        GGame.saveName = file.toString();
        GGame.saveDescription = "a".repeat(300);
        GGame.gDoSaveGame();
        assertEquals("game saved.", GGame.players[0].message);
        assertEquals(255, GGame.gReadSaveFileDescription(file.toString()).length());
        GGame.gInitNew(Doomstat.sk_easy, 1, 2);
        GGame.gDoLoadLevel();
        GGame.gDoLoadGame();
        assertEquals("game loaded.", GGame.players[0].message);
        assertEquals(1, Doomstat.gamemap);
        byte[] before = SaveGame.save();
        Files.write(file, new byte[] {100, 1});
        GGame.gDoLoadGame();
        assertTrue(GGame.players[0].message.startsWith("load failed:"));
        assertEquals(GGame.ga_nothing, GGame.gameaction);
        assertArrayEquals(before, SaveGame.save());
    }

    private static void checksum(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - 8);
        ByteBuffer.wrap(bytes).putLong(bytes.length - 8, crc.getValue());
    }

    private static void assertRejectedWithoutMutation(byte[] invalid) throws IOException {
        byte[] before = SaveGame.save();
        Object player = GGame.players[0], sector = PSetup.sectors[0];
        assertThrows(IOException.class, () -> SaveGame.load(invalid));
        assertSame(player, GGame.players[0]);
        assertSame(sector, PSetup.sectors[0]);
        assertArrayEquals(before, SaveGame.save());
    }
}
