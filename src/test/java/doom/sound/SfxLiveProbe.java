package doom.sound;

import doom.system.DMain;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Diagnostic: boot the engine with a real WAD and trigger a
 * sound effect through the normal sStartSound → iStartSound
 * channel allocation path. Report each step's outcome so we
 * can see where the SFX path breaks.
 *
 * Armed via `-De1m1.probe=1`.
 */
class SfxLiveProbe {

    private static final Path WAD_PATH = Path.of("reference-wads/DOOM.WAD");

    @Test
    void fireSfxAndReportState() throws IOException {
        if (!"1".equals(System.getProperty("e1m1.probe"))) return;

        DMain.dDoomMain(new String[] {
                "-iwad", WAD_PATH.toString(),
                "-warp", "1", "1",
                "-nomonsters" });

        System.err.printf(
                "ISound state: snd_SfxVolume=%d, channels[0].handle=%d, " +
                "line null? %b%n",
                SSound.snd_SfxVolume,
                ISound.channels[0].handle,
                ISound.class.getDeclaredFields()[0] == null);

        // Fire the pistol sound directly. Skip the playsim and
        // call S_StartSound the same way PPspr would.
        SSound.sStartSound(null, Sounds.sfx_pistol);

        // Walk every channel — was anything actually allocated?
        int active = 0;
        for (int i = 0; i < ISound.NUM_CHANNELS; i++) {
            if (ISound.channels[i].handle != 0) {
                active++;
                ISound.Channel c = ISound.channels[i];
                System.err.printf(
                        "channel %d: handle=%d data.len=%d cursor=%.1f " +
                        "rate=%d L=%d R=%d%n",
                        i, c.handle,
                        c.data == null ? -1 : c.data.length,
                        c.cursor, c.sampleRate, c.leftvol, c.rightvol);
            }
        }
        System.err.printf("active channels: %d%n", active);

        // What does iSubmitSound see? Run a few iterations.
        for (int i = 0; i < 10; i++) {
            ISound.iSubmitSound();
        }
        boolean anyActive = false;
        for (int i = 0; i < ISound.NUM_CHANNELS; i++) {
            if (ISound.channels[i].handle != 0) anyActive = true;
        }
        System.err.printf("after 10 iSubmitSound: anyActive=%b%n", anyActive);

        // Look up the pistol sfx struct.
        Sounds.Sfxinfo pistol = Sounds.S_sfx[Sounds.sfx_pistol];
        System.err.printf(
                "sfx_pistol: lumpnum=%d data=%s name=%s%n",
                pistol.lumpnum,
                pistol.data == null ? "null" : pistol.data.getClass().getSimpleName(),
                pistol.name);
    }
}
