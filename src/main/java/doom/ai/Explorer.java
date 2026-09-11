package doom.ai;

import doom.render.Line;
import java.util.*;

/** Reachability-first local proposals, bounded door interaction, and failed-motion recovery. */
final class Explorer {
    record Burst(Action action, int duration) {}
    private final LearnerRandom random;
    private final GeometryNavigator geometry = new GeometryNavigator();
    private final boolean geometryMode;
    private final LocalController controller = new LocalController(true);
    private final int[] avoidUntil = new int[16];
    private final IdentityHashMap<Line, Integer> doors = new IdentityHashMap<>();
    private double heading, lastX, lastY, passageX, passageY;
    private boolean passage;
    private int hold, lastTic, stuck, recoveryUntil;
    private Action previous = Action.IDLE;
    private Line door;
    private int doorDeadline;
    private boolean switchTarget;
    private long switchAppearance;
    private record FailedBarrier(long shape, int progress, int keys) {}
    private final IdentityHashMap<Line, FailedBarrier> failures = new IdentityHashMap<>();
    private final Set<Line> usedPanels = Collections.newSetFromMap(new IdentityHashMap<>());

    Explorer(LearnerRandom random) { this(random, true); }
    Explorer(LearnerRandom random, boolean geometryMode) { this.random = random; this.geometryMode = geometryMode; }
    void restart() {
        hold = lastTic = stuck = recoveryUntil = 0; previous = Action.IDLE; door = null;
        geometry.reset(); usedPanels.clear(); failures.clear(); passage = false;
        Arrays.fill(avoidUntil, 0); doors.clear(); controller.reset();
    }

    void rememberInteractions(Set<Integer> activated) {
        for (int index : activated) usedPanels.add(doom.play.PSetup.lines[index]);
    }
    private static long appearance(Line line) {
        long value = 0;
        for (int index : line.sidenum) if (index >= 0 && index < doom.play.PSetup.sides.length) {
            var side = doom.play.PSetup.sides[index];
            value = 31 * value + side.toptexture; value = 31 * value + side.midtexture; value = 31 * value + side.bottomtexture;
        }
        return value;
    }

    private static long shape(Line line) {
        long value = appearance(line);
        value = 31 * value + line.frontsector.floorheight;
        value = 31 * value + line.frontsector.ceilingheight;
        if (line.backsector != null) {
            value = 31 * value + line.backsector.floorheight;
            value = 31 * value + line.backsector.ceilingheight;
        }
        return value;
    }

    void observe(Observation o, int tic) {
        if (geometryMode) geometry.observe(o);
    }

    Burst choose(Observation o, Archive archive, int tic) {
        MotionProbe probe = new MotionProbe(o, !geometryMode);
        failures.entrySet().removeIf(entry -> entry.getValue().progress() != o.progress() || entry.getValue().keys() != o.keys()
                || entry.getValue().shape() != shape(entry.getKey()));
        Set<Line> blocked = Collections.newSetFromMap(new IdentityHashMap<>());
        blocked.addAll(failures.keySet()); blocked.addAll(o.lockedDoors());
        if (door != null && o.lockedDoors().contains(door)) { door = null; hold = 0; stuck = 0; }
        var waypoint = geometryMode ? geometry.choose(o, tic, usedPanels, blocked) : null;
        if (previous.forward() != 0 || previous.side() != 0) {
            stuck = Math.hypot(o.x() - lastX, o.y() - lastY) < 1.5 ? stuck + tic - lastTic : 0;
        } else if (door == null) stuck = 0;
        if (door != null && ((switchTarget ? appearance(door) != switchAppearance : !probe.blocks(door)) || tic >= doorDeadline)) {
            if (switchTarget && appearance(door) != switchAppearance) usedPanels.add(door);
            else if (tic >= doorDeadline && (switchTarget || probe.blocks(door)))
                failures.put(door, new FailedBarrier(shape(door), o.progress(), o.keys()));
            doors.put(door, tic + 350); door = null; hold = 0; stuck = 0;
        }
        if (door == null) {
            Set<Line> unavailable = Collections.newSetFromMap(new IdentityHashMap<>());
            unavailable.addAll(usedPanels); unavailable.addAll(blocked);
            Line candidate = probe.switchPanel(512.0, unavailable);
            boolean panel = candidate != null && doors.getOrDefault(candidate, 0) <= tic;
            if (!panel) candidate = probe.door(192, blocked);
            if (candidate != null && doors.getOrDefault(candidate, 0) <= tic) {
                door = candidate; switchTarget = panel; switchAppearance = appearance(candidate); doorDeadline = tic + (panel ? 140 : 105);
            }
        }
        Burst result;
        if (door != null) {
            double tx = ((long) door.v1.x + door.v2.x) / 131072.0;
            double ty = ((long) door.v1.y + door.v2.y) / 131072.0;
            result = controller.towards(o, probe, tx, ty, tic, false);
        } else {
            if (stuck >= 18) {
                avoidUntil[Math.floorMod((int) Math.round(heading * 8 / Math.PI), 16)] = tic + 140;
                recoveryUntil = tic + 12; stuck = 0; hold = 0;
            }
            if (tic < recoveryUntil) {
                double back = probe.distance(o.x() - 24 * Math.cos(o.angle()), o.y() - 24 * Math.sin(o.angle()));
                result = new Burst(new Action(back > 8 ? -25 : 0, 0, 1024, 0), 2);
            } else if (waypoint != null) {
                result = controller.towards(o, probe, waypoint.x(), waypoint.y(), tic, random.nextDouble() < .3);
            } else {
                if (passage && Math.hypot(passageX - o.x(), passageY - o.y()) < 24) hold = 0;
                if (hold-- <= 0 || probe.distance(o.x() + 48 * Math.cos(heading), o.y() + 48 * Math.sin(heading)) < 16) {
                    double best = Double.NEGATIVE_INFINITY;
                    passage = false;
                    for (int i = 0; i < 16; i++) {
                        double a = i * Math.PI / 8;
                        double reachable = probe.distance(o.x() + 112 * Math.cos(a), o.y() + 112 * Math.sin(a));
                        double tx = o.x() + reachable * Math.cos(a), ty = o.y() + reachable * Math.sin(a);
                        // Bounded novelty at the reachable endpoint can never outweigh an impassable wall.
                        double value = reachable < 16 ? -100 : 3 * Math.min(1, reachable / 32)
                                + -1.5 * Math.log1p(archive.visits(tx, ty, o.keys(), o.progress()))
                                + .5 * Math.cos(a - o.angle()) + random.nextDouble() * .8;
                        if (avoidUntil[i] > tic) value -= 5;
                        if (value > best) { best = value; heading = a; }
                    }
                    for (var opening : probe.passages()) {
                        int visits = archive.visits(opening.x(), opening.y(), o.keys(), o.progress());
                        if (visits > 0 && visits >= archive.visits(o.x(), o.y(), o.keys(), o.progress())) continue;
                        double a = Math.atan2(opening.y() - o.y(), opening.x() - o.x());
                        double value = 3.8 - 1.5 * Math.log1p(visits) + .5 * Math.cos(a - o.angle());
                        if (avoidUntil[Math.floorMod((int) Math.round(a * 8 / Math.PI), 16)] > tic) value -= 5;
                        if (value > best) {
                            best = value; heading = a; passage = true; passageX = opening.x(); passageY = opening.y();
                        }
                    }
                    hold = 3 + random.nextInt(5);
                }
                result = controller.towards(o, probe, passage ? passageX : o.x() + 112 * Math.cos(heading),
                        passage ? passageY : o.y() + 112 * Math.sin(heading), tic, random.nextDouble() < .3);
            }
        }
        lastX = o.x(); lastY = o.y(); lastTic = tic; previous = result.action();
        return result;
    }
}
