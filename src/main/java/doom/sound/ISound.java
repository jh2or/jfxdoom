// Port of linuxdoom-1.10/i_sound.c — stub.
//
// i_sound.c is the platform-audio backend: WAD lump → 11/22 kHz PCM
// for SFX, MUS → MIDI for music, an 8-channel software mixer, and
// the sound-card setup hooks. The full port lands once the JavaFX
// audio driver (javax.sound.sampled.SourceDataLine + javax.sound.midi.
// Sequencer) is wired in.
//
// For now this stub carries the init / shutdown entry points so
// I_Init / I_Quit can dispatch through it without crashing, plus
// the per-frame I_UpdateSound / I_SubmitSound hooks that the C's
// frame loop expects (both no-ops).
package doom.sound;

public final class ISound {

    // i_sound.c — I_InitSound. Stub: opens the platform audio
    // device. Full body lands with the JavaFX audio backend.
    public static void iInitSound() {
        // intentionally empty
    }

    // i_sound.c — I_ShutdownSound.
    public static void iShutdownSound() {
        // intentionally empty
    }

    // i_sound.c — I_InitMusic. The C's MUS-to-MIDI path lives here.
    public static void iInitMusic() {
        // intentionally empty
    }

    // i_sound.c — I_ShutdownMusic.
    public static void iShutdownMusic() {
        // intentionally empty
    }

    // i_sound.c — I_UpdateSound + I_SubmitSound. Per-frame mixer
    // pump that the engine calls between game ticks. No-op until
    // the audio backend lands.
    public static void iUpdateSound() {
        // intentionally empty
    }

    public static void iSubmitSound() {
        // intentionally empty
    }

    private ISound() {
    }
}
