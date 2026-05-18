// Port of linuxdoom-1.10/s_sound.c — full mixer wrapper.
//
// Sits above ISound (the platform audio backend) and provides:
//   - 16-channel allocation (sGetChannel) with priority kick-out
//   - Distance attenuation + stereo separation
//     (sAdjustSoundParams, based on the consoleplayer's mobj)
//   - Per-sfx pitch perturbation (saw weapons + bonus tinks)
//   - Music change/pause/resume dispatch
//   - Per-tic sUpdateSounds re-check of channel audibility
//
// The C uses a `void* origin` because the listener can be either a
// mobj_t* or a degenmobj_t* (a sector's soundorg trio of x/y/z).
// Java doesn't share a base class between Mobj and Degenmobj, so
// the port uses Object and downcasts via instanceof.
package doom.sound;

import doom.fixed.Fixed;
import doom.play.MRandom;
import doom.play.Mobj;
import doom.render.RMain;
import doom.render.Tables;
import doom.system.Doomstat;
import doom.system.GGame;
import doom.system.ISystem;

public final class SSound {

    // s_sound.c lines 51–80.
    public static final int S_MAX_VOLUME      = 127;
    public static final int S_CLIPPING_DIST   = 1200 * 0x10000;
    public static final int S_CLOSE_DIST      = 160  * 0x10000;
    public static final int S_ATTENUATOR      = (S_CLIPPING_DIST - S_CLOSE_DIST) >> Fixed.FRACBITS;
    public static final int NORM_PITCH        = 128;
    public static final int NORM_PRIORITY     = 64;
    public static final int NORM_SEP          = 128;
    public static final int S_PITCH_PERTURB   = 1;
    public static final int S_STEREO_SWING    = 96 * 0x10000;
    public static final int S_IFRACVOL        = 30;

    // s_sound.c — sound + music volumes (0..127 internally; menu
    // exposes 0..15).
    public static int snd_SfxVolume   = 15;
    public static int snd_MusicVolume = 15;

    // s_sound.c lines 93–104 — channel_t.
    public static final class Channel {
        public Sounds.Sfxinfo sfxinfo;  // null when slot is free
        public Object         origin;
        public int            handle;
    }

    // The 16 mixer channels (numChannels in the C). The C makes
    // this configurable via M_Misc defaults; Java pins it to the
    // ISound NUM_CHANNELS.
    public static final int numChannels = ISound.NUM_CHANNELS;
    public static final Channel[] channels = new Channel[numChannels];
    static {
        for (int i = 0; i < numChannels; i++) channels[i] = new Channel();
    }

    private static boolean mus_paused;
    private static Sounds.Musicinfo mus_playing;

    private SSound() {
    }

    //
    // S_Init
    // s_sound.c lines 161–192.
    public static void sInit(int sfxVolume, int musicVolume) {
        ISound.iSetChannels();
        sSetSfxVolume(sfxVolume);
        sSetMusicVolume(musicVolume);
        for (int i = 0; i < numChannels; i++) {
            channels[i].sfxinfo = null;
        }
        mus_paused = false;
        // Mark each sfx as not-yet-cached.
        for (int i = 1; i < Sounds.S_sfx.length; i++) {
            Sounds.S_sfx[i].lumpnum = -1;
            Sounds.S_sfx[i].usefulness = -1;
        }
    }

    //
    // S_Start
    // s_sound.c lines 202–248. Per-level: kill all playing sounds,
    // change to the per-map music.
    public static void sStart() {
        for (int cnum = 0; cnum < numChannels; cnum++) {
            if (channels[cnum].sfxinfo != null) {
                sStopChannel(cnum);
            }
        }
        mus_paused = false;

        int mnum;
        if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
            mnum = Sounds.mus_runnin + Doomstat.gamemap - 1;
        } else {
            // E4's Doom 2 -style picks per-map music from a small
            // hand-curated table — see s_sound.c lines 219–233.
            int[] spmus = {
                    Sounds.mus_e3m4, Sounds.mus_e3m2, Sounds.mus_e3m3,
                    Sounds.mus_e1m5, Sounds.mus_e2m7, Sounds.mus_e2m4,
                    Sounds.mus_e2m6, Sounds.mus_e2m5, Sounds.mus_e1m9,
            };
            if (Doomstat.gameepisode < 4) {
                mnum = Sounds.mus_e1m1
                        + (Doomstat.gameepisode - 1) * 9
                        + Doomstat.gamemap - 1;
            } else {
                mnum = spmus[Doomstat.gamemap - 1];
            }
        }

        sChangeMusic(mnum, 1);
    }

    //
    // S_StartSoundAtVolume
    // s_sound.c lines 254–395.
    public static void sStartSoundAtVolume(Object origin_p, int sfx_id, int volume) {
        if (sfx_id < 1 || sfx_id >= Sounds.S_sfx.length) {
            throw ISystem.iError("Bad sfx #: %d", sfx_id);
        }
        Sounds.Sfxinfo sfx = Sounds.S_sfx[sfx_id];

        int pitch;
        int priority;
        int sep;

        // sfx->link tweaks (e.g. CHGUN reuses pistol).
        if (sfx.link != null) {
            pitch    = sfx.pitch;
            priority = sfx.priority;
            volume  += sfx.volume;
            if (volume < 1) return;
            if (volume > snd_SfxVolume) volume = snd_SfxVolume;
        } else {
            pitch    = NORM_PITCH;
            priority = NORM_PRIORITY;
        }

        // Distance + stereo attenuation if the source isn't the
        // listener.
        Mobj listener = GGame.players[GGame.consoleplayer].mo;
        int[] volRef = { volume };
        int[] sepRef = { 0 };
        int[] pitRef = { pitch };
        if (origin_p != null && origin_p != listener) {
            int rc = sAdjustSoundParams(listener, origin_p, volRef, sepRef, pitRef);
            // If listener is on top of the source, flatten the pan.
            int sx = originX(origin_p);
            int sy = originY(origin_p);
            if (listener != null && sx == listener.x && sy == listener.y) {
                sepRef[0] = NORM_SEP;
            }
            if (rc == 0) return;
            volume = volRef[0];
            pitch  = pitRef[0];
            sep    = sepRef[0];
        } else {
            sep = NORM_SEP;
        }

        // pitch perturbation
        if (sfx_id >= Sounds.sfx_sawup && sfx_id <= Sounds.sfx_sawhit) {
            pitch += 8 - (MRandom.mRandom() & 15);
        } else if (sfx_id != Sounds.sfx_itemup && sfx_id != Sounds.sfx_tink) {
            pitch += 16 - (MRandom.mRandom() & 31);
        }
        if (pitch < 0)   pitch = 0;
        if (pitch > 255) pitch = 255;

        // kill old sound on this origin + grab a channel
        sStopSound(origin_p);
        int cnum = sGetChannel(origin_p, sfx);
        if (cnum < 0) return;

        if (sfx.lumpnum < 0) sfx.lumpnum = ISound.iGetSfxLumpNum(sfx);
        if (sfx.usefulness++ < 0) sfx.usefulness = 1;

        channels[cnum].handle = ISound.iStartSound(sfx_id, sfx,
                volume, sep, pitch, priority);
    }

    //
    // S_StartSound
    // s_sound.c line 397.
    public static void sStartSound(Object origin, int sfx_id) {
        sStartSoundAtVolume(origin, sfx_id, snd_SfxVolume);
    }

    //
    // S_StopSound
    // s_sound.c lines 471–484.
    public static void sStopSound(Object origin) {
        for (int cnum = 0; cnum < numChannels; cnum++) {
            if (channels[cnum].sfxinfo != null
                    && channels[cnum].origin == origin) {
                sStopChannel(cnum);
                return;
            }
        }
    }

    //
    // S_PauseSound / S_ResumeSound
    // s_sound.c lines 497–513.
    public static void sPauseSound() {
        if (mus_playing != null && !mus_paused) {
            ISound.iPauseSong(mus_playing.handle);
            mus_paused = true;
        }
    }

    public static void sResumeSound() {
        if (mus_playing != null && mus_paused) {
            ISound.iResumeSong(mus_playing.handle);
            mus_paused = false;
        }
    }

    //
    // S_UpdateSounds
    // s_sound.c lines 519–613. Per-tic re-check of channel
    // audibility + pan / volume refresh.
    public static void sUpdateSounds(Object listener_p) {
        Mobj listener = (listener_p instanceof Mobj) ? (Mobj) listener_p : null;

        for (int cnum = 0; cnum < numChannels; cnum++) {
            Channel c = channels[cnum];
            Sounds.Sfxinfo sfx = c.sfxinfo;
            if (sfx == null) continue;

            if (ISound.iSoundIsPlaying(c.handle) != 0) {
                int volume = snd_SfxVolume;
                int pitch  = NORM_PITCH;
                int sep    = NORM_SEP;

                if (sfx.link != null) {
                    pitch  = sfx.pitch;
                    volume += sfx.volume;
                    if (volume < 1) {
                        sStopChannel(cnum);
                        continue;
                    } else if (volume > snd_SfxVolume) {
                        volume = snd_SfxVolume;
                    }
                }

                // Re-attenuate non-local sounds.
                if (c.origin != null && listener_p != c.origin) {
                    int[] volRef = { volume };
                    int[] sepRef = { sep };
                    int[] pitRef = { pitch };
                    int audible = sAdjustSoundParams(listener, c.origin,
                            volRef, sepRef, pitRef);
                    if (audible == 0) {
                        sStopChannel(cnum);
                    } else {
                        ISound.iUpdateSoundParams(c.handle, volRef[0],
                                sepRef[0], pitRef[0]);
                    }
                }
            } else {
                // Channel allocated but the underlying sound stopped
                // — free the slot.
                sStopChannel(cnum);
            }
        }
    }

    //
    // S_SetMusicVolume / S_SetSfxVolume
    // s_sound.c lines 616–639.
    public static void sSetMusicVolume(int volume) {
        if (volume < 0 || volume > 127) {
            throw ISystem.iError("Attempt to set music volume at %d", volume);
        }
        ISound.iSetMusicVolume(127);
        ISound.iSetMusicVolume(volume);
        snd_MusicVolume = volume;
    }

    public static void sSetSfxVolume(int volume) {
        if (volume < 0 || volume > 127) {
            throw ISystem.iError("Attempt to set sfx volume at %d", volume);
        }
        snd_SfxVolume = volume;
    }

    //
    // S_StartMusic / S_ChangeMusic / S_StopMusic
    // s_sound.c lines 644–703.
    public static void sStartMusic(int m_id) {
        sChangeMusic(m_id, 0);
    }

    public static void sChangeMusic(int musicnum, int looping) {
        if (musicnum <= Sounds.mus_None || musicnum >= Sounds.S_music.length) {
            throw ISystem.iError("Bad music number %d", musicnum);
        }
        Sounds.Musicinfo music = Sounds.S_music[musicnum];
        if (mus_playing == music) return;

        sStopMusic();

        // get lumpnum if necessary
        if (music.lumpnum == 0) {
            // best-effort lookup; if the WAD doesn't ship the music
            // lump (e.g. SHAREWARE without D_E2M*), silently skip.
            int lump = doom.wad.WWad.wCheckNumForName("d_" + music.name);
            if (lump < 0) return;
            music.lumpnum = lump;
        }

        try {
            music.data = doom.wad.WWad.wCacheLumpNum(music.lumpnum,
                    doom.wad.ZZone.PU_MUSIC);
        } catch (java.io.IOException e) {
            return;
        }
        music.handle = ISound.iRegisterSong(music.data);
        ISound.iPlaySong(music.handle, looping);
        mus_playing = music;
    }

    public static void sStopMusic() {
        if (mus_playing != null) {
            if (mus_paused) ISound.iResumeSong(mus_playing.handle);
            ISound.iStopSong(mus_playing.handle);
            ISound.iUnRegisterSong(mus_playing.handle);
            mus_playing.data = null;
            mus_playing = null;
        }
    }

    //
    // S_StopChannel
    // s_sound.c lines 708–742.
    public static void sStopChannel(int cnum) {
        Channel c = channels[cnum];
        if (c.sfxinfo != null) {
            if (ISound.iSoundIsPlaying(c.handle) != 0) {
                ISound.iStopSound(c.handle);
            }
            c.sfxinfo.usefulness--;
            c.sfxinfo = null;
        }
    }

    //
    // S_AdjustSoundParams
    // s_sound.c lines 752–818. Returns 1 if the source is still
    // audible (and writes back vol/sep/pitch), 0 if it should be
    // clipped.
    public static int sAdjustSoundParams(Mobj listener, Object source_p,
                                         int[] vol, int[] sep, int[] pitch) {
        if (listener == null) return 1;  // no listener → leave params untouched

        int sx = originX(source_p);
        int sy = originY(source_p);

        int adx = Math.abs(listener.x - sx);
        int ady = Math.abs(listener.y - sy);
        // Approximate Euclidean distance (Graphics Gems).
        int approx_dist = adx + ady - ((adx < ady ? adx : ady) >> 1);

        if (Doomstat.gamemap != 8 && approx_dist > S_CLIPPING_DIST) {
            return 0;
        }

        int angle = RMain.rPointToAngle2(listener.x, listener.y, sx, sy);
        // angle_t arithmetic is unsigned 32-bit in C.
        if (Integer.compareUnsigned(angle, listener.angle) > 0) {
            angle = angle - listener.angle;
        } else {
            angle = angle + (0xffffffff - listener.angle);
        }
        angle >>>= Tables.ANGLETOFINESHIFT;

        sep[0] = 128 - (Fixed.mul(S_STEREO_SWING, Tables.finesine[angle]) >> Fixed.FRACBITS);

        if (approx_dist < S_CLOSE_DIST) {
            vol[0] = snd_SfxVolume;
        } else if (Doomstat.gamemap == 8) {
            if (approx_dist > S_CLIPPING_DIST) approx_dist = S_CLIPPING_DIST;
            vol[0] = 15 + ((snd_SfxVolume - 15)
                    * ((S_CLIPPING_DIST - approx_dist) >> Fixed.FRACBITS))
                    / S_ATTENUATOR;
        } else {
            vol[0] = (snd_SfxVolume
                    * ((S_CLIPPING_DIST - approx_dist) >> Fixed.FRACBITS))
                    / S_ATTENUATOR;
        }

        return vol[0] > 0 ? 1 : 0;
    }

    //
    // S_getChannel
    // s_sound.c lines 827–875.
    public static int sGetChannel(Object origin, Sounds.Sfxinfo sfxinfo) {
        int cnum;
        for (cnum = 0; cnum < numChannels; cnum++) {
            if (channels[cnum].sfxinfo == null) break;
            if (origin != null && channels[cnum].origin == origin) {
                sStopChannel(cnum);
                break;
            }
        }
        if (cnum == numChannels) {
            // Look for a lower priority slot.
            for (cnum = 0; cnum < numChannels; cnum++) {
                if (channels[cnum].sfxinfo.priority >= sfxinfo.priority) {
                    break;
                }
            }
            if (cnum == numChannels) return -1;
            sStopChannel(cnum);
        }
        Channel c = channels[cnum];
        c.sfxinfo = sfxinfo;
        c.origin  = origin;
        return cnum;
    }

    // Sound-origin x/y getters that handle both Mobj and Degenmobj
    // (sector soundorg).
    private static int originX(Object o) {
        if (o instanceof Mobj)       return ((Mobj) o).x;
        if (o instanceof doom.render.Degenmobj)  return ((doom.render.Degenmobj) o).x;
        return 0;
    }

    private static int originY(Object o) {
        if (o instanceof Mobj)       return ((Mobj) o).y;
        if (o instanceof doom.render.Degenmobj)  return ((doom.render.Degenmobj) o).y;
        return 0;
    }
}
