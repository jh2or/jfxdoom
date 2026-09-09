package doom.play;

import doom.render.RData;
import doom.system.ISystem;
import doom.wad.WWad;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Port extension: a generated, single-sector arena using the mounted WAD's assets.
 * Emits ordinary Doom map lumps so rendering, collision, spawning and saving all
 * use the existing engine paths. A convex room needs one subsector and no nodes.
 * The initial prototype is deliberately deterministic, including thing placement.
 */
public final class DynamicLevel {
    public static final String MAP_NAME = "DYNROOM";
    private static final int HALF_SIZE = 768;

    private DynamicLevel() { }

    /** Called once per WAD mount, after R_InitData has resolved materials. */
    public static void mount() {
        String wall = wallTexture();
        String floor = flat("FLOOR0_1");
        String ceiling = flat("CEIL3_5");
        // Clockwise: the room interior is on the front (right) of every wall.
        int[][] corners = {
                {-HALF_SIZE, -HALF_SIZE}, {-HALF_SIZE, HALF_SIZE},
                {HALF_SIZE, HALF_SIZE}, {HALF_SIZE, -HALF_SIZE}
        };
        ByteBuffer vertices = buffer(4 * 4);
        ByteBuffer lines = buffer(4 * 14);
        ByteBuffer sides = buffer(4 * 30);
        ByteBuffer segs = buffer(4 * 12);
        int[] angles = {16384, 0, 49152, 32768};
        for (int i = 0; i < 4; i++) {
            shorts(vertices, corners[i][0], corners[i][1]);
            shorts(lines, i, (i + 1) % 4, 1, 0, 0, i, -1);
            shorts(sides, 0, 0);
            name(sides, "-");
            name(sides, "-");
            name(sides, wall);
            shorts(sides, 0);
            shorts(segs, i, (i + 1) % 4, angles[i], i, 0, 0);
        }
        ByteBuffer sectors = buffer(26);
        shorts(sectors, 0, 128);
        name(sectors, floor);
        name(sectors, ceiling);
        shorts(sectors, 192, 0, 0);
        ByteBuffer subsectors = buffer(4);
        shorts(subsectors, 4, 0);

        int[][] placements = {
                {-512, 0, 0, 1},       // Player 1, facing east.
                {-384, 0, 0, 2001},    // Shotgun.
                {-320, -64, 0, 2049},  // Boxes of shells.
                {-320, 64, 0, 2049},
                {-576, -128, 0, 2012}, // Medkits behind the start.
                {-576, 128, 0, 2012},
                {128, -256, 180, 3004}, // Former humans.
                {128, 256, 180, 3004},
                {384, -384, 180, 9},    // Shotgun guys.
                {384, 384, 180, 9},
                {512, -128, 180, 3001}, // Imps.
                {512, 128, 180, 3001}
        };
        ByteBuffer things = buffer(placements.length * 10);
        for (int[] thing : placements) {
            shorts(things, thing[0], thing[1], thing[2], thing[3], 7);
        }
        WWad.wAddMemoryLumps(new String[] {
                MAP_NAME, "THINGS", "LINEDEFS", "SIDEDEFS", "VERTEXES",
                "SEGS", "SSECTORS", "NODES", "SECTORS", "REJECT", "BLOCKMAP"
        }, new byte[][] {
                new byte[0], things.array(), lines.array(), sides.array(), vertices.array(),
                segs.array(), subsectors.array(), new byte[0], sectors.array(),
                new byte[1], blockmap()
        });
    }

    private static byte[] blockmap() {
        // One block of padding beyond the walls for radius-based collision queries.
        int origin = -HALF_SIZE - 128;
        int width = HALF_SIZE * 2 / 128 + 3;
        int cells = width * width;
        int list = 4 + cells;
        ByteBuffer out = buffer((list + 6) * 2);
        shorts(out, origin, origin, width, width);
        // With only four lines, a shared conservative list is cheap and complete.
        // Leading zero is the Doom blocklist header; -1 terminates the list.
        for (int i = 0; i < cells; i++) shorts(out, list);
        shorts(out, 0, 0, 1, 2, 3, -1);
        return out.array();
    }

    private static String wallTexture() {
        for (String name : new String[] {"STARTAN3", "STARTAN2", "STONE2"}) {
            if (RData.rCheckTextureNumForName(name) > 0) return name;
        }
        // Texture zero denotes "no texture" in several engine paths.
        for (int i = 1; i < RData.numtextures; i++) {
            if (RData.textures[i].patchcount > 0) return RData.textures[i].name;
        }
        throw ISystem.iError("Dynamic levels need a wall texture in the loaded WADs");
    }

    private static String flat(String preferred) {
        int lump = WWad.wCheckNumForName(preferred);
        if (lump >= RData.firstflat && lump <= RData.lastflat
                && WWad.wLumpLength(lump) == 4096) return preferred;
        for (int i = RData.firstflat; i <= RData.lastflat; i++) {
            if (!WWad.lumpinfo[i].name.equals("F_SKY1") && WWad.wLumpLength(i) == 4096)
                return WWad.lumpinfo[i].name;
        }
        throw ISystem.iError("Dynamic levels need a floor flat in the loaded WADs");
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void shorts(ByteBuffer out, int... values) {
        for (int value : values) out.putShort((short) value);
    }

    private static void name(ByteBuffer out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        out.put(bytes);
        for (int i = bytes.length; i < 8; i++) out.put((byte) 0);
    }
}
