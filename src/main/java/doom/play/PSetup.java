// Port of linuxdoom-1.10/p_setup.c — partial.
//
// Loads a level's worth of geometry from a WAD: VERTEXES, SIDEDEFS,
// LINEDEFS, SECTORS, SUBSECTORS, NODES, SEGS, BLOCKMAP, REJECT, plus
// THINGS (mobj placements). Each parser reads its lump's bytes into a
// typed Java array; sizes come from the lump length divided by the
// on-disk record size.
//
// pSetupLevel orchestrates geometry, THINGS and sector-special loading.
// GGame starts per-level sound afterward. Eager graphics precaching and
// deathmatch spawning remain omitted. pSetupLevelGeometry is the geometry-only test entry point.
//
// On-disk records use SHORT() (16-bit little-endian) for everything;
// we read with ByteBuffer(LITTLE_ENDIAN) per CLAUDE.md §3.
package doom.play;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.Node;
import doom.render.RData;
import doom.render.Sector;
import doom.render.Seg;
import doom.render.Side;
import doom.render.Slopetype;
import doom.render.Subsector;
import doom.render.Vertex;
import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.ISystem;
import doom.wad.WWad;
import doom.wad.ZZone;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class PSetup {

    // doomdata.h lines 41–55 — map-lump offset enum.
    public static final int ML_LABEL    = 0;
    public static final int ML_THINGS   = 1;
    public static final int ML_LINEDEFS = 2;
    public static final int ML_SIDEDEFS = 3;
    public static final int ML_VERTEXES = 4;
    public static final int ML_SEGS     = 5;
    public static final int ML_SSECTORS = 6;
    public static final int ML_NODES    = 7;
    public static final int ML_SECTORS  = 8;
    public static final int ML_REJECT   = 9;
    public static final int ML_BLOCKMAP = 10;

    // r_state.h / p_setup.c — arrays and their counts.
    public static int numvertexes;
    public static Vertex[] vertexes;

    public static int numsegs;
    public static Seg[] segs;

    public static int numsectors;
    public static Sector[] sectors;

    public static int numsubsectors;
    public static Subsector[] subsectors;

    public static int numnodes;
    public static Node[] nodes;

    public static int numlines;
    public static Line[] lines;

    public static int numsides;
    public static Side[] sides;

    // BLOCKMAP — see p_setup.c lines 78–95.
    // blockmaplump holds the entire BLOCKMAP lump as ints; the C
    // aliases `blockmap = blockmaplump + 4` to skip the four-short
    // header. Java keeps both names as references to the same array
    // and tracks the +4 header skip explicitly via blockmapOffset.
    public static int bmapwidth;
    public static int bmapheight;
    public static int bmaporgx;     // fixed_t
    public static int bmaporgy;     // fixed_t
    public static int[] blockmaplump;
    public static int[] blockmap;   // alias of blockmaplump (same ref)
    public static final int blockmapOffset = 4;

    // For thing chains in the blockmap. Each cell is the head of a
    // doubly-linked list of mobjs whose origin lies in that block.
    // Sized bmapwidth*bmapheight; mobj.bnext is the link field.
    public static doom.play.Mobj[] blocklinks;

    // REJECT lump — bit-packed sector-vs-sector visibility table.
    // Indexed (i,j) = bit (i*numsectors + j); set bit means sector
    // i can never see sector j (P_CheckSight short-circuits).
    public static byte[] rejectmatrix;

    // p_setup.c lines 109–113 — single/multi-player starting spots.
    // deathmatchstarts is a fixed pool of 10 mapthing slots; the C
    // tracks the next free slot via the `deathmatch_p` pointer, the
    // Java port uses an int index into the array (deathmatchPIndex).
    // playerstarts is one slot per player seat (MAXPLAYERS).
    public static final int MAX_DEATHMATCH_STARTS = 10;
    public static final Mapthing[] deathmatchstarts = new Mapthing[MAX_DEATHMATCH_STARTS];
    public static int deathmatchPIndex; // C: deathmatch_p - deathmatchstarts
    public static final Mapthing[] playerstarts = new Mapthing[Doomdef.MAXPLAYERS];

    private PSetup() {
    }

    //
    // P_LoadVertexes
    //
    // p_setup.c lines 122–152.
    public static void pLoadVertexes(int lump) throws IOException {
        // mapvertex_t is 4 bytes (short x, short y).
        numvertexes = WWad.wLumpLength(lump) / 4;
        vertexes = new Vertex[numvertexes];
        byte[] data = WWad.wCacheLumpNum(lump, ZZone.PU_STATIC);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // Copy and convert vertex coordinates, internal repr as fixed.
        for (int i = 0; i < numvertexes; i++) {
            Vertex v = new Vertex();
            v.x = buf.getShort() << Fixed.FRACBITS;
            v.y = buf.getShort() << Fixed.FRACBITS;
            vertexes[i] = v;
        }

        ZZone.zFree(data);
    }

    //
    // P_LoadSegs
    //
    // p_setup.c lines 159–196. Requires vertexes, lines, sides loaded.
    public static void pLoadSegs(int lump) throws IOException {
        // mapseg_t is 12 bytes.
        numsegs = WWad.wLumpLength(lump) / 12;
        segs = new Seg[numsegs];
        byte[] data = WWad.wCacheLumpNum(lump, ZZone.PU_STATIC);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < numsegs; i++) {
            Seg s = new Seg();
            s.v1 = vertexes[buf.getShort() & 0xFFFF];
            s.v2 = vertexes[buf.getShort() & 0xFFFF];

            s.angle = (buf.getShort()) << 16;
            int linedef = buf.getShort() & 0xFFFF;
            int side = buf.getShort() & 0xFFFF;
            s.offset = (buf.getShort()) << 16;

            Line ldef = lines[linedef];
            s.linedef = ldef;
            s.sidedef = sides[ldef.sidenum[side]];
            s.frontsector = sides[ldef.sidenum[side]].sector;
            if ((ldef.flags & Line.ML_TWOSIDED) != 0) {
                s.backsector = sides[ldef.sidenum[side ^ 1]].sector;
            } else {
                s.backsector = null;
            }
            segs[i] = s;
        }

        ZZone.zFree(data);
    }

    //
    // P_LoadSubsectors
    //
    // p_setup.c lines 202–224.
    public static void pLoadSubsectors(int lump) throws IOException {
        // mapsubsector_t is 4 bytes (short numsegs, short firstseg).
        numsubsectors = WWad.wLumpLength(lump) / 4;
        subsectors = new Subsector[numsubsectors];
        byte[] data = WWad.wCacheLumpNum(lump, ZZone.PU_STATIC);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < numsubsectors; i++) {
            Subsector ss = new Subsector();
            ss.numlines = buf.getShort() & 0xFFFF;
            ss.firstline = buf.getShort() & 0xFFFF;
            subsectors[i] = ss;
        }

        ZZone.zFree(data);
    }

    //
    // P_LoadSectors
    //
    // p_setup.c lines 231–258.
    public static void pLoadSectors(int lump) throws IOException {
        // mapsector_t is 26 bytes.
        numsectors = WWad.wLumpLength(lump) / 26;
        sectors = new Sector[numsectors];
        byte[] data = WWad.wCacheLumpNum(lump, ZZone.PU_STATIC);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] nameBytes = new byte[8];

        for (int i = 0; i < numsectors; i++) {
            Sector s = new Sector();
            s.floorheight = buf.getShort() << Fixed.FRACBITS;
            s.ceilingheight = buf.getShort() << Fixed.FRACBITS;
            buf.get(nameBytes);
            s.floorpic = RData.rFlatNumForName(WWad.trimLumpName(nameBytes));
            buf.get(nameBytes);
            s.ceilingpic = RData.rFlatNumForName(WWad.trimLumpName(nameBytes));
            s.lightlevel = buf.getShort();
            s.special = buf.getShort();
            s.tag = buf.getShort();
            s.thinglist = null;
            sectors[i] = s;
        }

        ZZone.zFree(data);
    }

    //
    // P_LoadNodes
    //
    // p_setup.c lines 264–295.
    public static void pLoadNodes(int lump) throws IOException {
        // mapnode_t is 28 bytes (8 partition shorts + 16 bbox shorts +
        // 4 children = wait that's 8+16+4 = 28, but counting bytes:
        // 4*2 partition + 8*2 bbox + 2*2 children = 8 + 16 + 4 = 28).
        numnodes = WWad.wLumpLength(lump) / 28;
        nodes = new Node[numnodes];
        byte[] data = WWad.wCacheLumpNum(lump, ZZone.PU_STATIC);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < numnodes; i++) {
            Node n = new Node();
            n.x = buf.getShort() << Fixed.FRACBITS;
            n.y = buf.getShort() << Fixed.FRACBITS;
            n.dx = buf.getShort() << Fixed.FRACBITS;
            n.dy = buf.getShort() << Fixed.FRACBITS;
            for (int j = 0; j < 2; j++) {
                for (int k = 0; k < 4; k++) {
                    n.bbox[j][k] = buf.getShort() << Fixed.FRACBITS;
                }
            }
            // C order: children[0] precedes bbox[1] in memory? Actually
            // re-reading the on-disk layout: partition (4 shorts), then
            // bbox[0] (4 shorts), bbox[1] (4 shorts), then children[2].
            // Since we read bbox[0] then bbox[1] above, children come last.
            for (int j = 0; j < 2; j++) {
                n.children[j] = buf.getShort() & 0xFFFF;
            }
            nodes[i] = n;
        }

        ZZone.zFree(data);
    }

    //
    // P_LoadLineDefs
    //
    // p_setup.c lines 357–432. Requires vertexes + sides loaded.
    public static void pLoadLineDefs(int lump) throws IOException {
        // maplinedef_t is 14 bytes (7 shorts).
        numlines = WWad.wLumpLength(lump) / 14;
        lines = new Line[numlines];
        byte[] data = WWad.wCacheLumpNum(lump, ZZone.PU_STATIC);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < numlines; i++) {
            Line ld = new Line();
            // maplinedef_t on-disk order: v1, v2, flags, special, tag,
            // sidenum[0], sidenum[1]. Don't reorder — getShort() is
            // sequential (CLAUDE.md §3).
            int v1Idx = buf.getShort() & 0xFFFF;
            int v2Idx = buf.getShort() & 0xFFFF;
            ld.flags = buf.getShort() & 0xFFFF;
            ld.special = buf.getShort();
            ld.tag = buf.getShort();
            Vertex v1 = vertexes[v1Idx];
            Vertex v2 = vertexes[v2Idx];
            ld.v1 = v1;
            ld.v2 = v2;
            ld.dx = v2.x - v1.x;
            ld.dy = v2.y - v1.y;

            if (ld.dx == 0) {
                ld.slopetype = Slopetype.ST_VERTICAL;
            } else if (ld.dy == 0) {
                ld.slopetype = Slopetype.ST_HORIZONTAL;
            } else {
                if (Fixed.div(ld.dy, ld.dx) > 0) {
                    ld.slopetype = Slopetype.ST_POSITIVE;
                } else {
                    ld.slopetype = Slopetype.ST_NEGATIVE;
                }
            }

            if (v1.x < v2.x) {
                ld.bbox[MBbox.BOXLEFT] = v1.x;
                ld.bbox[MBbox.BOXRIGHT] = v2.x;
            } else {
                ld.bbox[MBbox.BOXLEFT] = v2.x;
                ld.bbox[MBbox.BOXRIGHT] = v1.x;
            }

            if (v1.y < v2.y) {
                ld.bbox[MBbox.BOXBOTTOM] = v1.y;
                ld.bbox[MBbox.BOXTOP] = v2.y;
            } else {
                ld.bbox[MBbox.BOXBOTTOM] = v2.y;
                ld.bbox[MBbox.BOXTOP] = v1.y;
            }

            // sidenum[] in the lump is unsigned short; -1 means "no
            // side" (one-sided line). Read as signed short so the
            // sentinel survives.
            ld.sidenum[0] = buf.getShort();
            ld.sidenum[1] = buf.getShort();

            if (ld.sidenum[0] != -1) {
                ld.frontsector = sides[ld.sidenum[0]].sector;
            } else {
                ld.frontsector = null;
            }
            if (ld.sidenum[1] != -1) {
                ld.backsector = sides[ld.sidenum[1]].sector;
            } else {
                ld.backsector = null;
            }
            lines[i] = ld;
        }

        ZZone.zFree(data);
    }

    //
    // P_LoadSideDefs
    //
    // p_setup.c lines 438–463. Requires sectors loaded.
    public static void pLoadSideDefs(int lump) throws IOException {
        // mapsidedef_t is 30 bytes (2 shorts + 3 char[8] + 1 short).
        numsides = WWad.wLumpLength(lump) / 30;
        sides = new Side[numsides];
        byte[] data = WWad.wCacheLumpNum(lump, ZZone.PU_STATIC);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] nameBytes = new byte[8];

        for (int i = 0; i < numsides; i++) {
            Side sd = new Side();
            sd.textureoffset = buf.getShort() << Fixed.FRACBITS;
            sd.rowoffset = buf.getShort() << Fixed.FRACBITS;
            buf.get(nameBytes);
            sd.toptexture = RData.rTextureNumForName(WWad.trimLumpName(nameBytes));
            buf.get(nameBytes);
            sd.bottomtexture = RData.rTextureNumForName(WWad.trimLumpName(nameBytes));
            buf.get(nameBytes);
            sd.midtexture = RData.rTextureNumForName(WWad.trimLumpName(nameBytes));
            sd.sector = sectors[buf.getShort() & 0xFFFF];
            sides[i] = sd;
        }

        ZZone.zFree(data);
    }

    //
    // P_LoadBlockMap
    //
    // p_setup.c lines 469–490. The blockmap lump is one giant array
    // of shorts: a 4-short header (origin x/y, width, height) followed
    // by a width*height table of per-block list offsets, followed by
    // the actual lists (each line index, terminated with -1).
    public static void pLoadBlockMap(int lump) throws IOException {
        byte[] data = WWad.wCacheLumpNum(lump, ZZone.PU_LEVEL);
        int count = WWad.wLumpLength(lump) / 2;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        blockmaplump = new int[count];
        for (int i = 0; i < count; i++) {
            blockmaplump[i] = buf.getShort();
        }
        // Same backing array — `blockmap = blockmaplump + 4` in C.
        // Iterators read at index `blockmapOffset + i`.
        blockmap = blockmaplump;

        bmaporgx = blockmaplump[0] << Fixed.FRACBITS;
        bmaporgy = blockmaplump[1] << Fixed.FRACBITS;
        bmapwidth = blockmaplump[2] & 0xFFFF;   // mapped count, unsigned
        bmapheight = blockmaplump[3] & 0xFFFF;

        // clear out mobj chains
        blocklinks = new doom.play.Mobj[bmapwidth * bmapheight];
    }

    //
    // P_GroupLines
    // Builds sector line lists and subsector sector numbers.
    // Finds block bounding boxes for sectors.
    //
    // p_setup.c lines 499–577.
    public static void pGroupLines() {
        int[] bbox = new int[4];

        // look up sector number for each subsector
        for (int i = 0; i < numsubsectors; i++) {
            Subsector ss = subsectors[i];
            Seg seg = segs[ss.firstline];
            ss.sector = seg.sidedef.sector;
        }

        // count number of lines in each sector
        int total = 0;
        for (int i = 0; i < numlines; i++) {
            Line li = lines[i];
            total++;
            li.frontsector.linecount++;

            if (li.backsector != null && li.backsector != li.frontsector) {
                li.backsector.linecount++;
                total++;
            }
        }

        // build line tables for each sector. The C uses one big
        // linebuffer Z_Malloc and hands out slices via pointer
        // arithmetic. Java has no slice-of-array, so each sector gets
        // its own Line[linecount] — same observable structure.
        for (int i = 0; i < numsectors; i++) {
            Sector sector = sectors[i];
            MBbox.clearBox(bbox);
            sector.lines = new Line[sector.linecount];
            int idx = 0;
            for (int j = 0; j < numlines; j++) {
                Line li = lines[j];
                if (li.frontsector == sector || li.backsector == sector) {
                    sector.lines[idx++] = li;
                    MBbox.addToBox(bbox, li.v1.x, li.v1.y);
                    MBbox.addToBox(bbox, li.v2.x, li.v2.y);
                }
            }
            if (idx != sector.linecount) {
                ISystem.iError("P_GroupLines: miscounted");
            }

            // set the degenmobj_t to the middle of the bounding box
            sector.soundorg.x = (bbox[MBbox.BOXRIGHT] + bbox[MBbox.BOXLEFT]) / 2;
            sector.soundorg.y = (bbox[MBbox.BOXTOP] + bbox[MBbox.BOXBOTTOM]) / 2;

            // adjust bounding box to map blocks
            int MAXRADIUS = 32 * Fixed.FRACUNIT;
            int MAPBLOCKSHIFT = Fixed.FRACBITS + 7;

            int block = (bbox[MBbox.BOXTOP] - bmaporgy + MAXRADIUS) >> MAPBLOCKSHIFT;
            block = block >= bmapheight ? bmapheight - 1 : block;
            sector.blockbox[MBbox.BOXTOP] = block;

            block = (bbox[MBbox.BOXBOTTOM] - bmaporgy - MAXRADIUS) >> MAPBLOCKSHIFT;
            block = block < 0 ? 0 : block;
            sector.blockbox[MBbox.BOXBOTTOM] = block;

            block = (bbox[MBbox.BOXRIGHT] - bmaporgx + MAXRADIUS) >> MAPBLOCKSHIFT;
            block = block >= bmapwidth ? bmapwidth - 1 : block;
            sector.blockbox[MBbox.BOXRIGHT] = block;

            block = (bbox[MBbox.BOXLEFT] - bmaporgx - MAXRADIUS) >> MAPBLOCKSHIFT;
            block = block < 0 ? 0 : block;
            sector.blockbox[MBbox.BOXLEFT] = block;
        }
    }

    //
    // P_LoadThings
    //
    // p_setup.c lines 301–350. Reads the THINGS lump and dispatches
    // each entry through P_SpawnMapThing. Filters out commercial-only
    // monster types when running on a non-commercial IWAD; the C
    // `break` on the first filtered entry is preserved verbatim — it
    // stops at the first commercial-only thing rather than skipping
    // just that entry. (Real PWADs avoid putting any of these types
    // into a non-commercial map, so the difference never bites.)
    //
    // mapthing_t on disk is 10 bytes (5 shorts: x, y, angle, type,
    // options).
    public static void pLoadThings(int lump) throws IOException {
        byte[] data = WWad.wCacheLumpNum(lump, ZZone.PU_STATIC);
        int numthings = WWad.wLumpLength(lump) / 10;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < numthings; i++) {
            boolean spawn = true;

            // Read all five shorts up-front so the per-entry filter
            // and the spawn path see the same field values. The C
            // peeks at the unswapped on-disk mt->type for the
            // commercial filter; that works because the commercial
            // type-numbers are all < 256 and a 16-bit little-endian
            // SHORT round-trip through SHORT() is a no-op for those.
            // The Java port has already swapped via getShort().
            int x = buf.getShort();
            int y = buf.getShort();
            int angle = buf.getShort();
            int type = buf.getShort();
            int options = buf.getShort();

            // Do not spawn cool, new monsters if !commercial.
            if (Doomstat.gamemode != Doomstat.GameMode.commercial) {
                switch (type) {
                    case 68: // Arachnotron
                    case 64: // Archvile
                    case 88: // Boss Brain
                    case 89: // Boss Shooter
                    case 69: // Hell Knight
                    case 67: // Mancubus
                    case 71: // Pain Elemental
                    case 65: // Former Human Commando
                    case 66: // Revenant
                    case 84: // Wolf SS
                        spawn = false;
                        break;
                    default:
                        break;
                }
            }
            if (!spawn) {
                break; // C: `break` — stops the loop entirely.
            }

            Mapthing mt = new Mapthing(x, y, angle, type, options);
            PMobj.pSpawnMapThing(mt);
        }

        ZZone.zFree(data);
    }

    //
    // P_SetupLevel
    //
    // p_setup.c lines 593–693. Full level-load orchestrator: clears
    // thinkers, loads geometry, loads THINGS (spawning monsters /
    // items / player), runs sector-special spawn (PSpec.pSpawnSpecials),
    // resets per-level counters.
    //
    // Remaining omitted setup calls:
    //   - R_PrecacheLevel (sprite / flat / texture precache)
    //   - G_DeathMatchSpawnPlayer iteration (deferred until full
    //     multiplayer support; single-player skips it)
    //
    // `playermask` is the bit-set of seats to populate (deathmatch
    // multiplayer); single-player passes 0 and the function just
    // pumps player 0 via the THINGS-lump spawn path.
    public static void pSetupLevel(int episode, int map, int playermask,
            int skill) throws IOException {
        // Reset per-level counters.
        doom.system.Doomstat.totalkills = 0;
        doom.system.Doomstat.totalitems = 0;
        doom.system.Doomstat.totalsecret = 0;

        // Per-player stats reset (matches C lines 605–613).
        for (int i = 0; i < doom.system.Doomdef.MAXPLAYERS; i++) {
            doom.system.GGame.players[i].killcount = 0;
            doom.system.GGame.players[i].secretcount = 0;
            doom.system.GGame.players[i].itemcount = 0;
        }

        // Reset thinker chain so old mobjs / floor-movers from the
        // previous level don't linger.
        doom.play.PTick.pInitThinkers();
        doom.play.PTick.leveltime = 0;

        // Pick the lumpnum based on (episode, map) per game mode.
        int lumpnum;
        if (Doomstat.dynamicLevels && !Doomstat.demoplayback) {
            lumpnum = WWad.wGetNumForName(DynamicLevel.MAP_NAME);
        } else if (doom.system.Doomstat.gamemode == doom.system.Doomstat.GameMode.commercial) {
            String name;
            if (map < 10) {
                name = "MAP0" + map;
            } else {
                name = "MAP" + map;
            }
            lumpnum = doom.wad.WWad.wGetNumForName(name);
        } else {
            lumpnum = doom.wad.WWad.wGetNumForName(
                    "E" + episode + "M" + map);
        }

        // Geometry + THINGS.
        pSetupLevelGeometry(lumpnum);
        pLoadThings(lumpnum + ML_THINGS);

        // Sector specials (lights, scrolls, door-close-30, etc.).
        PSpec.pSpawnSpecials();

        // GGame calls S_Start after setup; R_PrecacheLevel remains omitted.

        // Update gamestate.
        doom.system.Doomstat.gamemap = map;
        doom.system.Doomstat.gameepisode = episode;
        doom.system.Doomstat.gameskill = skill;
    }

    //
    // pSetupLevelGeometry: the geometry-only chunk of P_SetupLevel.
    // Loads the eight geometry lumps + REJECT for the given map base
    // lump and runs P_GroupLines. Does NOT load THINGS, run
    // R_PrecacheLevel, or touch player/deathmatch state.
    public static void pSetupLevelGeometry(int lumpnum) throws IOException {
        // Order mirrors p_setup.c lines 649–660.
        pLoadBlockMap(lumpnum + ML_BLOCKMAP);
        pLoadVertexes(lumpnum + ML_VERTEXES);
        pLoadSectors(lumpnum + ML_SECTORS);
        pLoadSideDefs(lumpnum + ML_SIDEDEFS);

        pLoadLineDefs(lumpnum + ML_LINEDEFS);
        pLoadSubsectors(lumpnum + ML_SSECTORS);
        pLoadNodes(lumpnum + ML_NODES);
        pLoadSegs(lumpnum + ML_SEGS);

        rejectmatrix = WWad.wCacheLumpNum(lumpnum + ML_REJECT, ZZone.PU_LEVEL);
        pGroupLines();
    }
}
