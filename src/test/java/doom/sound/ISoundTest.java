package doom.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ISoundTest {

    @BeforeEach
    void reset() {
        ISound.iSetChannels();
    }

    // ---- decodeDmx ----

    // Standard DMX lump (format=3, rate=11025, length=4, samples=
    // 128,200,100,128) parses to a 4-byte buffer at 11025 Hz.
    @Test
    void decodeDmxParsesHeader() {
        byte[] lump = {
                3, 0,            // format
                0x11, 0x2b,      // rate 11025 (LE)
                4, 0, 0, 0,      // length 4
                (byte) 128, (byte) 200, (byte) 100, (byte) 128,
        };
        ISound.DmxBlob blob = ISound.decodeDmx(lump);
        assertNotNull(blob);
        assertEquals(11025, blob.rate);
        assertEquals(4, blob.samples.length);
        assertEquals((byte) 200, blob.samples[1]);
    }

    // Length exceeding lump size is clamped (defensive).
    @Test
    void decodeDmxClampsLength() {
        byte[] lump = {
                3, 0,
                0x11, 0x2b,
                (byte) 0xff, (byte) 0xff, 0, 0,  // length 65535 (way too large)
                1, 2, 3, 4,
        };
        ISound.DmxBlob blob = ISound.decodeDmx(lump);
        assertNotNull(blob);
        assertEquals(4, blob.samples.length);
    }

    // ---- channel allocation ----

    // iStartSound with a pre-decoded sfx assigns a positive handle.
    @Test
    void startSoundAllocatesChannel() {
        Sounds.Sfxinfo sfx = new Sounds.Sfxinfo("test", false, 64, null, 0, 0, 0);
        sfx.lumpnum = 0;
        sfx.data = new ISound.DmxBlob(new byte[] { 100, 110, 120, (byte) 130 }, 11025);
        int handle = ISound.iStartSound(0, sfx, 127, 128, 128, 64);
        assertTrue(handle > 0);
        assertEquals(1, ISound.iSoundIsPlaying(handle));
    }

    // iStopSound releases the slot.
    @Test
    void stopSoundReleasesChannel() {
        Sounds.Sfxinfo sfx = new Sounds.Sfxinfo("test", false, 64, null, 0, 0, 0);
        sfx.lumpnum = 0;
        sfx.data = new ISound.DmxBlob(new byte[] { 100, 110 }, 11025);
        int handle = ISound.iStartSound(0, sfx, 127, 128, 128, 64);
        ISound.iStopSound(handle);
        assertEquals(0, ISound.iSoundIsPlaying(handle));
    }

    // Successive plays produce distinct handles.
    @Test
    void startSoundProducesDistinctHandles() {
        Sounds.Sfxinfo sfx = new Sounds.Sfxinfo("test", false, 64, null, 0, 0, 0);
        sfx.lumpnum = 0;
        sfx.data = new ISound.DmxBlob(new byte[] { 100 }, 11025);
        int h1 = ISound.iStartSound(0, sfx, 127, 128, 128, 64);
        int h2 = ISound.iStartSound(0, sfx, 127, 128, 128, 64);
        assertNotEquals(h1, h2);
    }

    // ---- volume / sep ----

    // sep=0 → full left, zero right. The applyVolumeSep clamp
    // caps vol at the menu range [0, 15] and then multiplies by
    // 8 for the mixer's [0, 127]-scaled per-sample math, so
    // vol=15 yields leftvol=120 at full pan, vol=127 also
    // clamps to leftvol=120.
    @Test
    void applyVolumeSepFullLeftPan() {
        Sounds.Sfxinfo sfx = new Sounds.Sfxinfo("test", false, 64, null, 0, 0, 0);
        sfx.lumpnum = 0;
        sfx.data = new ISound.DmxBlob(new byte[] { 0 }, 11025);
        int h = ISound.iStartSound(0, sfx, 15, 0, 128, 64);
        // Walk channels to find the one with this handle.
        for (ISound.Channel c : ISound.channels) {
            if (c.handle == h) {
                assertEquals(120, c.leftvol);
                assertEquals(0,   c.rightvol);
                return;
            }
        }
    }

    // sep=255 → zero left, full right.
    @Test
    void applyVolumeSepFullRightPan() {
        Sounds.Sfxinfo sfx = new Sounds.Sfxinfo("test", false, 64, null, 0, 0, 0);
        sfx.lumpnum = 0;
        sfx.data = new ISound.DmxBlob(new byte[] { 0 }, 11025);
        int h = ISound.iStartSound(0, sfx, 15, 255, 128, 64);
        for (ISound.Channel c : ISound.channels) {
            if (c.handle == h) {
                assertEquals(0,   c.leftvol);
                assertEquals(120, c.rightvol);
                return;
            }
        }
    }
}
