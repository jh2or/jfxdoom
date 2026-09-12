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
    private record Interaction(Line line, boolean panel) {}
    private record Attachment(int node, double distance) {}
    private record Connections(double x, double y, double z, List<Attachment> approaches) {}
    private int left, bottom, width, height, count, planned = -100;
    private Sector[] sectors;
    private boolean[] solid, blocked;
    private int[] staticEdges, edges;
    // Grid topology is implicit (eight neighbors); cache the expensive edge tests.
    private int[] stairKnown, stairPass;
    private double[] terrainCost;
    private Sector[] terrainSectors;
    private int[] terrainFloors, terrainCeilings, terrainFlats, terrainSpecials;
    private boolean terrainReady;
    private int[] sideTextures;
    private final Map<Interaction, List<Goal>> interactions = new HashMap<>();
    private final Map<Mobj, Connections> pickups = new IdentityHashMap<>();
    private Connections destinationConnections;
    private final Map<Line, int[]> touchingNodes = new IdentityHashMap<>();
    private static final int[] DX = {-1,0,1,-1,1,-1,0,1}, DY = {-1,-1,-1,0,0,1,1,1};
    private double[] floor, ceiling;
    private int[] visits;
    private final Map<Object, Integer> postponed = new HashMap<>();
    private Goal target;
    private List<Integer> route = List.of();
    private int cursor;
    private Point next;
    private TimedCrossing.Plan timedShortcut;
    private Point timedLanding;
    private Set<Line> blockedLastPlan = Set.of();
    private int lastProgress, progress, keys;
    private double closest;

    void reset() {
        timedShortcut = null; timedLanding = null;
        sectors = null; target = null; next = null; planned = -100; postponed.clear(); blockedLastPlan = Set.of();
        touchingNodes.clear(); terrainReady = false;
        interactions.clear(); pickups.clear(); destinationConnections = null;
    }

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
        sideTextures = new int[PSetup.sides.length * 3]; Arrays.fill(sideTextures, Integer.MIN_VALUE);
        stairKnown = new int[count]; stairPass = new int[count]; terrainCost = new double[count];
        for (int i = 0; i < count; i++) sectors[i] = RMain.rPointInSubsector((int) (x(i) * 65536), (int) (y(i) * 65536)).sector;
        Set<Sector> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(unique, sectors);
        for (Line line : PSetup.lines) {
            unique.add(line.frontsector);
            if (line.backsector != null) unique.add(line.backsector);
        }
        terrainSectors = unique.toArray(Sector[]::new);
        terrainFloors = new int[terrainSectors.length]; terrainCeilings = new int[terrainSectors.length];
        terrainFlats = new int[terrainSectors.length]; terrainSpecials = new int[terrainSectors.length];
        for (Line line : PSetup.lines) if (line.backsector == null || (line.flags & Line.ML_BLOCKING) != 0) {
            touching(line, i -> solid[i] = true); barrier(line, staticEdges);
        }
    }

    private void touching(Line line, java.util.function.IntConsumer action) {
        int[] nodes = touchingNodes.computeIfAbsent(line, l -> {
            var builder = java.util.stream.IntStream.builder();
            findTouching(l, builder::add);
            return builder.build().toArray();
        });
        for (int i : nodes) action.accept(i);
    }

    private void findTouching(Line line, java.util.function.IntConsumer action) {
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

    /** Door/lift motion invalidates height-dependent edges; objects and locks stay live overlays. */
    private boolean refreshTerrain() {
        boolean geometryChanged = !terrainReady, flatsChanged = !terrainReady;
        for (int i = 0; i < terrainSectors.length; i++) {
            Sector sector = terrainSectors[i];
            geometryChanged |= terrainFloors[i] != sector.floorheight || terrainCeilings[i] != sector.ceilingheight;
            flatsChanged |= terrainFlats[i] != sector.floorpic || terrainSpecials[i] != sector.special;
            terrainFloors[i] = sector.floorheight; terrainCeilings[i] = sector.ceilingheight;
            terrainFlats[i] = sector.floorpic; terrainSpecials[i] = sector.special;
        }
        if (geometryChanged) { Arrays.fill(stairKnown, 0); Arrays.fill(stairPass, 0); }
        if (flatsChanged) for (int i = 0; i < count; i++) terrainCost[i] = hazard(x(i), y(i)) ? 8 : 1;
        terrainReady = true;
        return geometryChanged;
    }

    private void update(Set<Line> failed) {
        if (refreshTerrain()) {
            interactions.clear(); pickups.clear(); destinationConnections = null;
        }
        // Texture changes can turn a neighboring face into an intervening use target.
        for (int i = 0; i < PSetup.sides.length; i++) {
            Side side = PSetup.sides[i];
            if (sideTextures[3*i] != side.toptexture || sideTextures[3*i+1] != side.midtexture
                    || sideTextures[3*i+2] != side.bottomtexture) interactions.clear();
            sideTextures[3*i] = side.toptexture; sideTextures[3*i+1] = side.midtexture;
            sideTextures[3*i+2] = side.bottomtexture;
        }
        pickups.keySet().removeIf(item -> item.removed || (item.flags & Mobj.MF_SPECIAL) == 0);
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
        if (timedShortcut != null) {
            int frame = PTick.leveltime - timedShortcut.frames().getFirst().tic();
            if (frame >= 0 && frame < timedShortcut.frames().size()
                    && timedShortcut.frames().get(frame).matches(o, PTick.leveltime)) return timedLanding;
            // An interrupted shortcut must not become an unrestricted hazardous route.
            boolean finished = frame == timedShortcut.frames().size()
                    && Math.hypot(o.x() - timedLanding.x, o.y() - timedLanding.y) < 24;
            if (!finished && !FloorSafety.hazardous(o.x(), o.y())) { target = null; route = List.of(); planned = -100; }
            timedShortcut = null; timedLanding = null;
        }
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
            else if (d < 48 && (hazard(target.x, target.y) || FloorSafety.exposure(o.x(), o.y(), target.x, target.y) == 0)
                    && (!(target.id instanceof Line line)
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
                    distance[i] = d + 7 * FloorSafety.exposure(o.x(), o.y(), x(i), y(i));
                    queue.add(new Step(i, distance[i]));
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
                if (rise > 0 && !cachedStairs(a, b, direction)) continue;
                if (dx != 0 && dy != 0 && (blocked[a + dx] || blocked[a + dy * width]
                        || floor[a + dx] - floor[a] > 24 || floor[a + dy * width] - floor[a] > 24)) continue;
                double cost = step.cost + (dx == 0 || dy == 0 ? GRID : GRID * Math.sqrt(2))
                        * terrainCost[b];
                if (cost < distance[b]) { distance[b] = cost; parent[b] = a; queue.add(new Step(b, cost)); }
            }
        }
        List<Goal> goals = new ArrayList<>(), riskyGoals = new ArrayList<>();
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
            if (!blocked[i] && Double.isFinite(distance[i]) && visits[i] == 0)
                (terrainCost[i] == 1 ? goals : riskyGoals).add(new Goal(x(i), y(i), x(i), y(i), i, .5, i));
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
        // A damaging destination may be necessary (including a final room). Explore it last, never forbid it.
        if (destination == null && goals.stream().noneMatch(g -> postponed.getOrDefault(g.id, 0) <= tic))
            goals.addAll(riskyGoals);
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
            int current = route.get(cursor);
            double budget = FloorSafety.exposure(o.x(), o.y(), x(current), y(current))
                    + FloorSafety.exposure(x(current), y(current), x(following), y(following));
            if (d > 256 || probe.distance(x(following), y(following)) < d - .01
                    || FloorSafety.exposure(o.x(), o.y(), x(following), y(following)) > budget + .01) break;
            cursor++;
        }
        int selected = route.get(cursor);
        double budget = 0, previousX = o.x(), previousY = o.y();
        for (int j = cursor; j < route.size(); j++) {
            int i = route.get(j); double d = Math.hypot(x(i) - o.x(), y(i) - o.y());
            if (d > 192 || probe.distance(x(i), y(i)) < d - .01) break;
            budget += FloorSafety.exposure(previousX, previousY, x(i), y(i));
            previousX = x(i); previousY = y(i);
            TimedCrossing.Plan shortcut = null;
            if (FloorSafety.exposure(o.x(), o.y(), x(i), y(i)) > budget + .01) {
                shortcut = TimedCrossing.plan(o, probe, x(i), y(i), PTick.leveltime);
                if (shortcut == null) continue;
            }
            selected = i; cursor = j;
            timedShortcut = shortcut;
            timedLanding = shortcut == null ? null : new Point(x(i), y(i));
        }
        next = new Point(x(selected), y(selected));
        return next;
    }

    private boolean cachedStairs(int from, int to, int direction) {
        int bit = 1 << direction;
        if ((stairKnown[from] & bit) == 0) {
            stairKnown[from] |= bit;
            if (stairs(from, to)) stairPass[from] |= bit;
        }
        return (stairPass[from] & bit) != 0;
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
        List<Goal> approaches = interactions.computeIfAbsent(new Interaction(line, panel), ignored -> {
            List<Goal> result = new ArrayList<>();
            connectInteraction(result, line, panel);
            return List.copyOf(result);
        });
        // Reachability, temporary obstacles, failed doors and keys are search-time conditions.
        for (Goal approach : approaches)
            if (!blocked[approach.node] && Double.isFinite(distances[approach.node])) goals.add(approach);
    }

    private void connectInteraction(List<Goal> goals, Line line, boolean panel) {
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
                    if (solid[i] || Math.abs(floor[i] - standingFloor) > 24) continue;
                    Observation standing = new Observation(x(i), y(i), 0, 100, 50, 3, 8, null,
                            floor[i], 56, 16, 0, 0, 1, 0);
                    MotionProbe probe = new MotionProbe(standing);
                    if (panel ? probe.canUse(line, aimX, aimY) : probe.canInteract(line, aimX, aimY))
                        goals.add(new Goal(x(i), y(i), aimX, aimY, i, panel ? 4 : .25, line));
                }
        }
    }

    private void goal(List<Goal> goals, double x, double y, double aimX, double aimY, double z, double priority, Object id, double[] distances) {
        Connections connection = id instanceof Mobj item ? pickups.get(item) : destinationConnections;
        if (connection == null || connection.x != x || connection.y != y || connection.z != z) {
            connection = connectPoint(x, y, z);
            if (id instanceof Mobj item) pickups.put(item, connection);
            else destinationConnections = connection;
        }
        int best = -1; double nearest = 80;
        for (Attachment approach : connection.approaches) {
            int i = approach.node;
            if (approach.distance < nearest && !blocked[i] && Double.isFinite(distances[i])) {
                nearest = approach.distance; best = i;
            }
        }
        if (best >= 0) goals.add(new Goal(x, y, aimX, aimY, best, priority, id));
    }

    /** Explicit connections from an objective to nearby standing nodes, in the original tie order. */
    private Connections connectPoint(double x, double y, double z) {
        List<Attachment> approaches = new ArrayList<>();
        int center = node(x, y);
        if (center >= 0) for (int gy = Math.max(0, center / width - 5); gy <= Math.min(height - 1, center / width + 5); gy++)
            for (int gx = Math.max(0, center % width - 5); gx <= Math.min(width - 1, center % width + 5); gx++) {
                int i = gy * width + gx; double d = Math.hypot(x(i) - x, y(i) - y);
                if (d < 80 && !solid[i] && Math.abs(floor[i] - z) <= 24
                        && Observation.clearance(x(i), y(i), x, y, false) >= .999)
                    approaches.add(new Attachment(i, d));
            }
        return new Connections(x, y, z, List.copyOf(approaches));
    }

    /** Damaging floor mechanics and visible toxic textures are finite route costs. */
    static boolean hazard(double x, double y) { return FloorSafety.hazardous(x, y); }

    private static boolean panel(Line line) {
        int side = line.sidenum[0]; if (side < 0 || side >= PSetup.sides.length) return false;
        Side wall = PSetup.sides[side];
        for (int index : new int[]{wall.toptexture, wall.midtexture, wall.bottomtexture})
            if (index >= 0 && index < RData.textures.length && RData.textures[index].name.startsWith("SW")) return true;
        return false;
    }
}
