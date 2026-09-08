// Port of linuxdoom-1.10/p_saveg.c — full.
//
// Save / load support: serialize the playsim into a byte stream and
// restore it. The C archives by `memcpy`ing each struct verbatim and
// swizzling pointer fields to integer indices (state_t* → S_* index,
// sector_t* → sector index, player_t* → 1-based seat); on restore,
// it un-swizzles and re-links thinkers via P_AddThinker /
// P_SetThingPosition.
//
// Java has no struct-as-bytes view, so the port writes each field
// individually through DataOutputStream / DataInputStream. The on-
// disk format does NOT match the C savegame layout — it's a fresh
// field-by-field encoding (see deviation 30). Demo playback uses a
// different format (g_game.c), so this divergence doesn't affect
// demo compatibility.
//
// Thinker dispatch: the C uses function-pointer comparisons
// (`th->function.acp1 == T_MoveCeiling`) to identify thinker class.
// The Java port uses `instanceof` against the Java subclass, which
// is the natural idiom and avoids method-reference identity issues.
package doom.play;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.Sector;
import doom.render.Side;
import doom.system.Doomdef;
import doom.system.GGame;
import doom.system.ISystem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class PSaveg {

    // p_saveg.c lines 220–225 / 329–340 — thinkerclass_t + specials_e
    // tag bytes.
    public static final int TC_END         = 0;
    public static final int TC_MOBJ        = 1;
    public static final int TC_CEILING     = 0;
    public static final int TC_DOOR        = 1;
    public static final int TC_FLOOR       = 2;
    public static final int TC_PLAT        = 3;
    public static final int TC_FLASH       = 4;
    public static final int TC_STROBE      = 5;
    public static final int TC_GLOW        = 6;
    public static final int TC_ENDSPECIALS = 7;

    // The save_p stream — set by archive/unarchive entry points and
    // read by the per-section helpers. Mirrors the C `byte* save_p`
    // global; only one save/load is in flight at a time.
    public static DataOutputStream out;
    public static DataInputStream in;

    private PSaveg() {
    }

    //
    // Top-level entry points. The C exposes only the per-section
    // helpers; G_DoSaveGame / G_DoLoadGame orchestrate them. The
    // Java port adds the orchestrator here so callers don't need
    // to manage the stream wiring directly.
    //

    /** Serialize the playsim state (players, world, thinkers,
     *  specials) into a byte array. */
    public static byte[] save() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        out = new DataOutputStream(buf);
        try {
            pArchivePlayers();
            pArchiveWorld();
            pArchiveThinkers();
            pArchiveSpecials();
        } finally {
            out = null;
        }
        return buf.toByteArray();
    }

    /** Restore the playsim state from `data`. Mutates the global
     *  sectors[] / lines[] / thinkercap / players[] in place. */
    public static void load(byte[] data) throws IOException {
        in = new DataInputStream(new ByteArrayInputStream(data));
        try {
            pUnArchivePlayers();
            pUnArchiveWorld();
            pUnArchiveThinkers();
            pUnArchiveSpecials();
        } finally {
            in = null;
        }
    }

    /** Validate the entire external payload without changing the active world. */
    public static void validate(byte[] data, int sectors, byte[] mapLines, int console)
            throws IOException {
        in = new DataInputStream(new ByteArrayInputStream(data));
        try {
            boolean[] active = new boolean[Doomdef.MAXPLAYERS];
            for (int i = 0; i < active.length; i++) active[i] = in.readBoolean();
            if (!active[console]) throw new IOException("Inactive console player");
            for (boolean seat : active) if (seat) {
                Player player = new Player();
                readPlayer(player);
                index(player.playerstate, 0, 3);
                index(player.readyweapon, 0, Doomdef.NUMWEAPONS);
                if (player.pendingweapon != Doomdef.wp_nochange)
                    index(player.pendingweapon, 0, Doomdef.NUMWEAPONS);
                index(player.fixedcolormap, 0, 32);
            }
            for (int i = 0; i < sectors; i++) {
                in.readShort(); in.readShort();
                index(in.readShort(), 0, doom.render.RData.numflats);
                index(in.readShort(), 0, doom.render.RData.numflats);
                in.readShort(); in.readShort(); in.readShort();
            }
            java.nio.ByteBuffer topology = java.nio.ByteBuffer.wrap(mapLines)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int offset = 0; offset < mapLines.length; offset += 14) {
                in.skipNBytes(6);
                for (int side = 0; side < 2; side++) {
                    if (topology.getShort(offset + 10 + side * 2) == -1) continue;
                    in.skipNBytes(4);
                    for (int texture = 0; texture < 3; texture++)
                        index(in.readShort(), 0, doom.render.RData.numtextures);
                }
            }
            boolean[] found = new boolean[Doomdef.MAXPLAYERS];
            int tag;
            while ((tag = in.readUnsignedByte()) != TC_END) {
                if (tag != TC_MOBJ) throw new IOException("Unknown thinker tag");
                in.skipNBytes(16);
                int sprite = in.readInt();
                index(sprite, 0, doom.render.RThings.numsprites);
                int frame = in.readInt() & Mobj.FF_FRAMEMASK;
                index(frame, 0, doom.render.RThings.sprites[sprite].numframes);
                int seat = in.readInt();
                index(seat, 0, Doomdef.MAXPLAYERS + 1);
                if (seat > 0) {
                    if (!active[seat - 1] || found[seat - 1]) throw new IOException("Invalid player mobj");
                    found[seat - 1] = true;
                }
                index(in.readInt(), 0, Info.mobjinfo.length);
                in.skipNBytes(7 * 4L);
                index(in.readInt(), -1, Info.states.length);
                in.skipNBytes(7 * 4L);
                if (in.readBoolean()) in.skipNBytes(20);
            }
            for (int i = 0; i < active.length; i++)
                if (active[i] && !found[i]) throw new IOException("Missing player mobj");
            int ceilings = 0, plats = 0;
            boolean[] occupied = new boolean[sectors];
            while ((tag = in.readUnsignedByte()) != TC_ENDSPECIALS) {
                switch (tag) {
                    case TC_CEILING -> {
                        if (++ceilings > PCeilng.MAXCEILINGS) throw new IOException("Too many ceilings");
                        in.readBoolean(); index(in.readInt(), 0, 6); moverSector(occupied);
                        in.skipNBytes(12); in.readBoolean(); in.skipNBytes(12);
                    }
                    case TC_DOOR -> { index(in.readInt(), 0, 8); moverSector(occupied); in.skipNBytes(20); }
                    case TC_FLOOR -> {
                        index(in.readInt(), 0, 13); in.readBoolean(); moverSector(occupied);
                        in.skipNBytes(8); index(in.readInt(), 0, doom.render.RData.numflats);
                        in.skipNBytes(8);
                    }
                    case TC_PLAT -> {
                        if (++plats > PPlats.MAXPLATS) throw new IOException("Too many platforms");
                        in.readBoolean(); moverSector(occupied); in.skipNBytes(20);
                        index(in.readInt(), 0, 4); index(in.readInt(), 0, 4);
                        in.readBoolean(); in.readInt(); index(in.readInt(), 0, 5);
                    }
                    case TC_FLASH -> { sector(sectors); in.skipNBytes(20); }
                    case TC_STROBE -> { sector(sectors); in.skipNBytes(20); }
                    case TC_GLOW -> { sector(sectors); in.skipNBytes(12); }
                    default -> throw new IOException("Unknown special tag");
                }
            }
            if (in.available() != 0) throw new IOException("Trailing save data");
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("Invalid saved index", e);
        } finally {
            in = null;
        }
    }

    private static void moverSector(boolean[] occupied) throws IOException {
        int sector = in.readInt();
        index(sector, 0, occupied.length);
        if (occupied[sector]) throw new IOException("Duplicate sector mover");
        occupied[sector] = true;
    }

    private static void sector(int count) throws IOException { index(in.readInt(), 0, count); }

    private static void index(int value, int min, int max) throws IOException {
        if (value < min || value >= max) throw new IOException("Invalid saved index: " + value);
    }

    //
    // P_ArchivePlayers
    // p_saveg.c lines 47–72.
    //
    public static void pArchivePlayers() throws IOException {
        // Active-seat bitmask first (C reads playeringame[] separately
        // in g_game.c; the Java port keeps the seats coupled with the
        // payload so a single buffer round-trips cleanly).
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            out.writeBoolean(GGame.playeringame[i]);
        }
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            if (!GGame.playeringame[i]) {
                continue;
            }
            writePlayer(GGame.players[i]);
        }
    }

    public static void pUnArchivePlayers() throws IOException {
        boolean[] active = new boolean[Doomdef.MAXPLAYERS];
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            active[i] = in.readBoolean();
        }
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            if (!active[i]) {
                continue;
            }
            // C: memcpy then null mo / message / attacker. Java:
            // overwrite into the existing Player slot.
            readPlayer(GGame.players[i]);
            GGame.players[i].mo = null;
            GGame.players[i].message = null;
            GGame.players[i].attacker = null;
        }
        // Also restore which seats are in game (C doesn't, but
        // since we paired them with the payload we should).
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            GGame.playeringame[i] = active[i];
        }
    }

    private static void writePlayer(Player p) throws IOException {
        out.writeInt(p.playerstate);
        // Ticcmd
        out.writeInt(p.cmd.forwardmove);
        out.writeInt(p.cmd.sidemove);
        out.writeInt(p.cmd.angleturn);
        out.writeInt(p.cmd.consistancy);
        out.writeInt(p.cmd.chatchar);
        out.writeInt(p.cmd.buttons);
        out.writeInt(p.viewz);
        out.writeInt(p.viewheight);
        out.writeInt(p.deltaviewheight);
        out.writeInt(p.bob);
        out.writeInt(p.health);
        out.writeInt(p.armorpoints);
        out.writeInt(p.armortype);
        for (int i = 0; i < Doomdef.NUMPOWERS; i++) {
            out.writeInt(p.powers[i]);
        }
        for (int i = 0; i < Doomdef.NUMCARDS; i++) {
            out.writeBoolean(p.cards[i]);
        }
        out.writeBoolean(p.backpack);
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            out.writeInt(p.frags[i]);
        }
        out.writeInt(p.readyweapon);
        out.writeInt(p.pendingweapon);
        for (int i = 0; i < Doomdef.NUMWEAPONS; i++) {
            out.writeBoolean(p.weaponowned[i]);
        }
        for (int i = 0; i < Doomdef.NUMAMMO; i++) {
            out.writeInt(p.ammo[i]);
        }
        for (int i = 0; i < Doomdef.NUMAMMO; i++) {
            out.writeInt(p.maxammo[i]);
        }
        out.writeInt(p.attackdown);
        out.writeInt(p.usedown);
        out.writeInt(p.cheats);
        out.writeInt(p.refire);
        out.writeInt(p.killcount);
        out.writeInt(p.itemcount);
        out.writeInt(p.secretcount);
        out.writeInt(p.damagecount);
        out.writeInt(p.bonuscount);
        out.writeInt(p.extralight);
        out.writeInt(p.fixedcolormap);
        // psprites[]: state index (-1 = null), tics, sx, sy.
        for (int j = 0; j < PPspr.NUMPSPRITES; j++) {
            Pspdef psp = p.psprites[j];
            out.writeInt(psp.state == null ? -1 : stateIndex(psp.state));
            out.writeInt(psp.tics);
            out.writeInt(psp.sx);
            out.writeInt(psp.sy);
        }
    }

    private static void readPlayer(Player p) throws IOException {
        p.playerstate = in.readInt();
        p.cmd.forwardmove = in.readInt();
        p.cmd.sidemove = in.readInt();
        p.cmd.angleturn = in.readInt();
        p.cmd.consistancy = in.readInt();
        p.cmd.chatchar = in.readInt();
        p.cmd.buttons = in.readInt();
        p.viewz = in.readInt();
        p.viewheight = in.readInt();
        p.deltaviewheight = in.readInt();
        p.bob = in.readInt();
        p.health = in.readInt();
        p.armorpoints = in.readInt();
        p.armortype = in.readInt();
        for (int i = 0; i < Doomdef.NUMPOWERS; i++) {
            p.powers[i] = in.readInt();
        }
        for (int i = 0; i < Doomdef.NUMCARDS; i++) {
            p.cards[i] = in.readBoolean();
        }
        p.backpack = in.readBoolean();
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            p.frags[i] = in.readInt();
        }
        p.readyweapon = in.readInt();
        p.pendingweapon = in.readInt();
        for (int i = 0; i < Doomdef.NUMWEAPONS; i++) {
            p.weaponowned[i] = in.readBoolean();
        }
        for (int i = 0; i < Doomdef.NUMAMMO; i++) {
            p.ammo[i] = in.readInt();
        }
        for (int i = 0; i < Doomdef.NUMAMMO; i++) {
            p.maxammo[i] = in.readInt();
        }
        p.attackdown = in.readInt();
        p.usedown = in.readInt();
        p.cheats = in.readInt();
        p.refire = in.readInt();
        p.killcount = in.readInt();
        p.itemcount = in.readInt();
        p.secretcount = in.readInt();
        p.damagecount = in.readInt();
        p.bonuscount = in.readInt();
        p.extralight = in.readInt();
        p.fixedcolormap = in.readInt();
        for (int j = 0; j < PPspr.NUMPSPRITES; j++) {
            Pspdef psp = p.psprites[j];
            int idx = in.readInt();
            psp.state = (idx == -1) ? null : Info.states[idx];
            psp.tics = in.readInt();
            psp.sx = in.readInt();
            psp.sy = in.readInt();
        }
    }

    //
    // P_ArchiveWorld
    // p_saveg.c lines 114–160.
    //
    public static void pArchiveWorld() throws IOException {
        // sectors
        for (int i = 0; i < PSetup.numsectors; i++) {
            Sector sec = PSetup.sectors[i];
            out.writeShort(sec.floorheight >> Fixed.FRACBITS);
            out.writeShort(sec.ceilingheight >> Fixed.FRACBITS);
            out.writeShort(sec.floorpic);
            out.writeShort(sec.ceilingpic);
            out.writeShort(sec.lightlevel);
            out.writeShort(sec.special);
            out.writeShort(sec.tag);
        }

        // lines
        for (int i = 0; i < PSetup.numlines; i++) {
            Line li = PSetup.lines[i];
            out.writeShort(li.flags);
            out.writeShort(li.special);
            out.writeShort(li.tag);
            for (int j = 0; j < 2; j++) {
                if (li.sidenum[j] == -1) {
                    continue;
                }
                Side si = PSetup.sides[li.sidenum[j]];
                out.writeShort(si.textureoffset >> Fixed.FRACBITS);
                out.writeShort(si.rowoffset >> Fixed.FRACBITS);
                out.writeShort(si.toptexture);
                out.writeShort(si.bottomtexture);
                out.writeShort(si.midtexture);
            }
        }
    }

    public static void pUnArchiveWorld() throws IOException {
        for (int i = 0; i < PSetup.numsectors; i++) {
            Sector sec = PSetup.sectors[i];
            sec.floorheight = ((int) in.readShort()) << Fixed.FRACBITS;
            sec.ceilingheight = ((int) in.readShort()) << Fixed.FRACBITS;
            sec.floorpic = in.readShort();
            sec.ceilingpic = in.readShort();
            sec.lightlevel = in.readShort();
            sec.special = in.readShort();
            sec.tag = in.readShort();
            sec.specialdata = null;
            sec.soundtarget = null;
        }

        for (int i = 0; i < PSetup.numlines; i++) {
            Line li = PSetup.lines[i];
            li.flags = in.readShort() & 0xFFFF;
            li.special = in.readShort();
            li.tag = in.readShort();
            for (int j = 0; j < 2; j++) {
                if (li.sidenum[j] == -1) {
                    continue;
                }
                Side si = PSetup.sides[li.sidenum[j]];
                si.textureoffset = ((int) in.readShort()) << Fixed.FRACBITS;
                si.rowoffset = ((int) in.readShort()) << Fixed.FRACBITS;
                si.toptexture = in.readShort();
                si.bottomtexture = in.readShort();
                si.midtexture = in.readShort();
            }
        }
    }

    //
    // P_ArchiveThinkers
    // p_saveg.c lines 232–259.
    //
    // The C uses `function.acp1 == P_MobjThinker` to filter — Java
    // checks `instanceof Mobj` for the same effect.
    public static void pArchiveThinkers() throws IOException {
        for (Thinker th = PTick.thinkercap.next;
                th != PTick.thinkercap;
                th = th.next) {
            if (th instanceof Mobj) {
                out.writeByte(TC_MOBJ);
                writeMobj((Mobj) th);
            }
        }
        // terminating marker
        out.writeByte(TC_END);
    }

    public static void pUnArchiveThinkers() throws IOException {
        // remove all the current thinkers
        Thinker currentthinker = PTick.thinkercap.next;
        while (currentthinker != PTick.thinkercap) {
            Thinker next = currentthinker.next;
            if (currentthinker instanceof Mobj) {
                PMobj.pRemoveMobj((Mobj) currentthinker);
            }
            // Non-mobjs: dropped via P_InitThinkers below — Java GC
            // reclaims once unlinked. The C calls Z_Free here.
            currentthinker = next;
        }
        PTick.pInitThinkers();

        // read in saved thinkers
        while (true) {
            int tclass = in.readByte() & 0xFF;
            if (tclass == TC_END) {
                return;
            }
            if (tclass == TC_MOBJ) {
                Mobj mobj = readMobj();
                PMaputl.pSetThingPosition(mobj);
                mobj.info = Info.mobjinfo[mobj.type];
                mobj.floorz = mobj.subsector.sector.floorheight;
                mobj.ceilingz = mobj.subsector.sector.ceilingheight;
                // C: re-bind thinker.function.acp1 = P_MobjThinker.
                mobj.function.acp1 = PMobj::pMobjThinker;
                PTick.pAddThinker(mobj);
                continue;
            }
            ISystem.iError("Unknown tclass " + tclass + " in savegame");
        }
    }

    private static void writeMobj(Mobj m) throws IOException {
        out.writeInt(m.x);
        out.writeInt(m.y);
        out.writeInt(m.z);
        out.writeInt(m.angle);
        out.writeInt(m.sprite);
        out.writeInt(m.frame);
        // 1-based player seat (0 = no player). Matches C's
        // (player - players + 1) swizzle.
        out.writeInt(m.player == null ? 0 : playerIndex(m.player) + 1);
        out.writeInt(m.type);
        out.writeInt(m.radius);
        out.writeInt(m.height);
        out.writeInt(m.flags);
        out.writeInt(m.momx);
        out.writeInt(m.momy);
        out.writeInt(m.momz);
        out.writeInt(m.tics);
        out.writeInt(m.state == null ? -1 : stateIndex(m.state));
        out.writeInt(m.health);
        out.writeInt(m.validcount);
        out.writeInt(m.movedir);
        out.writeInt(m.movecount);
        out.writeInt(m.reactiontime);
        out.writeInt(m.threshold);
        out.writeInt(m.lastlook);
        // Spawnpoint: nullable. Flag + 5 ints if present.
        if (m.spawnpoint == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeInt(m.spawnpoint.x);
            out.writeInt(m.spawnpoint.y);
            out.writeInt(m.spawnpoint.angle);
            out.writeInt(m.spawnpoint.type);
            out.writeInt(m.spawnpoint.options);
        }
        // C target / tracer pointers: target is reset to null on
        // unarchive (matches C); tracer the C preserves as a stale
        // pointer (a known savegame bug). Java drops both.
    }

    private static Mobj readMobj() throws IOException {
        Mobj mobj = new Mobj();
        mobj.x = in.readInt();
        mobj.y = in.readInt();
        mobj.z = in.readInt();
        mobj.angle = in.readInt();
        mobj.sprite = in.readInt();
        mobj.frame = in.readInt();
        int playerSeat = in.readInt();
        if (playerSeat != 0) {
            mobj.player = GGame.players[playerSeat - 1];
            mobj.player.mo = mobj;
        }
        mobj.type = in.readInt();
        mobj.radius = in.readInt();
        mobj.height = in.readInt();
        mobj.flags = in.readInt();
        mobj.momx = in.readInt();
        mobj.momy = in.readInt();
        mobj.momz = in.readInt();
        mobj.tics = in.readInt();
        int stateIdx = in.readInt();
        mobj.state = (stateIdx == -1) ? null : Info.states[stateIdx];
        mobj.health = in.readInt();
        mobj.validcount = in.readInt();
        mobj.movedir = in.readInt();
        mobj.movecount = in.readInt();
        mobj.reactiontime = in.readInt();
        mobj.threshold = in.readInt();
        mobj.lastlook = in.readInt();
        if (in.readBoolean()) {
            Mapthing mt = new Mapthing();
            mt.x = in.readInt();
            mt.y = in.readInt();
            mt.angle = in.readInt();
            mt.type = in.readInt();
            mt.options = in.readInt();
            mobj.spawnpoint = mt;
        }
        // target / tracer null-init by Mobj's default — matches C
        // post-unarchive state.
        return mobj;
    }

    //
    // P_ArchiveSpecials
    // p_saveg.c lines 355–469.
    //
    public static void pArchiveSpecials() throws IOException {
        for (Thinker th = PTick.thinkercap.next;
                th != PTick.thinkercap;
                th = th.next) {
            // Paused ceiling — C: function.acv == NULL → tc_ceiling.
            // Java: a Ceiling with acp1 == null is in stasis.
            if (th instanceof PCeilng.Ceiling && th.function.acp1 == null) {
                // verify it's in the active-ceiling slot table
                boolean active = false;
                for (int i = 0; i < PCeilng.MAXCEILINGS; i++) {
                    if (PCeilng.activeceilings[i] == th) {
                        active = true;
                        break;
                    }
                }
                if (active) {
                    out.writeByte(TC_CEILING);
                    writeCeiling((PCeilng.Ceiling) th);
                }
                continue;
            }

            if (th instanceof PCeilng.Ceiling) {
                out.writeByte(TC_CEILING);
                writeCeiling((PCeilng.Ceiling) th);
                continue;
            }

            if (th instanceof PDoors.VLDoor) {
                out.writeByte(TC_DOOR);
                writeDoor((PDoors.VLDoor) th);
                continue;
            }

            if (th instanceof PFloor.FloorMove) {
                out.writeByte(TC_FLOOR);
                writeFloor((PFloor.FloorMove) th);
                continue;
            }

            if (th instanceof PPlats.Plat) {
                out.writeByte(TC_PLAT);
                writePlat((PPlats.Plat) th);
                continue;
            }

            if (th instanceof PLights.LightFlash) {
                out.writeByte(TC_FLASH);
                writeFlash((PLights.LightFlash) th);
                continue;
            }

            if (th instanceof PLights.Strobe) {
                out.writeByte(TC_STROBE);
                writeStrobe((PLights.Strobe) th);
                continue;
            }

            if (th instanceof PLights.Glow) {
                out.writeByte(TC_GLOW);
                writeGlow((PLights.Glow) th);
                continue;
            }
            // Mobj is handled by ArchiveThinkers; FireFlicker has no
            // C savegame entry (a known C omission).
        }

        out.writeByte(TC_ENDSPECIALS);
    }

    public static void pUnArchiveSpecials() throws IOException {
        java.util.Arrays.fill(PCeilng.activeceilings, null);
        java.util.Arrays.fill(PPlats.activeplats, null);
        while (true) {
            int tclass = in.readByte() & 0xFF;
            switch (tclass) {
                case TC_ENDSPECIALS:
                    return;

                case TC_CEILING: {
                    PCeilng.Ceiling ceiling = new PCeilng.Ceiling();
                    readCeiling(ceiling);
                    ceiling.sector.specialdata = ceiling;
                    if (ceiling.function.acp1 != null) {
                        // Restore method ref (C: function.acp1 = T_MoveCeiling)
                        ceiling.function.acp1 = PCeilng::tMoveCeiling;
                    }
                    PTick.pAddThinker(ceiling);
                    PCeilng.pAddActiveCeiling(ceiling);
                    break;
                }

                case TC_DOOR: {
                    PDoors.VLDoor door = new PDoors.VLDoor();
                    readDoor(door);
                    door.sector.specialdata = door;
                    door.function.acp1 = PDoors::tVerticalDoor;
                    PTick.pAddThinker(door);
                    break;
                }

                case TC_FLOOR: {
                    PFloor.FloorMove floor = new PFloor.FloorMove();
                    readFloor(floor);
                    floor.sector.specialdata = floor;
                    floor.function.acp1 = PFloor::tMoveFloor;
                    PTick.pAddThinker(floor);
                    break;
                }

                case TC_PLAT: {
                    PPlats.Plat plat = new PPlats.Plat();
                    readPlat(plat);
                    plat.sector.specialdata = plat;
                    if (plat.function.acp1 != null) {
                        plat.function.acp1 = PPlats::tPlatRaise;
                    }
                    PTick.pAddThinker(plat);
                    PPlats.pAddActivePlat(plat);
                    break;
                }

                case TC_FLASH: {
                    PLights.LightFlash flash = new PLights.LightFlash();
                    readFlash(flash);
                    flash.function.acp1 = PLights::tLightFlash;
                    PTick.pAddThinker(flash);
                    break;
                }

                case TC_STROBE: {
                    PLights.Strobe strobe = new PLights.Strobe();
                    readStrobe(strobe);
                    strobe.function.acp1 = PLights::tStrobeFlash;
                    PTick.pAddThinker(strobe);
                    break;
                }

                case TC_GLOW: {
                    PLights.Glow glow = new PLights.Glow();
                    readGlow(glow);
                    glow.function.acp1 = PLights::tGlow;
                    PTick.pAddThinker(glow);
                    break;
                }

                default:
                    ISystem.iError("P_UnarchiveSpecials: Unknown tclass "
                            + tclass + " in savegame");
            }
        }
    }

    // --- Ceiling ---
    private static void writeCeiling(PCeilng.Ceiling c) throws IOException {
        out.writeBoolean(c.function.acp1 != null); // pause flag
        out.writeInt(c.type);
        out.writeInt(sectorIndex(c.sector));
        out.writeInt(c.bottomheight);
        out.writeInt(c.topheight);
        out.writeInt(c.speed);
        out.writeBoolean(c.crush);
        out.writeInt(c.direction);
        out.writeInt(c.tag);
        out.writeInt(c.olddirection);
    }

    private static void readCeiling(PCeilng.Ceiling c) throws IOException {
        boolean active = in.readBoolean();
        c.function.acp1 = active ? PCeilng::tMoveCeiling : null;
        c.type = in.readInt();
        c.sector = PSetup.sectors[in.readInt()];
        c.bottomheight = in.readInt();
        c.topheight = in.readInt();
        c.speed = in.readInt();
        c.crush = in.readBoolean();
        c.direction = in.readInt();
        c.tag = in.readInt();
        c.olddirection = in.readInt();
    }

    // --- Door ---
    private static void writeDoor(PDoors.VLDoor d) throws IOException {
        out.writeInt(d.type);
        out.writeInt(sectorIndex(d.sector));
        out.writeInt(d.topheight);
        out.writeInt(d.speed);
        out.writeInt(d.direction);
        out.writeInt(d.topwait);
        out.writeInt(d.topcountdown);
    }

    private static void readDoor(PDoors.VLDoor d) throws IOException {
        d.type = in.readInt();
        d.sector = PSetup.sectors[in.readInt()];
        d.topheight = in.readInt();
        d.speed = in.readInt();
        d.direction = in.readInt();
        d.topwait = in.readInt();
        d.topcountdown = in.readInt();
    }

    // --- Floor ---
    private static void writeFloor(PFloor.FloorMove f) throws IOException {
        out.writeInt(f.type);
        out.writeBoolean(f.crush);
        out.writeInt(sectorIndex(f.sector));
        out.writeInt(f.direction);
        out.writeInt(f.newspecial);
        out.writeInt(f.texture);
        out.writeInt(f.floordestheight);
        out.writeInt(f.speed);
    }

    private static void readFloor(PFloor.FloorMove f) throws IOException {
        f.type = in.readInt();
        f.crush = in.readBoolean();
        f.sector = PSetup.sectors[in.readInt()];
        f.direction = in.readInt();
        f.newspecial = in.readInt();
        f.texture = in.readInt();
        f.floordestheight = in.readInt();
        f.speed = in.readInt();
    }

    // --- Plat ---
    private static void writePlat(PPlats.Plat p) throws IOException {
        out.writeBoolean(p.function.acp1 != null); // pause flag
        out.writeInt(sectorIndex(p.sector));
        out.writeInt(p.speed);
        out.writeInt(p.low);
        out.writeInt(p.high);
        out.writeInt(p.wait);
        out.writeInt(p.count);
        out.writeInt(p.status);
        out.writeInt(p.oldstatus);
        out.writeBoolean(p.crush);
        out.writeInt(p.tag);
        out.writeInt(p.type);
    }

    private static void readPlat(PPlats.Plat p) throws IOException {
        boolean active = in.readBoolean();
        p.function.acp1 = active ? PPlats::tPlatRaise : null;
        p.sector = PSetup.sectors[in.readInt()];
        p.speed = in.readInt();
        p.low = in.readInt();
        p.high = in.readInt();
        p.wait = in.readInt();
        p.count = in.readInt();
        p.status = in.readInt();
        p.oldstatus = in.readInt();
        p.crush = in.readBoolean();
        p.tag = in.readInt();
        p.type = in.readInt();
    }

    // --- Flash / Strobe / Glow ---
    private static void writeFlash(PLights.LightFlash f) throws IOException {
        out.writeInt(sectorIndex(f.sector));
        out.writeInt(f.count);
        out.writeInt(f.maxlight);
        out.writeInt(f.minlight);
        out.writeInt(f.maxtime);
        out.writeInt(f.mintime);
    }

    private static void readFlash(PLights.LightFlash f) throws IOException {
        f.sector = PSetup.sectors[in.readInt()];
        f.count = in.readInt();
        f.maxlight = in.readInt();
        f.minlight = in.readInt();
        f.maxtime = in.readInt();
        f.mintime = in.readInt();
    }

    private static void writeStrobe(PLights.Strobe s) throws IOException {
        out.writeInt(sectorIndex(s.sector));
        out.writeInt(s.count);
        out.writeInt(s.minlight);
        out.writeInt(s.maxlight);
        out.writeInt(s.darktime);
        out.writeInt(s.brighttime);
    }

    private static void readStrobe(PLights.Strobe s) throws IOException {
        s.sector = PSetup.sectors[in.readInt()];
        s.count = in.readInt();
        s.minlight = in.readInt();
        s.maxlight = in.readInt();
        s.darktime = in.readInt();
        s.brighttime = in.readInt();
    }

    private static void writeGlow(PLights.Glow g) throws IOException {
        out.writeInt(sectorIndex(g.sector));
        out.writeInt(g.minlight);
        out.writeInt(g.maxlight);
        out.writeInt(g.direction);
    }

    private static void readGlow(PLights.Glow g) throws IOException {
        g.sector = PSetup.sectors[in.readInt()];
        g.minlight = in.readInt();
        g.maxlight = in.readInt();
        g.direction = in.readInt();
    }

    //
    // helpers — pointer→index swizzle.
    //

    private static int stateIndex(State s) {
        for (int i = 0; i < Info.NUMSTATES; i++) {
            if (Info.states[i] == s) {
                return i;
            }
        }
        return -1;
    }

    private static int sectorIndex(Sector s) {
        for (int i = 0; i < PSetup.numsectors; i++) {
            if (PSetup.sectors[i] == s) {
                return i;
            }
        }
        return -1;
    }

    private static int playerIndex(Player p) {
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            if (GGame.players[i] == p) {
                return i;
            }
        }
        return -1;
    }
}
