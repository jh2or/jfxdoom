package doom.ai;

import doom.render.Line;
import java.util.*;

/** Reachability-first local proposals, bounded door interaction, and failed-motion recovery. */
final class Explorer {
    record Burst(Action action, int duration) {}
    private final LearnerRandom random;
    private final LocalController controller = new LocalController();
    private final int[] avoidUntil = new int[16];
    private final IdentityHashMap<Line, Integer> doors = new IdentityHashMap<>();
    private double heading, lastX, lastY;
    private int hold, lastTic, stuck, recoveryUntil;
    private Action previous = Action.IDLE;
    private Line door;
    private int doorDeadline;

    Explorer(LearnerRandom random) { this.random = random; }
    void restart() {
        hold = lastTic = stuck = recoveryUntil = 0; previous = Action.IDLE; door = null;
        Arrays.fill(avoidUntil, 0); doors.clear(); controller.reset();
    }

    Burst choose(Observation o, Archive archive, int tic) {
        MotionProbe probe = new MotionProbe(o);
        if (previous.forward() != 0 || previous.side() != 0) {
            stuck = Math.hypot(o.x() - lastX, o.y() - lastY) < 1.5 ? stuck + tic - lastTic : 0;
        } else if (door == null) stuck = 0;
        if (door != null && (!probe.blocks(door) || tic >= doorDeadline)) {
            doors.put(door, tic + 350); door = null; hold = 0; stuck = 0;
        }
        if (door == null) {
            Line candidate = probe.door(72);
            if (candidate != null && doors.getOrDefault(candidate, 0) <= tic) {
                door = candidate; doorDeadline = tic + 70;
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
            } else {
                if (hold-- <= 0 || probe.distance(o.x() + 48 * Math.cos(heading), o.y() + 48 * Math.sin(heading)) < 16) {
                    double best = Double.NEGATIVE_INFINITY;
                    for (int i = 0; i < 16; i++) {
                        double a = i * Math.PI / 8;
                        double reachable = probe.distance(o.x() + 112 * Math.cos(a), o.y() + 112 * Math.sin(a));
                        double tx = o.x() + reachable * Math.cos(a), ty = o.y() + reachable * Math.sin(a);
                        // Bounded novelty at the reachable endpoint can never outweigh an impassable wall.
                        double value = reachable < 16 ? -100 : 3 * reachable / 112
                                + 1.5 / Math.sqrt(1 + archive.visits(tx, ty))
                                + .5 * Math.cos(a - o.angle()) + random.nextDouble() * .8;
                        if (avoidUntil[i] > tic) value -= 5;
                        if (value > best) { best = value; heading = a; }
                    }
                    hold = 3 + random.nextInt(5);
                }
                result = controller.towards(o, probe, o.x() + 112 * Math.cos(heading),
                        o.y() + 112 * Math.sin(heading), tic, random.nextDouble() < .3);
            }
        }
        lastX = o.x(); lastY = o.y(); lastTic = tic; previous = result.action();
        return result;
    }
}
