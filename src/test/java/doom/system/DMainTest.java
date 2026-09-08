package doom.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import doom.play.PSetup;
import doom.play.PTick;
import doom.wad.WWad;
import doom.wad.ZZone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DMainTest {

    private static final Path WAD_PATH = resolveWad();

    private static Path resolveWad() {
        for (String c : new String[] {
                "reference-wads/DOOM.WAD",
                "reference-wads/doom.wad",
                "reference-wads/doom1.wad",
                "reference-wads/DOOM1.WAD" }) {
            if (Files.exists(Paths.get(c))) {
                return Paths.get(c).toAbsolutePath();
            }
        }
        return null;
    }

    @BeforeAll
    void ensureWadPresent() {
        assumeTrue(WAD_PATH != null,
                "Skipping DMainTest — no WAD in reference-wads/");
    }

    @BeforeEach
    void reset() {
        // Reset cached state across tests since DMain.dDoomMain
        // mutates a lot of statics.
        DMain.numwadfiles = 0;
        for (int i = 0; i < DMain.MAXWADFILES; i++) {
            DMain.wadfiles[i] = null;
        }
        DMain.startskill = 0;
        DMain.startepisode = 0;
        DMain.startmap = 0;
        DMain.autostart = false;
        DMain.devparm = false;

        ZZone.zInit();
        try {
            WWad.resetForTests();
        } catch (IOException ignored) {
            // resetForTests is idempotent.
        }
        doom.render.RData.resetForTests();

        for (int i = 0; i < doom.system.Doomdef.MAXPLAYERS; i++) {
            doom.system.GGame.players[i] = new doom.play.Player();
            doom.system.GGame.playeringame[i] = false;
        }

        PTick.pInitThinkers();
        PTick.leveltime = 0;
        PTick.gametic = 0;
    }

    // dAddFile appends to the wadfiles[] list.
    @Test
    void addFileAppends() {
        DMain.numwadfiles = 0;
        DMain.dAddFile("a.wad");
        DMain.dAddFile("b.wad");
        assertEquals(2, DMain.numwadfiles);
        assertEquals("a.wad", DMain.wadfiles[0]);
        assertEquals("b.wad", DMain.wadfiles[1]);
    }

    // -iwad parsing.
    @Test
    void iwadFlagAddsToWadList() throws IOException {
        // Need a real WAD to avoid the W_InitFile failure.
        DMain.dDoomMain(new String[] { "-iwad", WAD_PATH.toString() });
        assertEquals(1, DMain.numwadfiles);
        assertEquals(WAD_PATH.toString(), DMain.wadfiles[0]);
    }

    // -warp E M parsing for Doom 1 sets episode + map + autostart.
    @Test
    void warpFlagSetsEpisodeAndMapAndAutostartsLevel() throws IOException {
        DMain.dDoomMain(new String[] {
                "-iwad", WAD_PATH.toString(),
                "-warp", "1", "1" });

        assertTrue(DMain.autostart);
        assertEquals(1, DMain.startepisode);
        assertEquals(1, DMain.startmap);

        // E1M1 was loaded → PSetup populated.
        assertTrue(PSetup.numsectors > 0);
        assertTrue(PSetup.numlines > 0);

        // Player 0 is now active (D_CheckNetGame seed).
        assertTrue(doom.system.GGame.playeringame[0]);
    }

    // -nomonsters / -respawn / -fast flags map to Doomstat.
    @Test
    void monsterFlagsMapToDoomstat() throws IOException {
        DMain.dDoomMain(new String[] {
                "-iwad", WAD_PATH.toString(),
                "-nomonsters",
                "-respawn",
                "-fast" });

        assertTrue(Doomstat.nomonsters);
        assertTrue(Doomstat.respawnparm);
        assertTrue(Doomstat.fastparm);
    }

    // dDoomLoop on a freshly-warped level runs without throwing.
    @Test
    void dDoomLoopRunsOneTickAfterWarp() throws IOException {
        DMain.dDoomMain(new String[] {
                "-iwad", WAD_PATH.toString(),
                "-warp", "1", "1",
                "-nomonsters" });

        // Player 0 now has a Player but no mobj yet (P_SpawnMapThing
        // creates the mobj only if playeringame[0] was set BEFORE
        // pLoadThings. The test arranges that — verify.)
        doom.play.Player p = doom.system.GGame.players[0];
        assertNotNull(p);
        // Run one tick — must not throw.
        DMain.dDoomLoop();
    }
}
