// Port of linuxdoom-1.10/i_sound.c — JavaFX/javax.sound backend.
//
// The C source is a tangle of Linux DMX driver glue + UDP sound-
// server forwarding that doesn't translate to Java. The Java port
// keeps the same C-side entry points (iInitSound / iSetChannels /
// iStartSound / iStopSound / iSoundIsPlaying / iUpdateSoundParams
// / iUpdateSound / iSubmitSound / iShutdownSound) but rewires the
// backend to javax.sound.sampled.
//
// Architecture (deviation 36):
//   - Each sfx lump is decoded once from its DMX header (3-byte
//     pad + 1-byte format=3 + 2-byte rate + 4-byte length + N
//     samples + 16-byte trailer) into a `byte[]` of 8-bit unsigned
//     PCM. The decoded buffer is cached on Sfxinfo.data so repeat
//     plays don't re-parse.
//   - Per-channel state lives in `Channel[NUMCHANNELS]`. A channel
//     holds the source samples, a play cursor, current volume +
//     stereo separation, and a monotonic handle.
//   - iSubmitSound mixes all active channels into a 22050 Hz / 16-
//     bit / stereo `SourceDataLine` once per tic (28ms = 630
//     frames). Mixing is integer-saturating add of signed samples.
//
// Music plays through `javax.sound.midi.Sequencer` driven by the
// JVM's bundled General MIDI synth — iRegisterSong converts the
// MUS lump to a MIDI byte stream (see MusToMidi), loads it into
// the sequencer, and iPlaySong starts playback. iSetMusicVolume
// taps the synth's master-gain controller.
package doom.sound;

import doom.system.ISystem;
import doom.wad.WWad;
import doom.wad.ZZone;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public final class ISound {

    // s_sound.c — channel count. Doom shipped with 8 in DOS / 16
    // in Linux; the Java port follows Linux.
    public static final int NUM_CHANNELS = 16;

    // Output mix format: 22050 Hz mono is the historical Doom rate;
    // the Java port up-samples each frame to 22050 Hz (no actual
    // resample — samples are repeated for the rate ratio) and emits
    // signed 16-bit little-endian. Stereo separation drives the
    // per-channel L/R gains.
    private static final int OUTPUT_RATE     = 22050;
    private static final int SAMPLES_PER_TIC = OUTPUT_RATE / 35;  // 630
    private static final AudioFormat OUTPUT_FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            OUTPUT_RATE, 16, 2, 4, OUTPUT_RATE, false);

    // Menu-range → backend-range gain. The C uniformly multiplied
    // the [0, 15] menu volume by 8 to reach the DMX driver's
    // [0, 127] scale on both sides. The Java port splits the
    // multiplier per channel: the JVM's General MIDI synth runs
    // hot relative to our 8-bit DMX SFX mixer, so equal scale
    // makes music perceptibly louder than SFX even at matched
    // slider positions. Tuning these constants keeps the menu
    // sliders centred on a balanced default.
    private static final int SFX_VOLUME_SCALE   = 28; // peak leftvol 420 at slider=15 (+10 dB over the old ×9)
    private static final int MUSIC_VOLUME_SCALE = 5;  // peak CC 7 = 75 at slider=15

    //
    // Per-channel mixer state.
    //
    public static final class Channel {
        public byte[] data;       // decoded 8-bit unsigned PCM
        public int    sampleRate; // source rate (typically 11025)
        public double cursor;     // playback position (fractional source samples)
        public int    handle;     // monotonic identity (>0 when active)
        public int    leftvol;    // 0..vol*SFX_VOLUME_SCALE; mixer clamps at int16 max
        public int    rightvol;   // same
    }

    public static final Channel[] channels = new Channel[NUM_CHANNELS];

    private static SourceDataLine line;
    private static int            nextHandle = 1;
    private static final byte[]   mixBufferI16 = new byte[SAMPLES_PER_TIC * 4];
    private static final short[]  mixL = new short[SAMPLES_PER_TIC];
    private static final short[]  mixR = new short[SAMPLES_PER_TIC];

    static {
        for (int i = 0; i < NUM_CHANNELS; i++) channels[i] = new Channel();
    }

    private ISound() {
    }

    //
    // I_InitSound
    // i_sound.c — Open + buffer the platform audio device.
    // Buffer = ~1 s of stereo 16-bit @ 22050 Hz (4 bytes/frame).
    // The previous code opened with OUTPUT_RATE *bytes* (≈250 ms)
    // which left no headroom when the game loop briefly fell
    // behind — iSubmitSound's "drop the tic if the line is near-
    // full" path would silence SFX mid-shot on a frame-rate dip.
    private static final int LINE_BUFFER_BYTES = OUTPUT_RATE * 4;

    public static void iInitSound() {
        if (line != null) return;
        try {
            line = AudioSystem.getSourceDataLine(OUTPUT_FORMAT);
            line.open(OUTPUT_FORMAT, LINE_BUFFER_BYTES);
            line.start();
        } catch (LineUnavailableException e) {
            // No audio device — degrade to silent.
            line = null;
        }
    }

    // i_sound.c — I_ShutdownSound.
    public static void iShutdownSound() {
        if (line != null) {
            line.drain();
            line.close();
            line = null;
        }
    }

    // Music subsystem state.
    private static Sequencer    musicSequencer;
    private static Synthesizer  musicSynth;
    private static javax.sound.midi.Receiver musicReceiver;
    private static Sequence     musicSequence;
    private static int          musicHandleSeq = 1;
    private static int          currentMusicHandle;

    // i_sound.c — I_InitMusic. Open the JVM default Sequencer +
    // Synthesizer and wire them together. The Sequencer broadcasts
    // events to the Synthesizer via a shared transmitter.
    public static void iInitMusic() {
        if (musicSequencer != null) return;
        try {
            musicSequencer = MidiSystem.getSequencer(false);
            musicSequencer.open();
            musicSynth = MidiSystem.getSynthesizer();
            musicSynth.open();
            // Cache the receiver. Gervill's getReceiver() returns the
            // default receiver each call, but holding our own
            // reference avoids relying on that contract.
            musicReceiver = musicSynth.getReceiver();
            musicSequencer.getTransmitter().setReceiver(musicReceiver);
        } catch (MidiUnavailableException e) {
            // No MIDI device — silent music. Same shape as iInitSound
            // bailing on LineUnavailableException.
            musicSequencer = null;
            musicSynth = null;
            musicReceiver = null;
        }
    }

    // i_sound.c — I_ShutdownMusic.
    public static void iShutdownMusic() {
        if (musicSequencer != null) {
            try { musicSequencer.stop(); } catch (Exception ignored) {}
            musicSequencer.close();
            musicSequencer = null;
        }
        if (musicSynth != null) {
            musicSynth.close();
            musicSynth = null;
        }
        musicReceiver = null;
        musicSequence = null;
    }

    //
    // I_SetChannels
    // i_sound.c — Initialise the mixer channels. Calling iInitSound
    // already did the work; this exists as a separate C-side entry
    // because the DOS path needed it.
    public static void iSetChannels() {
        for (int i = 0; i < NUM_CHANNELS; i++) {
            channels[i].data = null;
            channels[i].handle = 0;
        }
    }

    //
    // I_GetSfxLumpNum
    // i_sound.c — Maps a Sfxinfo to its WAD lump (DS-prefix). Used
    // by S_StartSoundAtVolume's first-use cache.
    public static int iGetSfxLumpNum(Sounds.Sfxinfo sfx) {
        String name = "DS" + sfx.name.toUpperCase();
        return WWad.wCheckNumForName(name);
    }

    //
    // I_StartSound
    // i_sound.c — Allocate a free channel, decode the sfx lump into
    // 8-bit unsigned PCM, store the play cursor + L/R gains. Returns
    // a positive handle the caller stows on its mixer channel.
    public static int iStartSound(int sfxId, Sounds.Sfxinfo sfx,
                                  int vol, int sep, int pitch, int priority) {
        if (sfx.lumpnum < 0) {
            sfx.lumpnum = iGetSfxLumpNum(sfx);
        }
        if (sfx.lumpnum < 0) {
            return -1;
        }

        // Lazy-decode the lump on first play. Catch both IOException
        // (WAD read error) and ISystem.DoomError (no WAD loaded —
        // playsim unit tests run without one).
        if (sfx.data == null) {
            try {
                byte[] lump = WWad.wCacheLumpNum(sfx.lumpnum, ZZone.PU_STATIC);
                sfx.data = decodeDmx(lump);
            } catch (IOException | ISystem.DoomError e) {
                return -1;
            }
            if (sfx.data == null) return -1;
        }
        DmxBlob blob = (DmxBlob) sfx.data;

        // Pick a free channel — slot 0..NUM_CHANNELS-1.
        int slot = -1;
        for (int i = 0; i < NUM_CHANNELS; i++) {
            if (channels[i].handle == 0) { slot = i; break; }
        }
        if (slot < 0) {
            // No free channel — kick the lowest handle (= oldest).
            slot = 0;
            int oldest = channels[0].handle;
            for (int i = 1; i < NUM_CHANNELS; i++) {
                if (channels[i].handle > 0 && channels[i].handle < oldest) {
                    oldest = channels[i].handle;
                    slot = i;
                }
            }
        }

        Channel c = channels[slot];
        c.data       = blob.samples;
        c.sampleRate = blob.rate;
        c.cursor     = 0;
        c.handle     = nextHandle++;
        if (nextHandle <= 0) nextHandle = 1;
        applyVolumeSep(c, vol, sep);
        return c.handle;
    }

    // i_sound.c — I_StopSound. Caller passes the handle returned by
    // iStartSound; we walk the channel table to find + free the slot.
    public static void iStopSound(int handle) {
        if (handle <= 0) return;
        for (int i = 0; i < NUM_CHANNELS; i++) {
            if (channels[i].handle == handle) {
                channels[i].handle = 0;
                channels[i].data   = null;
                return;
            }
        }
    }

    // i_sound.c — I_SoundIsPlaying. Returns 1 if the handle is
    // currently allocated to a channel, 0 otherwise.
    public static int iSoundIsPlaying(int handle) {
        if (handle <= 0) return 0;
        for (int i = 0; i < NUM_CHANNELS; i++) {
            if (channels[i].handle == handle) return 1;
        }
        return 0;
    }

    // i_sound.c — I_UpdateSoundParams. Live volume/sep update from
    // S_UpdateSounds' per-frame attenuation re-check.
    public static void iUpdateSoundParams(int handle, int vol, int sep, int pitch) {
        if (handle <= 0) return;
        for (int i = 0; i < NUM_CHANNELS; i++) {
            if (channels[i].handle == handle) {
                applyVolumeSep(channels[i], vol, sep);
                return;
            }
        }
    }

    //
    // I_UpdateSound + I_SubmitSound
    //
    // The C splits these for the DMX background thread. The Java
    // port does the mixing in iSubmitSound — iUpdateSound is a no-
    // op since there's no out-of-band buffer prep.
    public static void iUpdateSound() {
        // No-op.
    }

    public static void iSubmitSound() {
        if (line == null) return;

        // Zero the per-tic mix.
        for (int i = 0; i < SAMPLES_PER_TIC; i++) {
            mixL[i] = 0;
            mixR[i] = 0;
        }

        // Mix each active channel.
        for (int ch = 0; ch < NUM_CHANNELS; ch++) {
            Channel c = channels[ch];
            if (c.handle == 0 || c.data == null) continue;

            double step = (double) c.sampleRate / OUTPUT_RATE;
            for (int i = 0; i < SAMPLES_PER_TIC; i++) {
                int idx = (int) c.cursor;
                if (idx >= c.data.length) {
                    // Channel exhausted.
                    c.handle = 0;
                    c.data = null;
                    break;
                }
                // DMX samples are 8-bit unsigned, silence at 128.
                int sample = ((c.data[idx] & 0xff) - 128) << 7;  // → ~-16383..16256
                mixL[i] = clampShort(mixL[i] + (sample * c.leftvol  >> 7));
                mixR[i] = clampShort(mixR[i] + (sample * c.rightvol >> 7));
                c.cursor += step;
            }
        }

        // Interleave into the output buffer (little-endian 16-bit
        // L/R pairs).
        for (int i = 0; i < SAMPLES_PER_TIC; i++) {
            mixBufferI16[i * 4]     = (byte) (mixL[i]        & 0xff);
            mixBufferI16[i * 4 + 1] = (byte) ((mixL[i] >> 8) & 0xff);
            mixBufferI16[i * 4 + 2] = (byte) (mixR[i]        & 0xff);
            mixBufferI16[i * 4 + 3] = (byte) ((mixR[i] >> 8) & 0xff);
        }
        // Blocking write. The previous non-blocking variant
        // dropped the whole tic whenever the line couldn't fit
        // another 2 520 B — which on a brief frame-rate dip
        // chopped gunshots / footsteps mid-sample. The line's
        // ~1 s buffer (LINE_BUFFER_BYTES) is big enough that
        // bursting a few catch-up tics from the AnimationTimer's
        // multi-tick loop fits without blocking; once it does
        // back up, blocking briefly is preferable to silencing
        // the SFX.
        line.write(mixBufferI16, 0, mixBufferI16.length);
    }

    //
    // Music — s_sound.c → I_*Song.
    //
    // The C model: I_RegisterSong is called once per song, returns
    // a handle; subsequent I_PlaySong / I_StopSong / I_PauseSong /
    // I_ResumeSong / I_UnRegisterSong route by handle. The Java
    // port holds one Sequence at a time (Doom never plays two
    // songs concurrently) and stamps a monotonic handle so the
    // s_sound.c bookkeeping still matches.

    public static void iSetMusicVolume(int volume) {
        if (musicReceiver == null) return;
        // The menu drives this with snd_MusicVolume in [0, 15]
        // (m_menu.c's *8 scaling is commented out — the DMX driver
        // scaled internally). S_SetMusicVolume also calls us with
        // 127 first as a DMX reset workaround. Map menu values
        // through MUSIC_VOLUME_SCALE; pass anything already past
        // 15 through unchanged so the 127 reset still works.
        int cc = volume <= 15
                ? (volume * MUSIC_VOLUME_SCALE)
                : Math.min(volume, 127);
        try {
            for (int ch = 0; ch < 16; ch++) {
                ShortMessage m = new ShortMessage();
                m.setMessage(ShortMessage.CONTROL_CHANGE, ch, 7, cc);
                musicReceiver.send(m, -1);
            }
        } catch (javax.sound.midi.InvalidMidiDataException e) {
            // Best-effort — silently skip on a transient failure.
        }
    }

    public static void iPauseSong(int handle) {
        if (musicSequencer == null || currentMusicHandle != handle) return;
        if (musicSequencer.isRunning()) musicSequencer.stop();
    }

    public static void iResumeSong(int handle) {
        if (musicSequencer == null || currentMusicHandle != handle) return;
        if (!musicSequencer.isRunning()) musicSequencer.start();
    }

    /**
     * I_RegisterSong — convert a MUS lump to a Sequence and stash
     * it. {@code data} is the raw lump bytes (the C passes a
     * void* which the DMX driver reads as MUS). Returns a handle
     * the caller hands back to iPlaySong / iStopSong, or 0 if no
     * sequencer is available or the lump isn't valid MUS.
     */
    public static int iRegisterSong(Object data) {
        if (musicSequencer == null || !(data instanceof byte[])) return 0;
        try {
            byte[] midi = MusToMidi.convert((byte[]) data);
            if (midi == null) return 0;
            musicSequence = MidiSystem.getSequence(new ByteArrayInputStream(midi));
        } catch (Exception e) {
            return 0;
        }
        int h = musicHandleSeq++;
        if (musicHandleSeq <= 0) musicHandleSeq = 1;
        currentMusicHandle = h;
        return h;
    }

    public static void iPlaySong(int handle, int looping) {
        if (musicSequencer == null || musicSequence == null) return;
        if (currentMusicHandle != handle) return;
        try {
            musicSequencer.setSequence(musicSequence);
            musicSequencer.setLoopCount(looping != 0
                    ? Sequencer.LOOP_CONTINUOUSLY : 0);
            musicSequencer.setTickPosition(0);
            musicSequencer.start();
        } catch (javax.sound.midi.InvalidMidiDataException e) {
            // Sequence didn't decode after all — leave silent.
        }
    }

    public static void iStopSong(int handle) {
        if (musicSequencer == null || currentMusicHandle != handle) return;
        if (musicSequencer.isRunning()) musicSequencer.stop();
    }

    public static void iUnRegisterSong(int handle) {
        if (currentMusicHandle == handle) {
            musicSequence = null;
            currentMusicHandle = 0;
        }
    }

    //
    // DMX sound-effect lump decoder.
    //
    // i_sound.c implicitly via I_GetSfxLumpNum. The Doom WAD `DS*`
    // lump format is:
    //   short fmt;        // always 3
    //   short sampleRate; // typically 11025
    //   int   length;
    //   byte[length] samples;  // 8-bit unsigned, silence=128
    //
    // (Some sources document a 16-byte trailer; we ignore it.)
    public static final class DmxBlob {
        public final byte[] samples;
        public final int    rate;
        public DmxBlob(byte[] samples, int rate) {
            this.samples = samples;
            this.rate    = rate;
        }
    }

    public static DmxBlob decodeDmx(byte[] lump) {
        if (lump == null || lump.length < 8) return null;
        int rate   = (lump[2] & 0xff) | ((lump[3] & 0xff) << 8);
        int length = (lump[4] & 0xff)
                | ((lump[5] & 0xff) << 8)
                | ((lump[6] & 0xff) << 16)
                | ((lump[7] & 0xff) << 24);
        if (length <= 0 || length > lump.length - 8) {
            length = Math.max(0, lump.length - 8);
        }
        byte[] samples = new byte[length];
        System.arraycopy(lump, 8, samples, 0, length);
        return new DmxBlob(samples, rate);
    }

    // Map (vol, sep) → (leftvol, rightvol). Vol is 0..127, sep is
    // 0..255 with 128 = centred. Same balance curve as the C DMX
    // table (linear taper either side of centre).
    private static void applyVolumeSep(Channel c, int vol, int sep) {
        if (vol < 0)   vol = 0;
        // s_sound.c hands volume in the menu range [0, 15] (or up
        // to snd_SfxVolume which is also 0..15). The mixer's
        // per-sample multiply is `sample * leftvol >> 7`, which
        // gives full output when leftvol ≈ 128. Scale via
        // SFX_VOLUME_SCALE balances SFX against MIDI music. ×28
        // is approximately +10 dB above the original ×9 (10 dB ≈
        // ×3.16 in amplitude; 9 × 3.16 ≈ 28.4, rounded down).
        if (vol > 15)  vol = 15;
        if (sep < 0)   sep = 0;
        if (sep > 255) sep = 255;
        int scaled = vol * SFX_VOLUME_SCALE;  // [0, 15] → [0, 420]
        // Left = full at sep=0, zero at sep=255.
        c.leftvol  = (scaled * (255 - sep)) / 255;
        c.rightvol = (scaled *  sep)        / 255;
    }

    private static short clampShort(int v) {
        if (v >  32767) return  32767;
        if (v < -32768) return -32768;
        return (short) v;
    }

    // Silence unused-import warning for ISystem (referenced via
    // exceptions only in future error paths).
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_ISYSTEM = ISystem.class;
}
