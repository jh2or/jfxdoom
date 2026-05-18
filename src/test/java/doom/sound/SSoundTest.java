package doom.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.play.Mobj;
import doom.play.Player;
import doom.render.Degenmobj;
import doom.system.Doomstat;
import doom.system.GGame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SSoundTest {

    private static Sounds.Sfxinfo testSfx(int lump) {
        Sounds.Sfxinfo sfx = new Sounds.Sfxinfo("test", false, 64, null, 0, 0, 0);
        sfx.lumpnum = lump;
        // Pre-decoded blob so iStartSound doesn't try to read WAD.
        sfx.data = new ISound.DmxBlob(new byte[] { 100, 110, 120 }, 11025);
        return sfx;
    }

    @BeforeEach
    void reset() {
        for (int i = 0; i < SSound.numChannels; i++) {
            SSound.channels[i].sfxinfo = null;
            SSound.channels[i].origin = null;
            SSound.channels[i].handle = 0;
        }
        ISound.iSetChannels();
        SSound.snd_SfxVolume = 15;
        Doomstat.netgame = false;
        Doomstat.deathmatch = 0;
        Doomstat.gamemap = 1;
        GGame.players[0] = new Player();
        GGame.players[0].mo = new Mobj();
        GGame.consoleplayer = 0;
    }

    // ---- sGetChannel ----

    // First free slot is allocated.
    @Test
    void getChannelAllocatesFirstFreeSlot() {
        Sounds.Sfxinfo sfx = testSfx(0);
        int cnum = SSound.sGetChannel(null, sfx);
        assertEquals(0, cnum);
        assertSame(sfx, SSound.channels[0].sfxinfo);
    }

    // Same origin re-allocation stops + reuses the prior slot.
    // All preceding slots must be occupied so the free-slot loop
    // walks past them and reaches the matching-origin branch.
    @Test
    void getChannelReusesSlotForSameOrigin() {
        Sounds.Sfxinfo blocker = new Sounds.Sfxinfo("blk", false, 64, null, 0, 0, 0);
        for (int i = 0; i < 3; i++) {
            SSound.channels[i].sfxinfo = blocker;
            SSound.channels[i].origin = new Object();
        }
        Sounds.Sfxinfo sfx = testSfx(0);
        Mobj origin = new Mobj();
        SSound.channels[3].sfxinfo = sfx;
        SSound.channels[3].origin = origin;
        SSound.channels[3].handle = 7;
        int cnum = SSound.sGetChannel(origin, sfx);
        assertEquals(3, cnum);
    }

    // s_sound.c lines 850–860 — when every existing channel has
    // priority strictly less than the new sound's priority, no
    // slot is >= the new priority → "No lower priority. Sorry,
    // Charlie." → -1. The C's >= test looks backwards but is
    // preserved verbatim (CLAUDE.md §2).
    @Test
    void getChannelReturnsMinusOneWhenAllSlotsAreLowerPriority() {
        Sounds.Sfxinfo blocker = new Sounds.Sfxinfo("blk", false, 50, null, 0, 0, 0);
        for (int i = 0; i < SSound.numChannels; i++) {
            SSound.channels[i].sfxinfo = blocker;
            SSound.channels[i].origin = new Object();
        }
        Sounds.Sfxinfo highPrio = new Sounds.Sfxinfo("hi", false, 200, null, 0, 0, 0);
        assertEquals(-1, SSound.sGetChannel(null, highPrio));
    }

    // ---- sAdjustSoundParams ----

    // Source far away returns 0 (clipped) on non-boss maps.
    @Test
    void adjustSoundParamsClipsAtFarDistance() {
        Doomstat.gamemap = 1;
        Mobj listener = new Mobj();
        listener.x = 0;
        listener.y = 0;
        listener.angle = 0;
        Mobj source = new Mobj();
        source.x = SSound.S_CLIPPING_DIST + 1;
        source.y = SSound.S_CLIPPING_DIST + 1;
        int[] vol = { 15 };
        int[] sep = { 0 };
        int[] pit = { 128 };
        assertEquals(0, SSound.sAdjustSoundParams(listener, source, vol, sep, pit));
    }

    // Close source gets full volume.
    @Test
    void adjustSoundParamsCloseSourceFullVolume() {
        Mobj listener = new Mobj();
        Mobj source = new Mobj();
        source.x = SSound.S_CLOSE_DIST / 4;
        source.y = SSound.S_CLOSE_DIST / 4;
        int[] vol = { 0 };
        int[] sep = { 0 };
        int[] pit = { 128 };
        assertEquals(1, SSound.sAdjustSoundParams(listener, source, vol, sep, pit));
        assertEquals(SSound.snd_SfxVolume, vol[0]);
    }

    // ---- sStopSound ----

    @Test
    void stopSoundReleasesMatchingChannel() {
        Sounds.Sfxinfo sfx = testSfx(0);
        Mobj origin = new Mobj();
        SSound.channels[2].sfxinfo = sfx;
        SSound.channels[2].origin = origin;
        SSound.channels[2].handle = 1;
        SSound.sStopSound(origin);
        assertNull(SSound.channels[2].sfxinfo);
    }

    // ---- sStartSound integration ----

    // S_StartSound assigns a channel + ISound handle for a sfx with
    // pre-decoded data + a near origin.
    @Test
    void startSoundAllocatesChannelAndHandle() {
        Sounds.S_sfx[Sounds.sfx_pistol].lumpnum = 0;
        Sounds.S_sfx[Sounds.sfx_pistol].data = new ISound.DmxBlob(
                new byte[] { 100 }, 11025);
        SSound.sStartSound(GGame.players[0].mo, Sounds.sfx_pistol);
        // Some channel should now hold sfx_pistol.
        boolean found = false;
        for (SSound.Channel c : SSound.channels) {
            if (c.sfxinfo == Sounds.S_sfx[Sounds.sfx_pistol]) {
                found = true;
                assertTrue(c.handle > 0);
                break;
            }
        }
        assertTrue(found);
    }

    // Degenmobj source origins resolve via originX/originY.
    @Test
    void startSoundAcceptsDegenmobjOrigin() {
        Sounds.S_sfx[Sounds.sfx_doropn].lumpnum = 0;
        Sounds.S_sfx[Sounds.sfx_doropn].data = new ISound.DmxBlob(
                new byte[] { 100 }, 11025);
        Degenmobj soundorg = new Degenmobj();
        soundorg.x = SSound.S_CLOSE_DIST / 2;
        soundorg.y = SSound.S_CLOSE_DIST / 2;
        SSound.sStartSound(soundorg, Sounds.sfx_doropn);
        boolean found = false;
        for (SSound.Channel c : SSound.channels) {
            if (c.sfxinfo == Sounds.S_sfx[Sounds.sfx_doropn]) {
                found = true;
                assertSame(soundorg, c.origin);
                break;
            }
        }
        assertTrue(found);
    }
}
