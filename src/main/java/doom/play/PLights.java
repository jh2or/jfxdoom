// Port of linuxdoom-1.10/p_lights.c — sector-base lighting thinkers.
//
// Five light-animation effects, each backed by a thinker_t subtype:
// fire flicker, broken-light flash, strobe flash, glowing light,
// plus two linedef-triggered effects (EV_TurnTagLightsOff,
// EV_LightTurnOn) that scan the world at trigger time. Every
// per-tic effect mutates `sector->lightlevel` in place.
//
// Nested subtypes mirror the C struct layouts one-for-one: each
// starts with thinker_t (via inheritance) plus a sector pointer and
// a handful of int counters.
package doom.play;

import doom.render.Line;
import doom.render.Sector;
public final class PLights {

    // p_spec.h lines 124–173 — the four thinker subtypes.
    // Each inherits the thinker_t fields (prev/next/function/removed)
    // from Thinker; the additional fields are the ones the C listed
    // after `thinker_t thinker;`.

    public static final class FireFlicker extends Thinker {
        public Sector sector;
        public int count;
        public int maxlight;
        public int minlight;
    }

    public static final class LightFlash extends Thinker {
        public Sector sector;
        public int count;
        public int maxlight;
        public int minlight;
        public int maxtime;
        public int mintime;
    }

    public static final class Strobe extends Thinker {
        public Sector sector;
        public int count;
        public int minlight;
        public int maxlight;
        public int darktime;
        public int brighttime;
    }

    public static final class Glow extends Thinker {
        public Sector sector;
        public int minlight;
        public int maxlight;
        public int direction;
    }

    // p_spec.h lines 176–179 — timing constants.
    public static final int GLOWSPEED   = 8;
    public static final int STROBEBRIGHT = 5;
    public static final int FASTDARK    = 15;
    public static final int SLOWDARK    = 35;

    // p_lights.c lines 46–61 — T_FireFlicker.
    // The C signature is `void T_FireFlicker(fireflicker_t*)`; the
    // thinker call convention passes a `thinker_t*` which we cast
    // back (same pattern the C uses via `(actionf_p1) T_FireFlicker`).
    public static void tFireFlicker(Thinker t) {
        FireFlicker flick = (FireFlicker) t;

        if (--flick.count != 0) {
            return;
        }

        int amount = (MRandom.pRandom() & 3) * 16;

        if (flick.sector.lightlevel - amount < flick.minlight) {
            flick.sector.lightlevel = flick.minlight;
        } else {
            flick.sector.lightlevel = flick.maxlight - amount;
        }

        flick.count = 4;
    }

    // p_lights.c lines 68–85 — P_SpawnFireFlicker.
    public static void pSpawnFireFlicker(Sector sector) {
        // Note that we are resetting sector attributes.
        // Nothing special about it during gameplay.
        sector.special = 0;

        // C: flick = Z_Malloc(sizeof(*flick), PU_LEVSPEC, 0);
        // Extended thinkers are Java objects, not zone-tracked byte
        // buffers — the GC reclaims them once P_RunThinkers unlinks a
        // removed thinker (see deviation 14). So we allocate directly
        // and skip the zone call.
        FireFlicker flick = new FireFlicker();
        PTick.pAddThinker(flick);

        flick.function.acp1 = PLights::tFireFlicker;
        flick.sector = sector;
        flick.maxlight = sector.lightlevel;
        flick.minlight = PSpec.pFindMinSurroundingLight(sector, sector.lightlevel) + 16;
        flick.count = 4;
    }

    // p_lights.c lines 98–114 — T_LightFlash.
    public static void tLightFlash(Thinker t) {
        LightFlash flash = (LightFlash) t;

        if (--flash.count != 0) {
            return;
        }

        if (flash.sector.lightlevel == flash.maxlight) {
            flash.sector.lightlevel = flash.minlight;
            flash.count = (MRandom.pRandom() & flash.mintime) + 1;
        } else {
            flash.sector.lightlevel = flash.maxlight;
            flash.count = (MRandom.pRandom() & flash.maxtime) + 1;
        }
    }

    // p_lights.c lines 124–143 — P_SpawnLightFlash.
    public static void pSpawnLightFlash(Sector sector) {
        // nothing special about it during gameplay
        sector.special = 0;

        LightFlash flash = new LightFlash();
        PTick.pAddThinker(flash);

        flash.function.acp1 = PLights::tLightFlash;
        flash.sector = sector;
        flash.maxlight = sector.lightlevel;

        flash.minlight = PSpec.pFindMinSurroundingLight(sector, sector.lightlevel);
        flash.maxtime = 64;
        flash.mintime = 7;
        flash.count = (MRandom.pRandom() & flash.maxtime) + 1;
    }

    // p_lights.c lines 155–171 — T_StrobeFlash.
    public static void tStrobeFlash(Thinker t) {
        Strobe flash = (Strobe) t;

        if (--flash.count != 0) {
            return;
        }

        if (flash.sector.lightlevel == flash.minlight) {
            flash.sector.lightlevel = flash.maxlight;
            flash.count = flash.brighttime;
        } else {
            flash.sector.lightlevel = flash.minlight;
            flash.count = flash.darktime;
        }
    }

    // p_lights.c lines 180–209 — P_SpawnStrobeFlash.
    public static void pSpawnStrobeFlash(Sector sector, int fastOrSlow, int inSync) {
        Strobe flash = new Strobe();
        PTick.pAddThinker(flash);

        flash.sector = sector;
        flash.darktime = fastOrSlow;
        flash.brighttime = STROBEBRIGHT;
        flash.function.acp1 = PLights::tStrobeFlash;
        flash.maxlight = sector.lightlevel;
        flash.minlight = PSpec.pFindMinSurroundingLight(sector, sector.lightlevel);

        if (flash.minlight == flash.maxlight) {
            flash.minlight = 0;
        }

        // nothing special about it during gameplay
        sector.special = 0;

        if (inSync == 0) {
            flash.count = (MRandom.pRandom() & 7) + 1;
        } else {
            flash.count = 1;
        }
    }

    // p_lights.c lines 215–229 — EV_StartLightStrobing.
    public static void evStartLightStrobing(Line line) {
        int secnum = -1;
        while ((secnum = PSpec.pFindSectorFromLineTag(line, secnum)) >= 0) {
            Sector sec = PSetup.sectors[secnum];
            if (sec.specialdata != null) {
                continue;
            }

            pSpawnStrobeFlash(sec, SLOWDARK, 0);
        }
    }

    // p_lights.c lines 236–264 — EV_TurnTagLightsOff.
    public static void evTurnTagLightsOff(Line line) {
        // The C walks `sector = sectors; for (... j < numsectors; j++, sector++)`.
        // Translate the pointer-walk as an index.
        for (int j = 0; j < PSetup.numsectors; j++) {
            Sector sector = PSetup.sectors[j];
            if (sector.tag == line.tag) {
                int min = sector.lightlevel;
                for (int i = 0; i < sector.linecount; i++) {
                    Line templine = sector.lines[i];
                    Sector tsec = PSpec.getNextSector(templine, sector);
                    if (tsec == null) {
                        continue;
                    }
                    if (tsec.lightlevel < min) {
                        min = tsec.lightlevel;
                    }
                }
                sector.lightlevel = min;
            }
        }
    }

    // p_lights.c lines 270–307 — EV_LightTurnOn.
    public static void evLightTurnOn(Line line, int bright) {
        for (int i = 0; i < PSetup.numsectors; i++) {
            Sector sector = PSetup.sectors[i];
            if (sector.tag == line.tag) {
                // bright = 0 means to search
                // for highest light level
                // surrounding sector
                if (bright == 0) {
                    for (int j = 0; j < sector.linecount; j++) {
                        Line templine = sector.lines[j];
                        Sector temp = PSpec.getNextSector(templine, sector);

                        if (temp == null) {
                            continue;
                        }

                        if (temp.lightlevel > bright) {
                            bright = temp.lightlevel;
                        }
                    }
                }
                sector.lightlevel = bright;
            }
        }
    }

    // p_lights.c lines 314–338 — T_Glow.
    public static void tGlow(Thinker t) {
        Glow g = (Glow) t;

        switch (g.direction) {
            case -1 -> {
                // DOWN
                g.sector.lightlevel -= GLOWSPEED;
                if (g.sector.lightlevel <= g.minlight) {
                    g.sector.lightlevel += GLOWSPEED;
                    g.direction = 1;
                }
            }
            case 1 -> {
                // UP
                g.sector.lightlevel += GLOWSPEED;
                if (g.sector.lightlevel >= g.maxlight) {
                    g.sector.lightlevel -= GLOWSPEED;
                    g.direction = -1;
                }
            }
            default -> { /* ignore */ }
        }
    }

    // p_lights.c lines 341–356 — P_SpawnGlowingLight.
    public static void pSpawnGlowingLight(Sector sector) {
        Glow g = new Glow();
        PTick.pAddThinker(g);

        g.sector = sector;
        g.minlight = PSpec.pFindMinSurroundingLight(sector, sector.lightlevel);
        g.maxlight = sector.lightlevel;
        g.function.acp1 = PLights::tGlow;
        g.direction = -1;

        sector.special = 0;
    }

    private PLights() {
    }
}
