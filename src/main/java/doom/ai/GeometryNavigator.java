package doom.ai;

import doom.play.*;
import doom.render.*;
import java.util.*;

/** Geometry-informed planning. Knows wall/floor geometry and item/switch appearance, never action IDs. */
final class GeometryNavigator {
    record Point(double x, double y) {}
    private static final int GRID = 16;
    private record Goal(double x, double y, double aimX, double aimY, int node, double priority, Object id) {}
    private record Step(int node, double cost) {}
    private int left, bottom, width, height, count, planned = -100;
    private Sector[] sectors;
    private boolean[] solid, blocked;
    private int[] staticEdges, edges;
    private static final int[] DX = {-1,0,1,-1,1,-1,0,1}, DY = {-1,-1,-1,0,0,1,1,1};
    private double[] floor, ceiling;
    private int[] visits;
    private final Map<Object, Integer> postponed = new HashMap<>();
    private Goal target;
    private List<Integer> route = List.of();
    private int cursor;
    private Point next;
    private Set<Line> blockedLastPlan = Set.of();
    private int lastProgress, progress, keys;
    private double closest;

    void reset() { sectors = null; target = null; next = null; planned = -100; postponed.clear(); blockedLastPlan = Set.of(); }

    private double x(int node) { return left + (node % width) * GRID + GRID / 2.; }
    private double y(int node) { return bottom + (node / width) * GRID + GRID / 2.; }
    private int node(double x, double y) {
        int gx = (int) Math.floor((x - left) / GRID), gy = (int) Math.floor((y - bottom) / GRID);
        return gx < 0 || gy < 0 || gx >= width || gy >= height ? -1 : gy * width + gx;
    }

    void observe(Observation o) {
        if (sectors == null) initialize();
        int at = node(o.x(), o.y());
        if (at >= 0) visits[at]++;
    }

    private void initialize() {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = minX, maxY = maxX;
        for (Line line : PSetup.lines) for (Vertex v : new Vertex[]{line.v1, line.v2}) {
            minX = Math.min(minX, v.x >> 16); maxX = Math.max(maxX, v.x >> 16);
            minY = Math.min(minY, v.y >> 16); maxY = Math.max(maxY, v.y >> 16);
        }
        left = Math.floorDiv(minX, GRID) * GRID; bottom = Math.floorDiv(minY, GRID) * GRID;
        width = (maxX - left) / GRID + 1; height = (maxY - bottom) / GRID + 1; count = width * height;
        if (count > 300000) throw new IllegalArgumentException("Navigation grid exceeds 300000 cells");
        sectors = new Sector[count]; solid = new boolean[count]; blocked = new boolean[count];
        floor = new double[count]; ceiling = new double[count]; visits = new int[count]; staticEdges = new int[count]; edges = new int[count];
        for (int i = 0; i < count; i++) sectors[i] = RMain.rPointInSubsector((int) (x(i) * 65536), (int) (y(i) * 65536)).sector;
        for (Line line : PSetup.lines) if (line.backsector == null || (line.flags & Line.ML_BLOCKING) != 0) {
            touching(line, i -> solid[i] = true); barrier(line, staticEdges);
        }
    }

    private void touching(Line line, java.util.function.IntConsumer action) {
        int x0 = Math.max(0, (int) Math.floor((Math.min(line.v1.x, line.v2.x) / 65536. - 16 - left) / GRID));
        int x1 = Math.min(width - 1, (int) Math.floor((Math.max(line.v1.x, line.v2.x) / 65536. + 16 - left) / GRID));
        int y0 = Math.max(0, (int) Math.floor((Math.min(line.v1.y, line.v2.y) / 65536. - 16 - bottom) / GRID));
        int y1 = Math.min(height - 1, (int) Math.floor((Math.max(line.v1.y, line.v2.y) / 65536. + 16 - bottom) / GRID));
        for (int gy = y0; gy <= y1; gy++) for (int gx = x0; gx <= x1; gx++) {
            int i = gy * width + gx;
            if (MotionProbe.intersectsSquare(x(i), y(i), 16, line)) action.accept(i);
        }
    }

    private void barrier(Line line, int[] masks) {
        int x0 = Math.max(0, (int) Math.floor((Math.min(line.v1.x, line.v2.x) / 65536. - 48 - left) / GRID));
        int x1 = Math.min(width - 1, (int) Math.floor((Math.max(line.v1.x, line.v2.x) / 65536. + 48 - left) / GRID));
        int y0 = Math.max(0, (int) Math.floor((Math.min(line.v1.y, line.v2.y) / 65536. - 48 - bottom) / GRID));
        int y1 = Math.min(height - 1, (int) Math.floor((Math.max(line.v1.y, line.v2.y) / 65536. + 48 - bottom) / GRID));
        for (int gy = y0; gy <= y1; gy++) for (int gx = x0; gx <= x1; gx++) {
            int i = gy * width + gx;
            for (int direction = 0; direction < 8; direction++) {
                if ((masks[i] & (1 << direction)) != 0) continue;
                for (int step = 1; step <= 12; step++)
                    if (MotionProbe.intersectsSquare(x(i) + DX[direction] * GRID * step / 12.,
                            y(i) + DY[direction] * GRID * step / 12., 16, line)) {
                        masks[i] |= 1 << direction; break;
                    }
            }
        }
    }

    private static double potentialCeiling(Sector s) {
        // Zero-height sectors can be doors. Their passability is a hypothesis until a real Use succeeds.
        return (s.ceilingheight == s.floorheight ? s.floorheight / 65536. + 128 : s.ceilingheight / 65536.);
    }

    private void update(Set<Line> failed) {
        System.arraycopy(solid, 0, blocked, 0, count);
        System.arraycopy(staticEdges, 0, edges, 0, count);
        for (int i = 0; i < count; i++) { floor[i] = sectors[i].floorheight / 65536.; ceiling[i] = potentialCeiling(sectors[i]); }
        for (Line line : PSetup.lines) if (line.backsector != null && (line.flags & Line.ML_BLOCKING) == 0) {
            double high = Math.max(line.frontsector.floorheight, line.backsector.floorheight) / 65536.;
            double low = Math.min(potentialCeiling(line.frontsector), potentialCeiling(line.backsector));
            touching(line, i -> { floor[i] = Math.max(floor[i], high); ceiling[i] = Math.min(ceiling[i], low); });
        }
        for (Line line : failed) { touching(line, i -> blocked[i] = true); barrier(line, edges); }
        for (Thinker thinker = PTick.thinkercap.next; thinker != PTick.thinkercap; thinker = thinker.next)
            if (thinker instanceof Mobj object && (object.flags & Mobj.MF_SOLID) != 0 && (object.flags & Mobj.MF_SHOOTABLE) == 0) {
                double ox = object.x / 65536., oy = object.y / 65536., radius = object.radius / 65536. + 16;
                int gx0 = Math.max(0, (int) Math.floor((ox - radius - left) / GRID));
                int gx1 = Math.min(width - 1, (int) Math.floor((ox + radius - left) / GRID));
                int gy0 = Math.max(0, (int) Math.floor((oy - radius - bottom) / GRID));
                int gy1 = Math.min(height - 1, (int) Math.floor((oy + radius - bottom) / GRID));
                for (int gy = gy0; gy <= gy1; gy++) for (int gx = gx0; gx <= gx1; gx++) blocked[gy * width + gx] = true;
            }
        for (int i = 0; i < count; i++) if (ceiling[i] - floor[i] < 56) blocked[i] = true;
    }

    Point choose(Observation o, int tic, Set<Line> used, Set<Line> failed) {
        return choose(o, tic, used, failed, null, 0);
    }

    /** Route toward an observed trajectory landmark using current geometry. */
    Point towards(Observation o, int tic, Point destination, double z) {
        if (target != null && !target.id.equals(destination)) { target = null; planned = -100; }
        return choose(o, tic, Set.of(), Set.of(), destination, z);
    }

    private Point choose(Observation o, int tic, Set<Line> used, Set<Line> failed, Point destination, double destinationZ) {
        observe(o);
        if (!o.lockedDoors().isEmpty()) {
            Set<Line> combined = new HashSet<>(failed); combined.addAll(o.lockedDoors()); failed = combined;
        }
        if (!blockedLastPlan.equals(failed)) {
            blockedLastPlan = Set.copyOf(failed); target = null; route = List.of(); planned = -100;
        }
        if (o.keys() != keys || o.progress() != progress) {
            keys = o.keys(); progress = o.progress(); postponed.clear(); target = null; planned = -100;
        }
        if (target != null && target.id instanceof Line line && (used.contains(line) || failed.contains(line))) {
            target = null; planned = -100;
        }
        if (target != null) {
            double d = Math.hypot(target.x - o.x(), target.y - o.y());
            if (d < closest - 24) { closest = d; lastProgress = tic; }
            if (d < 24 && target.id instanceof Integer) { postponed.put(target.id, tic + 3500); target = null; }
            else if (tic - lastProgress > 350) { postponed.put(target.id, tic + 1400); target = null; }
            else if (d < 48 && (!(target.id instanceof Line line)
                    || new MotionProbe(o).canInteract(line, target.aimX, target.aimY)))
                return new Point(target.aimX, target.aimY);
        }
        if (target != null && !route.isEmpty() && tic - planned < 70) return waypoint(o);
        planned = tic; update(failed);
        double[] distance = new double[count]; Arrays.fill(distance, Double.POSITIVE_INFINITY);
        int[] parent = new int[count]; Arrays.fill(parent, -1);
        PriorityQueue<Step> queue = new PriorityQueue<>(Comparator.comparingDouble(Step::cost));
        MotionProbe local = new MotionProbe(o);
        int baseX = (int) Math.floor((o.x() - left) / GRID), baseY = (int) Math.floor((o.y() - bottom) / GRID);
        for (int gy = Math.max(0, baseY - 2); gy <= Math.min(height - 1, baseY + 2); gy++)
            for (int gx = Math.max(0, baseX - 2); gx <= Math.min(width - 1, baseX + 2); gx++) {
                int i = gy * width + gx; double d = Math.hypot(x(i) - o.x(), y(i) - o.y());
                if (!blocked[i] && floor[i] <= o.z() + 24 && local.distance(x(i), y(i)) >= d - .01) {
                    distance[i] = d; queue.add(new Step(i, d));
                }
            }
        while (!queue.isEmpty()) {
            Step step = queue.remove(); int a = step.node;
            if (step.cost != distance[a]) continue;
            int ax = a % width, ay = a / width;
            for (int direction = 0; direction < 8; direction++) {
                int dx = DX[direction], dy = DY[direction];
                if ((edges[a] & (1 << direction)) != 0 || ax + dx < 0 || ax + dx >= width || ay + dy < 0 || ay + dy >= height) continue;
                int b = a + dx + dy * width;
                double rise = floor[b] - floor[a];
                if (blocked[b] || rise > 256) continue;
                // Walking cannot speculate that a tall riser will become a lift.
                // Test the swept player footprint across changing floor heights.
                if (rise > 0 && !stairs(a, b)) continue;
                if (dx != 0 && dy != 0 && (blocked[a + dx] || blocked[a + dy * width]
                        || floor[a + dx] - floor[a] > 24 || floor[a + dy * width] - floor[a] > 24)) continue;
                double cost = step.cost + (dx == 0 || dy == 0 ? GRID : GRID * Math.sqrt(2))
                        * (hazard(x(b), y(b)) ? 8 : 1);
                if (cost < distance[b]) { distance[b] = cost; parent[b] = a; queue.add(new Step(b, cost)); }
            }
        }
        List<Goal> goals = new ArrayList<>();
        if (destination != null) {
            goal(goals, destination.x, destination.y, destination.x, destination.y, destinationZ, 1, destination, distance);
        } else {
        for (Thinker thinker = PTick.thinkercap.next; thinker != PTick.thinkercap; thinker = thinker.next)
            if (thinker instanceof Mobj item && (item.flags & Mobj.MF_SPECIAL) != 0 && PickupController.key(item)) {
                int bit = switch (item.sprite) {
                    case Info.SPR_BKEY -> 1; case Info.SPR_YKEY -> 2; case Info.SPR_RKEY -> 4;
                    case Info.SPR_BSKU -> 8; case Info.SPR_YSKU -> 16; default -> 32;
                };
                if ((o.keys() & bit) == 0) goal(goals, item.x / 65536., item.y / 65536., item.x / 65536., item.y / 65536.,
                        item.z / 65536., 8, item, distance);
            }
        for (Line line : PSetup.lines) if (!used.contains(line) && !failed.contains(line) && panel(line)) {
            switchGoals(goals, line, distance);
        }
        for (int gy = 2; gy < height; gy += 12) for (int gx = 2; gx < width; gx += 12) {
            int i = gy * width + gx;
            if (!blocked[i] && Double.isFinite(distance[i]) && visits[i] == 0 && !hazard(x(i), y(i)))
                goals.add(new Goal(x(i), y(i), x(i), y(i), i, .5, i));
        }
        }
        if (destination == null && goals.stream().noneMatch(g -> postponed.getOrDefault(g.id, 0) <= tic)) {
            // Only after reachable objectives are exhausted, test a raised passage
            // from its lower side. A successful Use changes geometry before walking.
            for (Line line : PSetup.lines) if (line.backsector != null && !failed.contains(line)
                    && MotionProbe.opening(line) >= 56
                    && Math.abs((long) line.frontsector.floorheight - line.backsector.floorheight) > 24L * 65536)
                interactionGoals(goals, line, distance, false);
        }
        Goal best = null; double score = Double.POSITIVE_INFINITY;
        for (Goal goal : goals) {
            if (postponed.getOrDefault(goal.id, 0) > tic) continue;
            double value = distance[goal.node] / goal.priority;
            if (target != null && target.id.equals(goal.id)) value -= 64;
            if (value < score) { score = value; best = goal; }
        }
        if (best == null) { target = null; next = null; return null; }
        if (target == null || !target.id.equals(best.id)) { lastProgress = tic; closest = Math.hypot(best.x - o.x(), best.y - o.y()); }
        target = best;
        int cursor = best.node;
        List<Integer> path = new ArrayList<>();
        while (cursor >= 0) { path.add(cursor); cursor = parent[cursor]; }
        Collections.reverse(path);
        route = path; this.cursor = 0;
        return waypoint(o);
    }

    private Point waypoint(Observation o) {
        MotionProbe probe = new MotionProbe(o);
        // Proximity alone cannot skip a stair entrance or cut an obstacle corner.
        while (cursor < route.size() - 1 && Math.hypot(x(route.get(cursor)) - o.x(), y(route.get(cursor)) - o.y()) < 40) {
            int following = route.get(cursor + 1);
            double d = Math.hypot(x(following) - o.x(), y(following) - o.y());
            if (d > 256 || probe.distance(x(following), y(following)) < d - .01) break;
            cursor++;
        }
        int selected = route.get(cursor);
        for (int j = cursor; j < route.size(); j++) {
            int i = route.get(j); double d = Math.hypot(x(i) - o.x(), y(i) - o.y());
            if (d > 192 || probe.distance(x(i), y(i)) < d - .01) break;
            selected = i; cursor = j;
        }
        next = new Point(x(selected), y(selected));
        return next;
    }

    private boolean stairs(int from, int to) {
        if (floor[to] - floor[from] > 128) return false;
        Observation virtual = new Observation(x(from), y(from), 0, 100, 50, 3, 8, null,
                floor[from], 56, 16, 0, 0, 1, 0);
        double length = Math.hypot(x(to) - x(from), y(to) - y(from));
        return new MotionProbe(virtual).distance(x(to), y(to)) >= length - .01;
    }

    Line interactionTarget() { return target != null && target.id instanceof Line line ? line : null; }

    /** Standing positions on the usable face, rather than a point inside its wall. */
    private void switchGoals(List<Goal> goals, Line line, double[] distances) {
        interactionGoals(goals, line, distances, true);
    }

    private void interactionGoals(List<Goal> goals, Line line, double[] distances, boolean panel) {
        double dx = line.dx / 65536., dy = line.dy / 65536., length = Math.hypot(dx, dy);
        if (length == 0) return;
        int face = !panel && line.backsector.floorheight < line.frontsector.floorheight ? -1 : 1;
        double standingFloor = (face == 1 ? line.frontsector : line.backsector).floorheight / 65536.;
        for (double fraction : new double[]{.25, .5, .75}) {
            double aimX = line.v1.x / 65536. + fraction * dx;
            double aimY = line.v1.y / 65536. + fraction * dy;
            int center = node(aimX + face * 32 * dy / length, aimY - face * 32 * dx / length);
            if (center < 0) continue;
            for (int gy = Math.max(0, center / width - 2); gy <= Math.min(height - 1, center / width + 2); gy++)
                for (int gx = Math.max(0, center % width - 2); gx <= Math.min(width - 1, center % width + 2); gx++) {
                    int i = gy * width + gx;
                    if (blocked[i] || !Double.isFinite(distances[i])
                            || Math.abs(floor[i] - standingFloor) > 24) continue;
                    Observation standing = new Observation(x(i), y(i), 0, 100, 50, 3, 8, null,
                            floor[i], 56, 16, 0, 0, 1, 0);
                    MotionProbe probe = new MotionProbe(standing);
                    if (panel ? probe.canUse(line, aimX, aimY) : probe.canInteract(line, aimX, aimY))
                        goals.add(new Goal(x(i), y(i), aimX, aimY, i, panel ? 4 : .25, line));
                }
        }
    }

    private void goal(List<Goal> goals, double x, double y, double aimX, double aimY, double z, double priority, Object id, double[] distances) {
        int center = node(x, y); if (center < 0) return;
        int best = -1; double nearest = 80;
        for (int gy = Math.max(0, center / width - 5); gy <= Math.min(height - 1, center / width + 5); gy++)
            for (int gx = Math.max(0, center % width - 5); gx <= Math.min(width - 1, center % width + 5); gx++) {
                int i = gy * width + gx; double d = Math.hypot(x(i) - x, y(i) - y);
                if (d < nearest && !blocked[i] && Math.abs(floor[i] - z) <= 24 && Double.isFinite(distances[i])
                        && Observation.clearance(x(i), y(i), x, y, false) >= .999) { nearest = d; best = i; }
            }
        if (best >= 0) goals.add(new Goal(x, y, aimX, aimY, best, priority, id));
    }

    /** Visible toxic floor textures are a cost, not an impassable barrier. No sector specials are read. */
    static boolean hazard(double x, double y) {
        if (doom.play.PSetup.subsectors == null || doom.play.PSetup.subsectors.length == 0) return false;
        var sector = doom.render.RMain.rPointInSubsector((int) (x * 65536), (int) (y * 65536)).sector;
        int index = doom.render.RData.firstflat + sector.floorpic;
        var lumps = doom.wad.WWad.lumpinfo;
        if (index < 0 || index >= lumps.length) return false;
        String name = lumps[index].name;
        return name != null && (name.startsWith("NUKAGE") || name.startsWith("LAVA"));
    }

    private static boolean panel(Line line) {
        int side = line.sidenum[0]; if (side < 0 || side >= PSetup.sides.length) return false;
        Side wall = PSetup.sides[side];
        for (int index : new int[]{wall.toptexture, wall.midtexture, wall.bottomtexture})
            if (index >= 0 && index < RData.textures.length && RData.textures[index].name.startsWith("SW")) return true;
        return false;
    }
}
