package doom.play;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Java-only compiler for a bounded grid of 128-unit, axis-aligned floor cells.
 * Each open cell is a convex subsector. A spatial partition of the cells gives
 * an exact BSP without rounded intersections or an external node builder.
 */
final class GridMapBuilder {
    static final int UNIT = 128;
    record Region(int floor, int ceiling, int light, String wall, String floorFlat, String ceilingFlat) { }
    record Thing(int x, int y, int angle, int type, int options) { }
    private record Point(int x, int y) { }
    private record Edge(int a, int b) { }
    private record Cell(int x, int y, int sector, int subsector) { }
    private static final class MapLine {
        int a, b, front, back = -1, special;
    }
    private record MapSide(int sector, String upper, String lower, String middle) { }
    private record MapSeg(int a, int b, int angle, int line, int side) { }
    private record Branch(int child, int left, int right, int bottom, int top) { }

    private final int[][] grid;
    private final List<Region> regions;
    private final int doorSector, exitX, exitY;
    private final String doorTexture, exitTexture;
    private final List<Point> vertices = new ArrayList<>();
    private final Map<Point, Integer> vertexIndices = new LinkedHashMap<>();
    private final List<MapLine> lines = new ArrayList<>();
    private final Map<Edge, Integer> lineIndices = new LinkedHashMap<>();
    private final List<MapSide> sides = new ArrayList<>();
    private final List<MapSeg> segs = new ArrayList<>();
    private final List<byte[]> nodes = new ArrayList<>();
    private final List<Cell> cells = new ArrayList<>();

    GridMapBuilder(int[][] grid, List<Region> regions, int doorSector,
                   int exitX, int exitY, String doorTexture, String exitTexture) {
        this.grid = grid;
        this.regions = regions;
        this.doorSector = doorSector;
        this.exitX = exitX;
        this.exitY = exitY;
        this.doorTexture = doorTexture;
        this.exitTexture = exitTexture;
    }

    byte[][] compile(List<Thing> things) {
        for (int y = 0; y < grid.length; y++) {
            for (int x = 0; x < grid[y].length; x++) {
                if (grid[y][x] >= 0) cells.add(new Cell(x, y, grid[y][x], cells.size()));
            }
        }
        for (Cell cell : cells) addCell(cell);
        buildNode(cells);
        // Classic map indices and the port's signed BLOCKMAP offsets are bounded.
        if (cells.size() >= 32768 || lines.size() >= 32768 || sides.size() >= 32768
                || vertices.size() >= 32768 || segs.size() >= 32768)
            throw new IllegalArgumentException("Generated map exceeds classic map limits");
        ByteBuffer vertexData = buffer(vertices.size() * 4);
        for (Point p : vertices) shorts(vertexData, p.x, p.y);
        ByteBuffer lineData = buffer(lines.size() * 14);
        for (MapLine line : lines) {
            boolean twoSided = line.back >= 0;
            int flags = twoSided ? 4 : 1;
            if (twoSided && sides.get(line.front).sector == sides.get(line.back).sector)
                flags |= 128; // Internal cell edges do not clutter the automap.
            shorts(lineData, line.a, line.b, flags, line.special, 0, line.front, line.back);
        }
        ByteBuffer sideData = buffer(sides.size() * 30);
        for (MapSide side : sides) {
            shorts(sideData, 0, 0);
            name(sideData, side.upper); name(sideData, side.lower); name(sideData, side.middle);
            shorts(sideData, side.sector);
        }
        ByteBuffer segData = buffer(segs.size() * 12);
        for (MapSeg seg : segs) shorts(segData, seg.a, seg.b, seg.angle, seg.line, seg.side, 0);
        ByteBuffer subsectors = buffer(cells.size() * 4);
        for (Cell cell : cells) shorts(subsectors, 4, cell.subsector * 4);
        ByteBuffer nodeData = buffer(nodes.size() * 28);
        for (byte[] node : nodes) nodeData.put(node);
        ByteBuffer sectorData = buffer(regions.size() * 26);
        for (Region region : regions) {
            shorts(sectorData, region.floor, region.ceiling);
            name(sectorData, region.floorFlat); name(sectorData, region.ceilingFlat);
            shorts(sectorData, region.light, 0, 0);
        }
        ByteBuffer thingData = buffer(things.size() * 10);
        for (Thing thing : things)
            shorts(thingData, thing.x, thing.y, thing.angle, thing.type, thing.options);
        return new byte[][] {new byte[0], thingData.array(), lineData.array(), sideData.array(),
                vertexData.array(), segData.array(), subsectors.array(), nodeData.array(),
                sectorData.array(), new byte[(regions.size() * regions.size() + 7) / 8], blockmap()};
    }

    private int sector(int x, int y) {
        return y < 0 || y >= grid.length || x < 0 || x >= grid[y].length ? -1 : grid[y][x];
    }

    private void addCell(Cell cell) {
        int x = cell.x * UNIT, y = cell.y * UNIT;
        Point[] corners = {new Point(x, y), new Point(x, y + UNIT),
                new Point(x + UNIT, y + UNIT), new Point(x + UNIT, y)};
        int[] neighbors = {sector(cell.x - 1, cell.y), sector(cell.x, cell.y + 1),
                sector(cell.x + 1, cell.y), sector(cell.x, cell.y - 1)};
        int[] angles = {16384, 0, 49152, 32768};
        for (int edge = 0; edge < 4; edge++) {
            int a = vertex(corners[edge]), b = vertex(corners[(edge + 1) % 4]);
            Edge key = new Edge(Math.min(a, b), Math.max(a, b));
            Integer index = lineIndices.get(key);
            if (index == null) {
                MapLine line = new MapLine();
                // Manual doors operate on the back sector: both usable faces
                // must point from the corridor into the closed door cell.
                boolean reverse = cell.sector == doorSector && neighbors[edge] >= 0;
                line.a = reverse ? b : a;
                line.b = reverse ? a : b;
                int front = reverse ? neighbors[edge] : cell.sector;
                int back = reverse ? cell.sector : neighbors[edge];
                boolean door = back == doorSector;
                boolean exit = cell.x == exitX && cell.y == exitY && edge == 2;
                line.special = door ? 32 : exit ? 11 : 0;
                line.front = sides.size();
                Region region = regions.get(front);
                sides.add(new MapSide(front, door ? doorTexture : region.wall, region.wall,
                        back >= 0 ? "-" : exit ? exitTexture : region.wall));
                if (back >= 0) {
                    line.back = sides.size();
                    Region other = regions.get(back);
                    sides.add(new MapSide(back, other.wall, other.wall, "-"));
                }
                index = lines.size();
                lines.add(line);
                lineIndices.put(key, index);
            }
            MapLine line = lines.get(index);
            segs.add(new MapSeg(a, b, angles[edge], index, a == line.a ? 0 : 1));
        }
    }

    private int vertex(Point point) {
        return vertexIndices.computeIfAbsent(point, p -> {
            vertices.add(p);
            return vertices.size() - 1;
        });
    }

    private Branch buildNode(List<Cell> group) {
        int left = Integer.MAX_VALUE, right = 0, bottom = Integer.MAX_VALUE, top = 0;
        for (Cell c : group) {
            left = Math.min(left, c.x); right = Math.max(right, c.x + 1);
            bottom = Math.min(bottom, c.y); top = Math.max(top, c.y + 1);
        }
        if (group.size() == 1)
            return new Branch(0x8000 | group.getFirst().subsector,
                    left * UNIT, right * UNIT, bottom * UNIT, top * UNIT);
        boolean vertical = right - left >= top - bottom;
        int split = vertical ? (left + right) / 2 : (bottom + top) / 2;
        List<Cell> low = new ArrayList<>(), high = new ArrayList<>();
        for (Cell c : group) ((vertical ? c.x : c.y) < split ? low : high).add(c);
        // Vertical northbound partition: east is front. Horizontal eastbound:
        // south is front. Children are written in the engine's front/back order.
        Branch front = buildNode(vertical ? high : low);
        Branch back = buildNode(vertical ? low : high);
        ByteBuffer node = buffer(28);
        shorts(node, vertical ? split * UNIT : 0, vertical ? 0 : split * UNIT,
                vertical ? 0 : UNIT, vertical ? UNIT : 0);
        for (Branch child : new Branch[] {front, back})
            shorts(node, child.top, child.bottom, child.left, child.right);
        shorts(node, front.child, back.child);
        int index = nodes.size();
        nodes.add(node.array());
        return new Branch(index, left * UNIT, right * UNIT, bottom * UNIT, top * UNIT);
    }

    private byte[] blockmap() {
        int width = grid[0].length + 2, height = grid.length + 2;
        List<List<Integer>> blocks = new ArrayList<>();
        for (int i = 0; i < width * height; i++) blocks.add(new ArrayList<>());
        for (int i = 0; i < lines.size(); i++) {
            MapLine line = lines.get(i);
            Point a = vertices.get(line.a), b = vertices.get(line.b);
            // Include both sides of an edge on a block boundary, plus endpoints.
            for (int y = Math.min(a.y, b.y) / UNIT; y <= Math.max(a.y, b.y) / UNIT + 1; y++)
                for (int x = Math.min(a.x, b.x) / UNIT; x <= Math.max(a.x, b.x) / UNIT + 1; x++)
                    blocks.get(y * width + x).add(i);
        }
        int size = 4 + blocks.size();
        for (List<Integer> block : blocks) size += block.size() + 2;
        if (size >= 32768) throw new IllegalArgumentException("Generated BLOCKMAP exceeds signed offsets");
        ByteBuffer out = buffer(size * 2);
        shorts(out, -UNIT, -UNIT, width, height);
        int offset = 4 + blocks.size();
        for (List<Integer> block : blocks) {
            shorts(out, offset);
            offset += block.size() + 2;
        }
        for (List<Integer> block : blocks) {
            shorts(out, 0);
            for (int line : block) shorts(out, line);
            shorts(out, -1);
        }
        return out.array();
    }

    private static ByteBuffer buffer(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    private static void shorts(ByteBuffer out, int... values) {
        for (int value : values) out.putShort((short) value);
    }
    private static void name(ByteBuffer out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        out.put(bytes);
        for (int i = bytes.length; i < 8; i++) out.put((byte) 0);
    }
}
