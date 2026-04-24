// Port of linuxdoom-1.10/p_spec.c — partial.
//
// p_spec.c is the 1300-line "sector specials" module: it handles
// animated textures, line-triggered events (teleport, lights, doors,
// floors, ...), button timers, and per-tic special updates. The full
// body lands incrementally alongside its companion files (p_doors,
// p_floor, p_plats, ...). For now this carries only the tiny
// sector-graph walkers other ported modules need — p_lights.c spawn
// helpers in particular.
package doom.play;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.RData;
import doom.render.Sector;
import doom.sound.SSound;
import doom.sound.Sounds;
import doom.system.ISystem;
import doom.wad.WWad;

public final class PSpec {

    // p_spec.h lines 602–608 — result_e.
    // Return code for T_MovePlane and its callers. `ok` = move
    // completed normally, `crushed` = move blocked by a mobj, `pastdest`
    // = destination reached this tic.
    public enum Result { ok, crushed, pastdest }

    // p_spec.h line 600 — FLOORSPEED.
    public static final int FLOORSPEED = Fixed.FRACUNIT;

    // p_spec.c lines 250–262 — getNextSector.
    // Return the sector on the other side of a two-sided line.
    // Null for one-sided lines (ML_TWOSIDED not set).
    public static Sector getNextSector(Line line, Sector sec) {
        if ((line.flags & Line.ML_TWOSIDED) == 0) {
            return null;
        }

        if (line.frontsector == sec) {
            return line.backsector;
        }

        return line.frontsector;
    }

    // p_spec.c lines 434–445 — P_FindSectorFromLineTag.
    // Returns the next sector index in `sectors[]` whose tag matches
    // line->tag, starting the search at start+1. Returns -1 when no
    // more matches remain.
    public static int pFindSectorFromLineTag(Line line, int start) {
        for (int i = start + 1; i < PSetup.numsectors; i++) {
            if (PSetup.sectors[i].tag == line.tag) {
                return i;
            }
        }

        return -1;
    }

    // p_spec.c lines 454–476 — P_FindMinSurroundingLight.
    // Minimum light level across `sector`'s adjacent sectors, or
    // `max` if none of them are darker.
    public static int pFindMinSurroundingLight(Sector sector, int max) {
        int min = max;
        for (int i = 0; i < sector.linecount; i++) {
            Line line = sector.lines[i];
            Sector check = getNextSector(line, sector);

            if (check == null) {
                continue;
            }

            if (check.lightlevel < min) {
                min = check.lightlevel;
            }
        }
        return min;
    }

    // p_spec.c lines 270–289 — P_FindLowestFloorSurrounding.
    // Lowest floor height among two-sided neighbours, seeded with
    // `sec`'s own floor — so the result is at most `sec->floorheight`.
    public static int pFindLowestFloorSurrounding(Sector sec) {
        int floor = sec.floorheight;
        for (int i = 0; i < sec.linecount; i++) {
            Line check = sec.lines[i];
            Sector other = getNextSector(check, sec);

            if (other == null) {
                continue;
            }

            if (other.floorheight < floor) {
                floor = other.floorheight;
            }
        }
        return floor;
    }

    // p_spec.c lines 297–316 — P_FindHighestFloorSurrounding.
    // Highest floor height among two-sided neighbours. The seed is
    // -500 * FRACUNIT (well below any legal map floor), so isolated
    // sectors report that sentinel back to the caller. Callers
    // (e.g. EV_DoFloor lowerFloor) treat the sentinel specially.
    public static int pFindHighestFloorSurrounding(Sector sec) {
        int floor = -500 * Fixed.FRACUNIT;
        for (int i = 0; i < sec.linecount; i++) {
            Line check = sec.lines[i];
            Sector other = getNextSector(check, sec);

            if (other == null) {
                continue;
            }

            if (other.floorheight > floor) {
                floor = other.floorheight;
            }
        }
        return floor;
    }

    // p_spec.c line 326 — MAX_ADJOINING_SECTORS.
    public static final int MAX_ADJOINING_SECTORS = 20;

    // p_spec.c lines 328–375 — P_FindNextHighestFloor.
    // Next floor height strictly above `currentheight` among
    // two-sided neighbours. The C collects candidates into a local
    // fixed array, caps at MAX_ADJOINING_SECTORS with a stderr
    // warning, and returns the min of the list (or `currentheight`
    // when empty — note the C returns the unchanged seed, not -1).
    public static int pFindNextHighestFloor(Sector sec, int currentheight) {
        int[] heightlist = new int[MAX_ADJOINING_SECTORS];
        int h = 0;

        for (int i = 0; i < sec.linecount; i++) {
            Line check = sec.lines[i];
            Sector other = getNextSector(check, sec);

            if (other == null) {
                continue;
            }

            if (other.floorheight > currentheight) {
                heightlist[h++] = other.floorheight;
            }

            // Check for overflow. Exit.
            if (h >= MAX_ADJOINING_SECTORS) {
                System.err.println("Sector with more than 20 adjoining sectors");
                break;
            }
        }

        // Find lowest height in list
        if (h == 0) {
            return currentheight;
        }

        int min = heightlist[0];

        // Range checking?
        for (int i = 1; i < h; i++) {
            if (heightlist[i] < min) {
                min = heightlist[i];
            }
        }

        return min;
    }

    // p_spec.c lines 381–401 — P_FindLowestCeilingSurrounding.
    // Lowest ceiling among two-sided neighbours. Seeded with MAXINT
    // so that isolated sectors return the sentinel — callers check.
    public static int pFindLowestCeilingSurrounding(Sector sec) {
        int height = Integer.MAX_VALUE;
        for (int i = 0; i < sec.linecount; i++) {
            Line check = sec.lines[i];
            Sector other = getNextSector(check, sec);

            if (other == null) {
                continue;
            }

            if (other.ceilingheight < height) {
                height = other.ceilingheight;
            }
        }
        return height;
    }

    // p_spec.c lines 407–426 — P_FindHighestCeilingSurrounding.
    // Highest ceiling among two-sided neighbours. Seeded with 0 —
    // isolated sectors return 0 (floor of the map).
    public static int pFindHighestCeilingSurrounding(Sector sec) {
        int height = 0;
        for (int i = 0; i < sec.linecount; i++) {
            Line check = sec.lines[i];
            Sector other = getNextSector(check, sec);

            if (other == null) {
                continue;
            }

            if (other.ceilingheight > height) {
                height = other.ceilingheight;
            }
        }
        return height;
    }

    // p_spec.c lines 60–68 — anim_t.
    // Runtime state for one animated flat / texture cycle.
    public static final class Anim {
        public boolean istexture;
        public int picnum;
        public int basepic;
        public int numpics;
        public int speed;
    }

    // p_spec.c lines 73–79 — animdef_t.
    // Source animation definition. `endname` / `startname` land as
    // `String`. The C data table includes a trailing `{-1}` sentinel
    // (istexture == -1); Java doesn't have a tri-state boolean, so
    // the sentinel lives as a null AnimDef past the last real entry.
    public static final class AnimDef {
        public final boolean istexture; // false = flat
        public final String endname;
        public final String startname;
        public final int speed;

        public AnimDef(boolean istexture, String endname, String startname, int speed) {
            this.istexture = istexture;
            this.endname = endname;
            this.startname = startname;
            this.speed = speed;
        }
    }

    // p_spec.c line 83.
    public static final int MAXANIMS = 32;

    // p_spec.c lines 101–132 — animdefs[].
    // The final `{-1}` sentinel lives here as `null` — the init loop
    // stops at either null or MAXANIMS, whichever comes first.
    public static final AnimDef[] animdefs = {
            new AnimDef(false, "NUKAGE3",  "NUKAGE1",  8),
            new AnimDef(false, "FWATER4",  "FWATER1",  8),
            new AnimDef(false, "SWATER4",  "SWATER1",  8),
            new AnimDef(false, "LAVA4",    "LAVA1",    8),
            new AnimDef(false, "BLOOD3",   "BLOOD1",   8),

            // DOOM II flat animations.
            new AnimDef(false, "RROCK08",  "RROCK05",  8),
            new AnimDef(false, "SLIME04",  "SLIME01",  8),
            new AnimDef(false, "SLIME08",  "SLIME05",  8),
            new AnimDef(false, "SLIME12",  "SLIME09",  8),

            new AnimDef(true,  "BLODGR4",  "BLODGR1",  8),
            new AnimDef(true,  "SLADRIP3", "SLADRIP1", 8),

            new AnimDef(true,  "BLODRIP4", "BLODRIP1", 8),
            new AnimDef(true,  "FIREWALL", "FIREWALA", 8),
            new AnimDef(true,  "GSTFONT3", "GSTFONT1", 8),
            new AnimDef(true,  "FIRELAVA", "FIRELAV3", 8),
            new AnimDef(true,  "FIREMAG3", "FIREMAG1", 8),
            new AnimDef(true,  "FIREBLU2", "FIREBLU1", 8),
            new AnimDef(true,  "ROCKRED3", "ROCKRED1", 8),

            new AnimDef(true,  "BFALL4",   "BFALL1",   8),
            new AnimDef(true,  "SFALL4",   "SFALL1",   8),
            new AnimDef(true,  "WFALL4",   "WFALL1",   8),
            new AnimDef(true,  "DBRAIN4",  "DBRAIN1",  8),

            // C sentinel {-1} → null.
    };

    // p_spec.c lines 134–135 — runtime state.
    // The C's `anim_t* lastanim` is translated as an `int lastanimIdx`
    // bounded by `numanims` — no pointer arithmetic, just an index.
    public static final Anim[] anims = new Anim[MAXANIMS];
    public static int numanims;

    static {
        for (int i = 0; i < MAXANIMS; i++) {
            anims[i] = new Anim();
        }
    }

    // p_spec.c lines 141–144 — line-scroll state.
    public static final int MAXLINEANIMS = 64;
    public static int numlinespecials;
    public static final Line[] linespeciallist = new Line[MAXLINEANIMS];

    // p_spec.c lines 148–187 — P_InitPicAnims.
    // Walks `animdefs[]` up to the null sentinel, looking up each
    // start/end lump via R_CheckTextureNumForName (for textures) or
    // W_CheckNumForName (for flats) and recording the range into
    // `anims[numanims++]`.
    public static void pInitPicAnims() {
        numanims = 0;

        for (int i = 0; i < animdefs.length && i < MAXANIMS; i++) {
            AnimDef def = animdefs[i];
            if (def == null) {
                break;
            }

            Anim a = anims[numanims];

            if (def.istexture) {
                // different episode ?
                if (RData.rCheckTextureNumForName(def.startname) == -1) {
                    continue;
                }

                a.picnum = RData.rTextureNumForName(def.endname);
                a.basepic = RData.rTextureNumForName(def.startname);
            } else {
                if (WWad.wCheckNumForName(def.startname) == -1) {
                    continue;
                }

                a.picnum = RData.rFlatNumForName(def.endname);
                a.basepic = RData.rFlatNumForName(def.startname);
            }

            a.istexture = def.istexture;
            a.numpics = a.picnum - a.basepic + 1;

            if (a.numpics < 2) {
                throw ISystem.iError("P_InitPicAnims: bad cycle from %s to %s",
                        def.startname, def.endname);
            }

            a.speed = def.speed;
            numanims++;
        }
    }

    // p_spec.c lines 1080–1081 — level-timer globals.
    public static boolean levelTimer;
    public static int levelTimeCount;

    // p_spec.c lines 1083–1156 — P_UpdateSpecials (partial).
    // The level-timer block at the top of the C routine calls
    // G_ExitLevel on timeout; we skip it here (see deviation 23)
    // until g_game.c ports. The three remaining blocks — flat /
    // texture animation, line-scroll animation (special 48), and
    // button countdown — are ported verbatim.
    public static void pUpdateSpecials() {
        // --- LEVEL TIMER (deferred; see deviation 23) ---
        if (levelTimer) {
            levelTimeCount--;
            // if (levelTimeCount == 0) GGame.gExitLevel();
        }

        // --- ANIMATE FLATS AND TEXTURES GLOBALLY ---
        for (int n = 0; n < numanims; n++) {
            Anim anim = anims[n];
            for (int i = anim.basepic; i < anim.basepic + anim.numpics; i++) {
                int pic = anim.basepic
                        + ((PTick.leveltime / anim.speed + i) % anim.numpics);
                if (anim.istexture) {
                    RData.texturetranslation[i] = pic;
                } else {
                    RData.flattranslation[i] = pic;
                }
            }
        }

        // --- ANIMATE LINE SPECIALS ---
        for (int i = 0; i < numlinespecials; i++) {
            Line line = linespeciallist[i];
            switch (line.special) {
                case 48:
                    // EFFECT FIRSTCOL SCROLL +
                    PSetup.sides[line.sidenum[0]].textureoffset += Fixed.FRACUNIT;
                    break;
                default:
                    break;
            }
        }

        // --- DO BUTTONS ---
        for (int i = 0; i < PSwitch.MAXBUTTONS; i++) {
            PSwitch.Button b = PSwitch.buttonlist[i];
            if (b.btimer != 0) {
                b.btimer--;
                if (b.btimer == 0) {
                    switch (b.where) {
                        case PSwitch.top:
                            PSetup.sides[b.line.sidenum[0]].toptexture = b.btexture;
                            break;
                        case PSwitch.middle:
                            PSetup.sides[b.line.sidenum[0]].midtexture = b.btexture;
                            break;
                        case PSwitch.bottom:
                            PSetup.sides[b.line.sidenum[0]].bottomtexture = b.btexture;
                            break;
                    }
                    SSound.sStartSound(b.soundorg, Sounds.sfx_swtchn);
                    // C: memset(&buttonlist[i], 0, sizeof(button_t));
                    b.line = null;
                    b.where = 0;
                    b.btexture = 0;
                    b.btimer = 0;
                    b.soundorg = null;
                }
            }
        }
    }

    private PSpec() {
    }
}
