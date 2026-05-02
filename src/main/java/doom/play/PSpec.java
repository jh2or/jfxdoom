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
import doom.system.Doomstat;
import doom.system.ISystem;
import doom.system.MArgv;
import doom.wad.WWad;

public final class PSpec {

    // p_spec.h lines 602–608 — result_e.
    // Return code for T_MovePlane and its callers. `ok` = move
    // completed normally, `crushed` = move blocked by a mobj, `pastdest`
    // = destination reached this tic.
    public enum Result { ok, crushed, pastdest }

    // p_spec.h line 600 — FLOORSPEED.
    public static final int FLOORSPEED = Fixed.FRACUNIT;

    // p_spec.c lines 198–210 — getSide.
    // Returns a side_t* given the current sector index, the line index
    // within that sector, and the side (0/1) you want.
    public static doom.render.Side getSide(int currentSector, int line, int side) {
        return PSetup.sides[PSetup.sectors[currentSector].lines[line].sidenum[side]];
    }

    // p_spec.c lines 213–226 — getSector.
    // Returns sector_t* of the sector facing the chosen side of the
    // chosen line within the chosen sector.
    public static Sector getSector(int currentSector, int line, int side) {
        return PSetup.sides[PSetup.sectors[currentSector].lines[line].sidenum[side]].sector;
    }

    // p_spec.c lines 229–240 — twoSided.
    // 0/non-zero (the C returns the bit literally, not 0/1).
    public static int twoSided(int sector, int line) {
        return PSetup.sectors[sector].lines[line].flags & Line.ML_TWOSIDED;
    }

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

    // p_spec.c lines 1239–1362 — P_SpawnSpecials.
    // Called once per level after p_setup populates the world. Two
    // passes: one over `sectors[]` spawning light/door thinkers based
    // on `sector->special`, one over `lines[]` recording scroll-line
    // indices into `linespeciallist[]`. Then zero the plat / ceiling /
    // button active tables and check the -avg / -timer dm args.
    public static void pSpawnSpecials() {
        // episode is computed but never read inside the function in
        // the C (the `episode = 2` dead-store is preserved for parity).
        @SuppressWarnings("unused")
        int episode = 1;
        if (WWad.wCheckNumForName("texture2") >= 0) {
            episode = 2;
        }

        // See if -TIMER needs to be used.
        levelTimer = false;

        int i = MArgv.checkParm("-avg");
        if (i != 0 && Doomstat.deathmatch != 0) {
            levelTimer = true;
            levelTimeCount = 20 * 60 * 35;
        }

        i = MArgv.checkParm("-timer");
        if (i != 0 && Doomstat.deathmatch != 0) {
            // C: time = atoi(myargv[i+1]) * 60 * 35;
            // atoi accepts leading digits and returns 0 on any
            // failure; Integer.parseInt is stricter. We match the C
            // lenient-on-junk behavior by trying parseInt and
            // falling back to 0 on NumberFormatException (covers the
            // "-timer foo" case).
            int minutes = 0;
            try {
                minutes = Integer.parseInt(MArgv.myargv[i + 1]);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
                // atoi returns 0; missing arg would crash in C, but
                // tests shouldn't faceplant either.
            }
            levelTimer = true;
            levelTimeCount = minutes * 60 * 35;
        }

        // Init special SECTORs.
        for (int s = 0; s < PSetup.numsectors; s++) {
            Sector sector = PSetup.sectors[s];
            if (sector.special == 0) {
                continue;
            }

            switch (sector.special) {
                case 1:
                    // FLICKERING LIGHTS
                    PLights.pSpawnLightFlash(sector);
                    break;

                case 2:
                    // STROBE FAST
                    PLights.pSpawnStrobeFlash(sector, PLights.FASTDARK, 0);
                    break;

                case 3:
                    // STROBE SLOW
                    PLights.pSpawnStrobeFlash(sector, PLights.SLOWDARK, 0);
                    break;

                case 4:
                    // STROBE FAST/DEATH SLIME
                    PLights.pSpawnStrobeFlash(sector, PLights.FASTDARK, 0);
                    sector.special = 4;
                    break;

                case 8:
                    // GLOWING LIGHT
                    PLights.pSpawnGlowingLight(sector);
                    break;

                case 9:
                    // SECRET SECTOR
                    Doomstat.totalsecret++;
                    break;

                case 10:
                    // DOOR CLOSE IN 30 SECONDS
                    PDoors.pSpawnDoorCloseIn30(sector);
                    break;

                case 12:
                    // SYNC STROBE SLOW
                    PLights.pSpawnStrobeFlash(sector, PLights.SLOWDARK, 1);
                    break;

                case 13:
                    // SYNC STROBE FAST
                    PLights.pSpawnStrobeFlash(sector, PLights.FASTDARK, 1);
                    break;

                case 14:
                    // DOOR RAISE IN 5 MINUTES
                    PDoors.pSpawnDoorRaiseIn5Mins(sector, s);
                    break;

                case 17:
                    PLights.pSpawnFireFlicker(sector);
                    break;
            }
        }

        // Init line EFFECTs
        numlinespecials = 0;
        for (int n = 0; n < PSetup.numlines; n++) {
            switch (PSetup.lines[n].special) {
                case 48:
                    // EFFECT FIRSTCOL SCROLL+
                    linespeciallist[numlinespecials] = PSetup.lines[n];
                    numlinespecials++;
                    break;
                default:
                    break;
            }
        }

        // Init other misc stuff
        for (int n = 0; n < PCeilng.MAXCEILINGS; n++) {
            PCeilng.activeceilings[n] = null;
        }

        for (int n = 0; n < PPlats.MAXPLATS; n++) {
            PPlats.activeplats[n] = null;
        }

        for (int n = 0; n < PSwitch.MAXBUTTONS; n++) {
            // C: memset(&buttonlist[n], 0, sizeof(button_t)); keep slot
            // non-null (see PSwitch static initializer) and clear fields.
            PSwitch.Button b = PSwitch.buttonlist[n];
            b.line = null;
            b.where = 0;
            b.btexture = 0;
            b.btimer = 0;
            b.soundorg = null;
        }

        // UNUSED: no horizontal sliders.
        //  P_InitSlidingDoorFrames();
    }

    //
    // P_CrossSpecialLine - TRIGGER
    // Called every time a thing origin is about to cross a line with
    // a non-0 special.
    //
    // p_spec.c lines 491–950.
    public static void pCrossSpecialLine(int linenum, int side, Mobj thing) {
        Line line;
        int ok;

        line = PSetup.lines[linenum];

        // Triggers that other things can activate
        if (thing.player == null) {
            // Things that should NOT trigger specials...
            switch (thing.type) {
                case Info.MT_ROCKET:
                case Info.MT_PLASMA:
                case Info.MT_BFG:
                case Info.MT_TROOPSHOT:
                case Info.MT_HEADSHOT:
                case Info.MT_BRUISERSHOT:
                    return;

                default:
                    break;
            }

            ok = 0;
            switch (line.special) {
                case 39:  // TELEPORT TRIGGER
                case 97:  // TELEPORT RETRIGGER
                case 125: // TELEPORT MONSTERONLY TRIGGER
                case 126: // TELEPORT MONSTERONLY RETRIGGER
                case 4:   // RAISE DOOR
                case 10:  // PLAT DOWN-WAIT-UP-STAY TRIGGER
                case 88:  // PLAT DOWN-WAIT-UP-STAY RETRIGGER
                    ok = 1;
                    break;
            }
            if (ok == 0) {
                return;
            }
        }

        // Note: could use some const's here.
        switch (line.special) {
            // TRIGGERS.
            // All from here to RETRIGGERS.
            case 2:
                // Open Door
                PDoors.evDoDoor(line, PDoors.open);
                line.special = 0;
                break;

            case 3:
                // Close Door
                PDoors.evDoDoor(line, PDoors.close);
                line.special = 0;
                break;

            case 4:
                // Raise Door
                PDoors.evDoDoor(line, PDoors.normal);
                line.special = 0;
                break;

            case 5:
                // Raise Floor
                PFloor.evDoFloor(line, PFloor.raiseFloor);
                line.special = 0;
                break;

            case 6:
                // Fast Ceiling Crush & Raise
                PCeilng.evDoCeiling(line, PCeilng.fastCrushAndRaise);
                line.special = 0;
                break;

            case 8:
                // Build Stairs
                PFloor.evBuildStairs(line, PFloor.build8);
                line.special = 0;
                break;

            case 10:
                // PlatDownWaitUp
                PPlats.evDoPlat(line, PPlats.downWaitUpStay, 0);
                line.special = 0;
                break;

            case 12:
                // Light Turn On - brightest near
                PLights.evLightTurnOn(line, 0);
                line.special = 0;
                break;

            case 13:
                // Light Turn On 255
                PLights.evLightTurnOn(line, 255);
                line.special = 0;
                break;

            case 16:
                // Close Door 30
                PDoors.evDoDoor(line, PDoors.close30ThenOpen);
                line.special = 0;
                break;

            case 17:
                // Start Light Strobing
                PLights.evStartLightStrobing(line);
                line.special = 0;
                break;

            case 19:
                // Lower Floor
                PFloor.evDoFloor(line, PFloor.lowerFloor);
                line.special = 0;
                break;

            case 22:
                // Raise floor to nearest height and change texture
                PPlats.evDoPlat(line, PPlats.raiseToNearestAndChange, 0);
                line.special = 0;
                break;

            case 25:
                // Ceiling Crush and Raise
                PCeilng.evDoCeiling(line, PCeilng.crushAndRaise);
                line.special = 0;
                break;

            case 30:
                // Raise floor to shortest texture height
                //  on either side of lines.
                PFloor.evDoFloor(line, PFloor.raiseToTexture);
                line.special = 0;
                break;

            case 35:
                // Lights Very Dark
                PLights.evLightTurnOn(line, 35);
                line.special = 0;
                break;

            case 36:
                // Lower Floor (TURBO)
                PFloor.evDoFloor(line, PFloor.turboLower);
                line.special = 0;
                break;

            case 37:
                // LowerAndChange
                PFloor.evDoFloor(line, PFloor.lowerAndChange);
                line.special = 0;
                break;

            case 38:
                // Lower Floor To Lowest
                PFloor.evDoFloor(line, PFloor.lowerFloorToLowest);
                line.special = 0;
                break;

            case 39:
                // TELEPORT!
                PTelept.evTeleport(line, side, thing);
                line.special = 0;
                break;

            case 40:
                // RaiseCeilingLowerFloor
                PCeilng.evDoCeiling(line, PCeilng.raiseToHighest);
                PFloor.evDoFloor(line, PFloor.lowerFloorToLowest);
                line.special = 0;
                break;

            case 44:
                // Ceiling Crush
                PCeilng.evDoCeiling(line, PCeilng.lowerAndCrush);
                line.special = 0;
                break;

            case 52:
                // EXIT!
                doom.system.GGame.gExitLevel();
                break;

            case 53:
                // Perpetual Platform Raise
                PPlats.evDoPlat(line, PPlats.perpetualRaise, 0);
                line.special = 0;
                break;

            case 54:
                // Platform Stop
                PPlats.evStopPlat(line);
                line.special = 0;
                break;

            case 56:
                // Raise Floor Crush
                PFloor.evDoFloor(line, PFloor.raiseFloorCrush);
                line.special = 0;
                break;

            case 57:
                // Ceiling Crush Stop
                PCeilng.evCeilingCrushStop(line);
                line.special = 0;
                break;

            case 58:
                // Raise Floor 24
                PFloor.evDoFloor(line, PFloor.raiseFloor24);
                line.special = 0;
                break;

            case 59:
                // Raise Floor 24 And Change
                PFloor.evDoFloor(line, PFloor.raiseFloor24AndChange);
                line.special = 0;
                break;

            case 104:
                // Turn lights off in sector(tag)
                PLights.evTurnTagLightsOff(line);
                line.special = 0;
                break;

            case 108:
                // Blazing Door Raise (faster than TURBO!)
                PDoors.evDoDoor(line, PDoors.blazeRaise);
                line.special = 0;
                break;

            case 109:
                // Blazing Door Open (faster than TURBO!)
                PDoors.evDoDoor(line, PDoors.blazeOpen);
                line.special = 0;
                break;

            case 100:
                // Build Stairs Turbo 16
                PFloor.evBuildStairs(line, PFloor.turbo16);
                line.special = 0;
                break;

            case 110:
                // Blazing Door Close (faster than TURBO!)
                PDoors.evDoDoor(line, PDoors.blazeClose);
                line.special = 0;
                break;

            case 119:
                // Raise floor to nearest surr. floor
                PFloor.evDoFloor(line, PFloor.raiseFloorToNearest);
                line.special = 0;
                break;

            case 121:
                // Blazing PlatDownWaitUpStay
                PPlats.evDoPlat(line, PPlats.blazeDWUS, 0);
                line.special = 0;
                break;

            case 124:
                // Secret EXIT
                doom.system.GGame.gSecretExitLevel();
                break;

            case 125:
                // TELEPORT MonsterONLY
                if (thing.player == null) {
                    PTelept.evTeleport(line, side, thing);
                    line.special = 0;
                }
                break;

            case 130:
                // Raise Floor Turbo
                PFloor.evDoFloor(line, PFloor.raiseFloorTurbo);
                line.special = 0;
                break;

            case 141:
                // Silent Ceiling Crush & Raise
                PCeilng.evDoCeiling(line, PCeilng.silentCrushAndRaise);
                line.special = 0;
                break;

            // RETRIGGERS.  All from here till end.
            case 72:
                // Ceiling Crush
                PCeilng.evDoCeiling(line, PCeilng.lowerAndCrush);
                break;

            case 73:
                // Ceiling Crush and Raise
                PCeilng.evDoCeiling(line, PCeilng.crushAndRaise);
                break;

            case 74:
                // Ceiling Crush Stop
                PCeilng.evCeilingCrushStop(line);
                break;

            case 75:
                // Close Door
                PDoors.evDoDoor(line, PDoors.close);
                break;

            case 76:
                // Close Door 30
                PDoors.evDoDoor(line, PDoors.close30ThenOpen);
                break;

            case 77:
                // Fast Ceiling Crush & Raise
                PCeilng.evDoCeiling(line, PCeilng.fastCrushAndRaise);
                break;

            case 79:
                // Lights Very Dark
                PLights.evLightTurnOn(line, 35);
                break;

            case 80:
                // Light Turn On - brightest near
                PLights.evLightTurnOn(line, 0);
                break;

            case 81:
                // Light Turn On 255
                PLights.evLightTurnOn(line, 255);
                break;

            case 82:
                // Lower Floor To Lowest
                PFloor.evDoFloor(line, PFloor.lowerFloorToLowest);
                break;

            case 83:
                // Lower Floor
                PFloor.evDoFloor(line, PFloor.lowerFloor);
                break;

            case 84:
                // LowerAndChange
                PFloor.evDoFloor(line, PFloor.lowerAndChange);
                break;

            case 86:
                // Open Door
                PDoors.evDoDoor(line, PDoors.open);
                break;

            case 87:
                // Perpetual Platform Raise
                PPlats.evDoPlat(line, PPlats.perpetualRaise, 0);
                break;

            case 88:
                // PlatDownWaitUp
                PPlats.evDoPlat(line, PPlats.downWaitUpStay, 0);
                break;

            case 89:
                // Platform Stop
                PPlats.evStopPlat(line);
                break;

            case 90:
                // Raise Door
                PDoors.evDoDoor(line, PDoors.normal);
                break;

            case 91:
                // Raise Floor
                PFloor.evDoFloor(line, PFloor.raiseFloor);
                break;

            case 92:
                // Raise Floor 24
                PFloor.evDoFloor(line, PFloor.raiseFloor24);
                break;

            case 93:
                // Raise Floor 24 And Change
                PFloor.evDoFloor(line, PFloor.raiseFloor24AndChange);
                break;

            case 94:
                // Raise Floor Crush
                PFloor.evDoFloor(line, PFloor.raiseFloorCrush);
                break;

            case 95:
                // Raise floor to nearest height
                // and change texture.
                PPlats.evDoPlat(line, PPlats.raiseToNearestAndChange, 0);
                break;

            case 96:
                // Raise floor to shortest texture height
                // on either side of lines.
                PFloor.evDoFloor(line, PFloor.raiseToTexture);
                break;

            case 97:
                // TELEPORT!
                PTelept.evTeleport(line, side, thing);
                break;

            case 98:
                // Lower Floor (TURBO)
                PFloor.evDoFloor(line, PFloor.turboLower);
                break;

            case 105:
                // Blazing Door Raise (faster than TURBO!)
                PDoors.evDoDoor(line, PDoors.blazeRaise);
                break;

            case 106:
                // Blazing Door Open (faster than TURBO!)
                PDoors.evDoDoor(line, PDoors.blazeOpen);
                break;

            case 107:
                // Blazing Door Close (faster than TURBO!)
                PDoors.evDoDoor(line, PDoors.blazeClose);
                break;

            case 120:
                // Blazing PlatDownWaitUpStay.
                PPlats.evDoPlat(line, PPlats.blazeDWUS, 0);
                break;

            case 126:
                // TELEPORT MonsterONLY.
                if (thing.player == null) {
                    PTelept.evTeleport(line, side, thing);
                }
                break;

            case 128:
                // Raise To Nearest Floor
                PFloor.evDoFloor(line, PFloor.raiseFloorToNearest);
                break;

            case 129:
                // Raise Floor Turbo
                PFloor.evDoFloor(line, PFloor.raiseFloorTurbo);
                break;
        }
    }

    //
    // P_ShootSpecialLine - IMPACT SPECIALS
    // Called when a thing shoots a special line.
    //
    // p_spec.c lines 958–1000.
    public static void pShootSpecialLine(Mobj thing, Line line) {
        int ok;

        // Impacts that other things can activate.
        if (thing.player == null) {
            ok = 0;
            switch (line.special) {
                case 46:
                    // OPEN DOOR IMPACT
                    ok = 1;
                    break;
            }
            if (ok == 0) {
                return;
            }
        }

        switch (line.special) {
            case 24:
                // RAISE FLOOR
                PFloor.evDoFloor(line, PFloor.raiseFloor);
                PSwitch.pChangeSwitchTexture(line, 0);
                break;

            case 46:
                // OPEN DOOR
                PDoors.evDoDoor(line, PDoors.open);
                PSwitch.pChangeSwitchTexture(line, 1);
                break;

            case 47:
                // RAISE FLOOR NEAR AND CHANGE
                PPlats.evDoPlat(line, PPlats.raiseToNearestAndChange, 0);
                PSwitch.pChangeSwitchTexture(line, 0);
                break;
        }
    }

    //
    // Special Stuff that can not be categorized
    //
    // p_spec.c lines 1163–1221 — EV_DoDonut.
    // Spawns a "donut": the inner sector (s1) lowers to the outer
    // ring's floor height, and the ring sector (s2) rises to meet it,
    // adopting the outer sector (s3)'s pic + special.
    //
    // PRESERVED C BUG (CLAUDE.md §2): the inner-loop guard at
    // p_spec.c line 1187 reads `(!s2->lines[i]->flags & ML_TWOSIDED)`
    // — `!` binds tighter than `&` in C, so the LHS is `(!flags) &
    // ML_TWOSIDED` which is always 0 (since `!x` is 0 or 1, and
    // `1 & 4 == 0`). The guard collapses to `backsector == s1`. The
    // intent was clearly `!(flags & ML_TWOSIDED)`, but the bug means
    // EV_DoDonut accepts the *first* line of s2 whose backsector is
    // not s1 — even one-sided lines. Faithfully preserved.
    public static int evDoDonut(Line line) {
        Sector s1;
        Sector s2;
        Sector s3;
        int secnum;
        int rtn;
        int i;
        PFloor.FloorMove floor;

        secnum = -1;
        rtn = 0;
        while ((secnum = pFindSectorFromLineTag(line, secnum)) >= 0) {
            s1 = PSetup.sectors[secnum];

            // ALREADY MOVING?  IF SO, KEEP GOING...
            if (s1.specialdata != null) {
                continue;
            }

            rtn = 1;
            s2 = getNextSector(s1.lines[0], s1);
            if (s2 == null) {
                // The C dereferences s2 unconditionally; in a clean WAD
                // s1.lines[0] is two-sided so getNextSector never returns
                // null. Defensive null-check here only because Java would
                // throw NPE; the C would crash equivalently.
                continue;
            }
            for (i = 0; i < s2.linecount; i++) {
                // Faithful translation of the precedence bug — see header.
                int notFlags = (s2.lines[i].flags == 0) ? 1 : 0;
                if (((notFlags & Line.ML_TWOSIDED) != 0)
                        || (s2.lines[i].backsector == s1)) {
                    continue;
                }
                s3 = s2.lines[i].backsector;

                // Spawn rising slime
                floor = new PFloor.FloorMove();
                PTick.pAddThinker(floor);
                s2.specialdata = floor;
                floor.function.acp1 = PFloor::tMoveFloor;
                floor.type = PFloor.donutRaise;
                floor.crush = false;
                floor.direction = 1;
                floor.sector = s2;
                floor.speed = FLOORSPEED / 2;
                floor.texture = s3.floorpic;
                floor.newspecial = 0;
                floor.floordestheight = s3.floorheight;

                // Spawn lowering donut-hole
                floor = new PFloor.FloorMove();
                PTick.pAddThinker(floor);
                s1.specialdata = floor;
                floor.function.acp1 = PFloor::tMoveFloor;
                floor.type = PFloor.lowerFloor;
                floor.crush = false;
                floor.direction = -1;
                floor.sector = s1;
                floor.speed = FLOORSPEED / 2;
                floor.floordestheight = s3.floorheight;
                break;
            }
        }
        return rtn;
    }

    //
    // P_PlayerInSpecialSector
    //
    // p_spec.c lines 273–377. Stub: the full body damages the player
    // for slime / lava / hurt-on-contact / 20% damage / etc. (each
    // gated on Player.powers[pw_ironfeet] or armor) and counts
    // secrets. Needs P_DamageMobj from p_inter (deferred) plus the
    // tic-modulo + radsuit-checks.
    //
    // p_user.c P_PlayerThink calls this every tic the player stands
    // in a sector with sector.special != 0; the no-op stub keeps
    // the call site live without altering player health/secret state.
    public static void pPlayerInSpecialSector(Player player) {
        // Intentionally empty until p_inter ports P_DamageMobj.
    }

    private PSpec() {
    }
}
