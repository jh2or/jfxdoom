// Port of linuxdoom-1.10/s_sound.c — stub.
//
// s_sound.c is the ~880-line sound engine wrapper: S_Init, S_Start,
// S_StartSound (and -AtVolume), S_StopSound, S_UpdateSounds,
// S_ChangeMusic, ... plus an internal 8-channel mixer bookkeeping
// (`channels[NUM_CHANNELS]`), listener-distance attenuation, and
// stereo separation based on mobj angle. The whole thing sits on top
// of i_sound's platform dispatcher, which is itself unported.
//
// For now this stub carries only S_StartSound as a no-op so the
// ported plane movers (PCeilng, PPlats, PDoors, ...) can call it the
// same way the C does. `origin` is typed `Object` because the C
// passes either a mobj_t* or a sector's soundorg (degenmobj_t*) —
// both carry the x/y/z prefix the C listener-distance code expects,
// but Java has no inheritance between Mobj and Degenmobj today. When
// the full S_StartSound lands it will swap to a typed listener-origin
// interface; call sites stay identical.
package doom.sound;

public final class SSound {

    // s_sound.c — sound + music volumes (0..15). The C menu reads
    // and writes these directly; the real mixer (deferred) consumes
    // them.
    public static int snd_SfxVolume   = 8;
    public static int snd_MusicVolume = 8;

    // s_sound.c — S_SetSfxVolume / S_SetMusicVolume. Stubs until
    // the audio backend lands.
    public static void sSetSfxVolume(int vol) {
        snd_SfxVolume = vol;
    }

    public static void sSetMusicVolume(int vol) {
        snd_MusicVolume = vol;
    }

    // s_sound.c lines 331–... — S_StartSound(origin, sfx_id).
    // Stub: no-op. The faithful implementation needs i_sound + the
    // channel mixer + attenuation math, none of which are ported.
    public static void sStartSound(Object origin, int sfxId) {
        // intentional no-op
    }

    // s_sound.c — S_ChangeMusic(musicnum, looping). Used by the
    // ST_Responder "mus" cheat. Stub until the music pump lands.
    public static void sChangeMusic(int musicnum, int looping) {
        // intentional no-op
    }

    // s_sound.c — S_StartMusic(musicnum). Used by F_Ticker's E3
    // bunny-scroll music kick-in. Stub for now.
    public static void sStartMusic(int musicnum) {
        // intentional no-op
    }

    private SSound() {
    }
}
