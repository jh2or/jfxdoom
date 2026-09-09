package doom.play;

import doom.render.RData;
import doom.system.Doomstat;
import doom.system.ISystem;
import doom.wad.WWad;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Seeded, offline level planning; geometry is compiled by GridMapBuilder.
 * Generation owns its PRNG and never consumes Doom's gameplay random table.
 * Bump VERSION whenever rules or random draw order change reproducible output.
 */
public final class DynamicLevel {
    public static final int VERSION = 1;
    public static final String MAP_NAME = "DYNROOM";
    private static final String[] LUMP_NAMES = {MAP_NAME, "THINGS", "LINEDEFS", "SIDEDEFS", "VERTEXES",
            "SEGS", "SSECTORS", "NODES", "SECTORS", "REJECT", "BLOCKMAP"};
    static final int START = 0, EXIT = 8, REWARD = 6;
    static final int SIZE = 30;
    record Room(int x, int y, int rx, int ry) {
        int worldX() { return x * 128 + 64; }
        int worldY() { return y * 128 + 64; }
    }
    record Connection(int a, int b) { }
    record Area(int floor, int ceiling, int light, int style) { }
    record Layout(int[][] cells, List<Room> rooms, List<Connection> connections,
                  List<Area> areas, List<GridMapBuilder.Thing> things, int keyRoom,
                  int doorSector, int doorX, int doorY) { }

    private DynamicLevel() { }

    public static void mount() {
        WWad.wAddMemoryLumps(LUMP_NAMES, generate(Doomstat.dynamicSeed));
    }

    /** Pure with respect to engine state: only reads the mounted material tables. */
    public static byte[][] generate(long seed) {
        Layout layout = plan(seed);
        String[] walls = {texture("STARTAN3", "STARTAN2"), texture("BROWN1", "STONE2"),
                texture("COMPTALL", "STARTAN3"), texture("METAL1", "STONE2")};
        String[] floors = {flat("FLOOR0_1"), flat("FLOOR4_8"), flat("FLAT5_4")};
        String[] ceilings = {flat("CEIL3_5"), flat("CEIL5_1")};
        List<GridMapBuilder.Region> regions = new ArrayList<>();
        for (Area area : layout.areas) regions.add(new GridMapBuilder.Region(area.floor,
                area.ceiling, area.light, walls[area.style % walls.length],
                floors[area.style % floors.length], ceilings[area.style % ceilings.length]));
        Room exit = layout.rooms.get(EXIT);
        return new GridMapBuilder(layout.cells, regions, layout.doorSector,
                exit.x + exit.rx, exit.y, texture("DOORBLU", "BIGDOOR2"),
                texture("SW1EXIT", "SW1BRCOM")).compile(layout.things);
    }

    /** Save preflight generates and validates data before replacing the active map. */
    public static void restore(long seed, byte[][] lumps) {
        WWad.wReplaceMemoryLumps(WWad.wGetNumForName(MAP_NAME), lumps);
        Doomstat.dynamicSeed = seed;
    }

    static Layout plan(long seed) {
        SeedRandom random = new SeedRandom(seed);
        List<Room> rooms = new ArrayList<>();
        List<Area> areas = new ArrayList<>();
        int[][] cells = new int[SIZE][SIZE];
        for (int[] row : cells) Arrays.fill(row, -1);
        for (int i = 0; i < 9; i++) {
            Room room = new Room(4 + (i % 3) * 10, 4 + (i / 3) * 10,
                    2 + random.nextInt(2), 2 + random.nextInt(2));
            rooms.add(room);
            int floor = i == START || i == EXIT ? 0 : random.nextInt(3) * 8;
            areas.add(new Area(floor, 128 + random.nextInt(3) * 32,
                    144 + random.nextInt(5) * 16, random.nextInt(4)));
            for (int y = room.y - room.ry; y <= room.y + room.ry; y++)
                for (int x = room.x - room.rx; x <= room.x + room.rx; x++) cells[y][x] = i;
        }
        // A random spanning tree over seven main rooms, then one or two extra edges.
        // Exit and reward rooms are leaves, so neither can bypass progression.
        List<Connection> candidates = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            if (i % 3 < 2) candidates.add(new Connection(i, i + 1));
            if (i / 3 < 2) candidates.add(new Connection(i, i + 3));
        }
        shuffle(candidates, random);
        int[] components = new int[9];
        for (int i = 0; i < 9; i++) components[i] = i;
        List<Connection> connections = new ArrayList<>(), extra = new ArrayList<>();
        for (Connection edge : candidates) {
            if (terminal(edge.a) || terminal(edge.b)) continue;
            int a = component(components, edge.a), b = component(components, edge.b);
            if (a != b) {
                components[a] = b;
                connections.add(edge);
            } else extra.add(edge);
        }
        connections.addAll(extra.subList(0, Math.min(1 + random.nextInt(2), extra.size())));
        connections.add(new Connection(random.nextInt(2) == 0 ? 3 : 7, REWARD));
        connections.add(new Connection(random.nextInt(2) == 0 ? 5 : 7, EXIT));
        int keyRoom = 1;
        int[] distance = distances(connections, START);
        for (int i = 1; i < 9; i++)
            if (!terminal(i) && distance[i] > distance[keyRoom]) keyRoom = i;
        int doorX = -1, doorY = -1;
        for (Connection edge : connections) {
            Room a = rooms.get(edge.a), b = rooms.get(edge.b);
            int region = areas.size();
            areas.add(new Area(0, 128, 144 + random.nextInt(3) * 16, random.nextInt(4)));
            for (int y = Math.min(a.y, b.y); y <= Math.max(a.y, b.y); y++)
                for (int x = Math.min(a.x, b.x); x <= Math.max(a.x, b.x); x++)
                    if (cells[y][x] < 0) cells[y][x] = region;
            if (edge.b == EXIT) { doorX = (a.x + b.x) / 2; doorY = (a.y + b.y) / 2; }
        }
        int doorSector = areas.size();
        areas.add(new Area(0, 0, 176, 3));
        cells[doorY][doorX] = doorSector;
        List<GridMapBuilder.Thing> things = new ArrayList<>();
        Room start = rooms.get(START);
        thing(things, start, -128, 0, 0, 1, 7);
        thing(things, start, 0, 0, 0, 2001, 7); // Shotgun on the initial path.
        thing(things, start, 128, 0, 0, 2049, 7);
        thing(things, start, 0, -128, 0, 2012, 7);
        thing(things, rooms.get(keyRoom), 0, 0, 0, 5, 7); // Blue keycard.
        thing(things, rooms.get(REWARD), 0, 0, 0, 2019, 7); // Blue armor.
        int[][] positions = {{-128, -128}, {128, 128}, {-128, 128}, {128, -128}};
        for (int i = 1; i < 9; i++) {
            Room room = rooms.get(i);
            // Two core enemies plus one medium/hard and one hard-only enemy.
            for (int j = 0; j < 4; j++) {
                int type = new int[] {3004, 9, 3001}[random.nextInt(3)];
                int options = j < 2 ? 7 : j == 2 ? 6 : 4;
                thing(things, room, positions[j][0], positions[j][1], random.nextInt(8) * 45,
                        type, options);
            }
            thing(things, room, 0, -192, 0, 2049, 7); // Supplies do not depend on random drops.
            if (i % 2 == 1) thing(things, room, 0, 192, 0, 2012, 7);
        }
        Layout result = new Layout(cells, List.copyOf(rooms), List.copyOf(connections),
                List.copyOf(areas), List.copyOf(things), keyRoom, doorSector, doorX, doorY);
        validate(result);
        return result;
    }

    private static boolean terminal(int room) { return room == EXIT || room == REWARD; }
    private static int component(int[] parent, int i) {
        while (parent[i] != i) i = parent[i];
        return i;
    }
    private static void shuffle(List<Connection> list, SeedRandom random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Connection value = list.get(i);
            list.set(i, list.get(j)); list.set(j, value);
        }
    }
    private static int[] distances(List<Connection> edges, int start) {
        int[] distance = new int[9];
        Arrays.fill(distance, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start); distance[start] = 0;
        while (!queue.isEmpty()) {
            int room = queue.remove();
            for (Connection edge : edges) {
                int next = edge.a == room ? edge.b : edge.b == room ? edge.a : -1;
                if (next >= 0 && distance[next] < 0) {
                    distance[next] = distance[room] + 1; queue.add(next);
                }
            }
        }
        return distance;
    }
    private static void thing(List<GridMapBuilder.Thing> things, Room room,
                              int dx, int dy, int angle, int type, int options) {
        things.add(new GridMapBuilder.Thing(room.worldX() + dx, room.worldY() + dy, angle, type, options));
    }

    /** Validate actual carved space, including the lock, rather than just the room graph. */
    static void validate(Layout layout) {
        boolean[][] beforeKey = reachable(layout, false), afterKey = reachable(layout, true);
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            int sector = layout.cells[y][x];
            if (sector < 0) continue;
            if (!afterKey[y][x]) throw new IllegalStateException("Disconnected generated floor");
            if (sector == EXIT && beforeKey[y][x]) throw new IllegalStateException("Key bypass");
        }
        Room key = layout.rooms.get(layout.keyRoom), reward = layout.rooms.get(REWARD);
        if (!beforeKey[key.y][key.x] || !beforeKey[reward.y][reward.x])
            throw new IllegalStateException("Unreachable key or reward");
        if (layout.connections.size() < layout.rooms.size()) throw new IllegalStateException("Missing loop");
        for (GridMapBuilder.Thing thing : layout.things) {
            int x = thing.x() / 128, y = thing.y() / 128;
            if (x < 0 || y < 0 || x >= SIZE || y >= SIZE || layout.cells[y][x] < 0)
                throw new IllegalStateException("Thing outside generated floor");
        }
    }

    static boolean[][] reachable(Layout layout, boolean doorOpen) {
        boolean[][] seen = new boolean[SIZE][SIZE];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        Room start = layout.rooms.get(START);
        queue.add(start.y * SIZE + start.x); seen[start.y][start.x] = true;
        while (!queue.isEmpty()) {
            int cell = queue.remove(), x = cell % SIZE, y = cell / SIZE;
            for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = x + step[0], ny = y + step[1];
                if (nx < 0 || ny < 0 || nx >= SIZE || ny >= SIZE || seen[ny][nx]) continue;
                int sector = layout.cells[ny][nx];
                if (sector < 0 || (!doorOpen && sector == layout.doorSector)) continue;
                seen[ny][nx] = true; queue.add(ny * SIZE + nx);
            }
        }
        return seen;
    }

    private static String texture(String... preferred) {
        for (String name : preferred) if (RData.rCheckTextureNumForName(name) > 0) return name;
        for (int i = 1; i < RData.numtextures; i++)
            if (RData.textures[i].patchcount > 0) return RData.textures[i].name;
        throw ISystem.iError("Dynamic levels need a wall texture in the loaded WADs");
    }
    private static String flat(String preferred) {
        int lump = WWad.wCheckNumForName(preferred);
        if (lump >= RData.firstflat && lump <= RData.lastflat && WWad.wLumpLength(lump) == 4096)
            return preferred;
        for (int i = RData.firstflat; i <= RData.lastflat; i++)
            if (!WWad.lumpinfo[i].name.equals("F_SKY1") && WWad.wLumpLength(i) == 4096)
                return WWad.lumpinfo[i].name;
        throw ISystem.iError("Dynamic levels need a floor flat in the loaded WADs");
    }

    /** SplitMix64, with explicitly fixed operations for stable full-width seeds. */
    private static final class SeedRandom {
        private long state;
        SeedRandom(long seed) { state = seed; }
        int nextInt(int bound) {
            long value = (state += 0x9e3779b97f4a7c15L);
            value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
            value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
            value ^= value >>> 31;
            return (int) Long.remainderUnsigned(value, bound);
        }
    }
}
