package doom.system;

import doom.play.*;
import doom.wad.WWad;
import doom.wad.ZZone;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Versioned Java save envelope. Vanilla saves and pre-header Java saves are incompatible. */
public final class SaveGame {
    private static final int MAGIC = 0x4a465853; // JFXS
    private static final int VERSION = 1;
    private static final int DYNAMIC_VERSION = 2; // Adds generator version and full-width seed.
    private SaveGame() { }

    public static byte[] save() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(MAGIC);
        out.writeInt(Doomstat.dynamicLevels ? DYNAMIC_VERSION : VERSION);
        out.writeInt(Doomstat.gamemode.ordinal());
        out.writeInt(Doomstat.gameskill);
        out.writeInt(Doomstat.gameepisode);
        out.writeInt(Doomstat.gamemap);
        out.writeInt(PTick.leveltime);
        out.writeInt(GGame.consoleplayer);
        out.writeInt(Doomstat.deathmatch);
        out.writeBoolean(Doomstat.fastparm);
        out.writeBoolean(Doomstat.nomonsters);
        out.writeBoolean(Doomstat.respawnmonsters);
        out.writeBoolean(Doomstat.netgame);
        out.writeBoolean(Doomstat.respawnparm);
        out.writeInt(MRandom.rndindex);
        out.writeInt(MRandom.prndindex);
        if (Doomstat.dynamicLevels) {
            out.writeInt(DynamicLevel.VERSION);
            out.writeLong(Doomstat.dynamicSeed);
        }
        out.write(mapDigest(Doomstat.gameepisode, Doomstat.gamemap));
        byte[] body = PSaveg.save();
        out.writeInt(body.length);
        out.write(body);
        CRC32 crc = new CRC32();
        crc.update(buffer.toByteArray());
        out.writeLong(crc.getValue());
        return buffer.toByteArray();
    }

    public static void load(byte[] data) throws IOException {
        // Complete preflight before changing players, map, demo state, or flags.
        if (data.length < 8) throw new IOException("Truncated save header");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int magic = in.readInt(), version = in.readInt();
        if (magic != MAGIC || (version != VERSION && version != DYNAMIC_VERSION))
            throw new IOException("Unsupported save version (legacy saves have no map metadata)");
        if ((version == DYNAMIC_VERSION) != Doomstat.dynamicLevels)
            throw new IOException("Save requires " + (version == DYNAMIC_VERSION ? "-dynamic mode" : "normal WAD mode"));
        if (data.length < 93) throw new IOException("Truncated save");
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length - 8);
        if (crc.getValue() != ByteBuffer.wrap(data, data.length - 8, 8).getLong())
            throw new IOException("Save checksum mismatch");
        int mode = in.readInt(), skill = in.readInt(), episode = in.readInt(), map = in.readInt();
        int time = in.readInt(), seat = in.readInt(), deathmatch = in.readInt();
        boolean fast = in.readBoolean(), nomonsters = in.readBoolean();
        boolean respawn = in.readBoolean(), netgame = in.readBoolean();
        boolean respawnRequest = in.readBoolean();
        int random = in.readInt(), playRandom = in.readInt();
        if (mode != Doomstat.gamemode.ordinal() || skill < 0 || skill > Doomstat.sk_nightmare
                || episode < 1 || episode > 4 || map < 1 || map > 32 || time < 0
                || seat < 0 || seat >= Doomdef.MAXPLAYERS || deathmatch < 0 || deathmatch > 2
                || random < 0 || random > 255 || playRandom < 0 || playRandom > 255)
            throw new IOException("Invalid save metadata or incompatible game mode");
        long dynamicSeed = 0;
        byte[][] generated = null;
        if (version == DYNAMIC_VERSION) {
            if (in.readInt() != DynamicLevel.VERSION) throw new IOException("Unsupported dynamic generator version");
            dynamicSeed = in.readLong();
            generated = DynamicLevel.generate(dynamicSeed);
        }
        byte[] digest = in.readNBytes(32);
        if (!Arrays.equals(digest, generated == null ? mapDigest(episode, map) : generatedDigest(generated)))
            throw new IOException("Save map differs from loaded WADs");
        int length = in.readInt();
        if (length < 0 || length != in.available() - 8) throw new IOException("Invalid save length");
        byte[] body = in.readNBytes(length);
        int base = mapLump(episode, map);
        byte[] sectors = generated == null ? WWad.wCacheLumpNum(base + PSetup.ML_SECTORS, ZZone.PU_CACHE)
                : generated[PSetup.ML_SECTORS];
        byte[] lines = generated == null ? WWad.wCacheLumpNum(base + PSetup.ML_LINEDEFS, ZZone.PU_CACHE)
                : generated[PSetup.ML_LINEDEFS];
        PSaveg.validate(body, sectors.length / 26, lines, seat);

        if (generated != null) DynamicLevel.restore(dynamicSeed, generated);
        Doomstat.respawnparm = respawnRequest;
        Doomstat.fastparm = fast;
        Doomstat.nomonsters = nomonsters;
        Doomstat.respawnmonsters = respawn;
        Doomstat.netgame = netgame;
        Doomstat.deathmatch = deathmatch;
        GGame.consoleplayer = seat;
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) GGame.playeringame[i] = body[i] != 0;
        DMain.advancedemo = false;
        Doomstat.demoplayback = false;
        Doomstat.netdemo = false;
        GGame.demorecording = false;
        GGame.gInitNew(skill, episode, map);
        GGame.gDoLoadLevel();
        PSaveg.load(body);
        Doomstat.respawnmonsters = respawn;
        PTick.leveltime = time;
        MRandom.rndindex = random;
        MRandom.prndindex = playRandom;
        doom.ui.AmMap.automapactive = false;
        doom.ui.HuStuff.huStart();
        doom.ui.StStuff.stStart();
    }

    private static int mapLump(int episode, int map) throws IOException {
        String name = Doomstat.dynamicLevels ? DynamicLevel.MAP_NAME
                : Doomstat.gamemode == Doomstat.GameMode.commercial
                ? String.format("MAP%02d", map) : "E" + episode + "M" + map;
        int lump = WWad.wCheckNumForName(name);
        if (lump < 0) throw new IOException("Saved map is unavailable: " + name);
        return lump;
    }

    private static byte[] generatedDigest(byte[][] lumps) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 1; i <= PSetup.ML_BLOCKMAP; i++) {
                digest.update(lumps[i]);
                // Match W_CacheLumpNum's four-byte zone allocation padding.
                for (int pad = lumps[i].length; (pad & 3) != 0; pad++) digest.update((byte) 0);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] mapDigest(int episode, int map) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int base = mapLump(episode, map);
            for (int i = 1; i <= PSetup.ML_BLOCKMAP; i++)
                digest.update(WWad.wCacheLumpNum(base + i, ZZone.PU_CACHE));
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
