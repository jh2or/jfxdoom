package doom.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.play.Player;
import doom.play.Ticcmd;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GGameDemoTest {

    @BeforeEach
    void resetState() {
        GGame.demoBuffer = null;
        GGame.demoP = 0;
        GGame.demoEnd = 0;
        GGame.demorecording = false;
        GGame.demoName = null;
        GGame.defdemoname = null;
        Doomstat.demoplayback = false;
        Doomstat.netdemo = false;
        Doomstat.singledemo = false;
        Doomstat.netgame = false;
        Doomstat.deathmatch = 0;
        Doomstat.respawnparm = false;
        Doomstat.respawnmonsters = false;
        Doomstat.fastparm = false;
        Doomstat.nomonsters = false;
        Doomstat.usergame = true;
        Doomstat.gameskill = Doomstat.sk_medium;
        Doomstat.gameepisode = 1;
        Doomstat.gamemap = 1;
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            GGame.players[i] = new Player();
            GGame.playeringame[i] = false;
        }
        GGame.consoleplayer = 0;
        GGame.gameaction = GGame.ga_nothing;
        for (int i = 0; i < Doomdef.NUMKEYS; i++) {
            GGame.gamekeydown[i] = false;
        }
    }

    // ---- gReadDemoTiccmd ----

    // Four bytes from the demo buffer land in the ticcmd's
    // forwardmove / sidemove / angleturn (<<8) / buttons fields.
    @Test
    void readDemoTiccmdParses4Bytes() {
        GGame.demoBuffer = new byte[] { 20, -10, 5, 0x40, (byte) GGame.DEMOMARKER };
        GGame.demoP = 0;
        Ticcmd cmd = new Ticcmd();
        GGame.gReadDemoTiccmd(cmd);
        assertEquals(20,    cmd.forwardmove);
        assertEquals(-10,   cmd.sidemove);
        assertEquals(5 << 8, cmd.angleturn);
        assertEquals(0x40,  cmd.buttons);
        assertEquals(4, GGame.demoP);
    }

    // A DEMOMARKER (0x80) byte terminates playback by calling
    // gCheckDemoStatus, which clears demoplayback.
    @Test
    void readDemoTiccmdMarkerEndsPlayback() {
        GGame.demoBuffer = new byte[] { (byte) GGame.DEMOMARKER };
        GGame.demoP = 0;
        Doomstat.demoplayback = true;
        Doomstat.singledemo = false;
        // dStartTitle ends up being called — arm a fresh advancedemo
        // latch.
        DMain.advancedemo = false;
        Ticcmd cmd = new Ticcmd();
        GGame.gReadDemoTiccmd(cmd);
        assertFalse(Doomstat.demoplayback);
        assertNull(GGame.demoBuffer);
    }

    // ---- gWriteDemoTiccmd ----

    // The recording cursor advances by 4 each write.
    @Test
    void writeDemoTiccmdAppends4Bytes() {
        GGame.demoBuffer = new byte[128];
        GGame.demoP = 0;
        GGame.demoEnd = 128;
        GGame.demorecording = true;
        Ticcmd cmd = new Ticcmd();
        cmd.forwardmove = 30;
        cmd.sidemove    = -25;
        cmd.angleturn   = 0x300;
        cmd.buttons     = 0x05;
        GGame.gWriteDemoTiccmd(cmd);
        assertEquals(30,  GGame.demoBuffer[0]);
        assertEquals(-25, GGame.demoBuffer[1]);
        // angleturn is right-shifted by 8 with +128 bias.
        assertEquals((0x300 + 128) >> 8,
                GGame.demoBuffer[2] & 0xff);
        assertEquals(0x05, GGame.demoBuffer[3] & 0xff);
        // demoP advanced by 4 (the C trick: write 4, gReadDemoTiccmd
        // reads them back which also advances by 4 — so end state
        // is +4, not +0).
        assertEquals(4, GGame.demoP);
    }

    // 'q' during recording terminates via gCheckDemoStatus.
    @Test
    void writeDemoTiccmdQuitOnQKeyEndsRecording(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        GGame.demoBuffer = new byte[128];
        GGame.demoP = 0;
        GGame.demoEnd = 128;
        GGame.demorecording = true;
        GGame.demoName = tmp.resolve("throwaway.lmp").toString();
        GGame.gamekeydown['q'] = true;
        Ticcmd cmd = new Ticcmd();
        GGame.gWriteDemoTiccmd(cmd);
        assertFalse(GGame.demorecording);
    }

    // ---- gRecordDemo ----

    @Test
    void recordDemoAllocatesBufferAndArmsRecording() {
        // -maxdemo not in argv → default 128 KiB.
        MArgv.myargc = 1;
        MArgv.myargv = new String[] { "doom" };
        GGame.gRecordDemo("foo");
        assertTrue(GGame.demorecording);
        assertNotNull(GGame.demoBuffer);
        assertEquals(0x20000, GGame.demoBuffer.length);
        assertEquals("foo.lmp", GGame.demoName);
        assertFalse(Doomstat.usergame);
    }

    @Test
    void recordDemoHonorsMaxdemoCmdline() {
        MArgv.myargc = 3;
        MArgv.myargv = new String[] { "doom", "-maxdemo", "256" };
        GGame.gRecordDemo("bar");
        // 256 KiB
        assertEquals(256 * 1024, GGame.demoBuffer.length);
    }

    // ---- gBeginRecording ----

    @Test
    void beginRecordingWrites9ByteHeaderAndPlayerFlags() {
        GGame.demoBuffer = new byte[64];
        GGame.demoP = 0;
        Doomstat.gameskill = Doomstat.sk_hard;
        Doomstat.gameepisode = 2;
        Doomstat.gamemap = 5;
        Doomstat.deathmatch = 1;
        Doomstat.respawnparm = true;
        Doomstat.fastparm = false;
        Doomstat.nomonsters = true;
        GGame.consoleplayer = 0;
        GGame.playeringame[0] = true;
        GGame.gBeginRecording();
        assertEquals(GGame.DEMOVERSION, GGame.demoBuffer[0] & 0xff);
        assertEquals(Doomstat.sk_hard,  GGame.demoBuffer[1] & 0xff);
        assertEquals(2,                  GGame.demoBuffer[2] & 0xff);
        assertEquals(5,                  GGame.demoBuffer[3] & 0xff);
        assertEquals(1,                  GGame.demoBuffer[4] & 0xff);
        assertEquals(1,                  GGame.demoBuffer[5] & 0xff);  // respawn
        assertEquals(0,                  GGame.demoBuffer[6] & 0xff);  // fast
        assertEquals(1,                  GGame.demoBuffer[7] & 0xff);  // nomon
        assertEquals(0,                  GGame.demoBuffer[8] & 0xff);  // console
        assertEquals(1,                  GGame.demoBuffer[9] & 0xff);  // playeringame[0]
        // Cursor advanced past the header + MAXPLAYERS.
        assertEquals(9 + Doomdef.MAXPLAYERS, GGame.demoP);
    }

    // ---- gDeferedPlayDemo ----

    @Test
    void deferedPlayDemoQueuesGameAction() {
        GGame.gameaction = GGame.ga_nothing;
        GGame.gDeferedPlayDemo("demo2");
        assertEquals(GGame.ga_playdemo, GGame.gameaction);
        assertEquals("demo2", GGame.defdemoname);
    }

    // ---- gCheckDemoStatus playback ----

    // After playback ends, demoplayback / netgame / deathmatch all
    // clear and the title-screen demo loop is re-armed.
    @Test
    void checkDemoStatusPlaybackResetsState() {
        Doomstat.demoplayback = true;
        Doomstat.netgame = true;
        Doomstat.deathmatch = 1;
        Doomstat.fastparm = true;
        Doomstat.respawnparm = true;
        GGame.playeringame[1] = true;
        GGame.demoBuffer = new byte[] { (byte) GGame.DEMOMARKER };
        DMain.advancedemo = false;
        boolean cycled = GGame.gCheckDemoStatus();
        assertTrue(cycled);
        assertFalse(Doomstat.demoplayback);
        assertFalse(Doomstat.netgame);
        assertEquals(0, Doomstat.deathmatch);
        assertFalse(Doomstat.fastparm);
        assertFalse(Doomstat.respawnmonsters);
        assertFalse(GGame.playeringame[1]);
        assertTrue(DMain.advancedemo);
    }
}
