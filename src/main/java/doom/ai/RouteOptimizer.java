package doom.ai;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Proposes loop deletion and feedback-controlled shortcuts along an already successful trajectory. */
final class RouteOptimizer {
    private final LearnerRandom random;
    RouteOptimizer(LearnerRandom random) { this.random = random; }

    RunTrace propose(Episode episode, RunTrace incumbent, long attempt, BooleanSupplier stop) {
        if (attempt % 3 == 0) return follow(episode, incumbent, attempt / 3, stop);
        if (attempt % 3 == 1 && incumbent.route().tics > 2100) {
            int start = random.nextInt(incumbent.samples().size() - 64);
            int variant = random.nextInt(288);
            // Mix ordinary, mechanism-preserving and faster-turning suffix proposals.
            if (variant >= 144) variant += 48;
            return followFrom(episode, incumbent, variant, start, stop);
        }
        List<Action> original = RunTrace.actions(incumbent.route());
        List<RunTrace.Sample> points = incumbent.samples();
        int from = -1, to = -1;
        int largest = 0;
        // Prefer deleting long returns to approximately the same position and facing.
        for (int trial = 0; trial < 1500; trial++) {
            int a = random.nextInt(points.size() - 1);
            int b = a + 1 + random.nextInt(points.size() - a - 1);
            RunTrace.Sample p = points.get(a), q = points.get(b);
            if (b - a > largest && Math.hypot(p.x() - q.x(), p.y() - q.y()) < 8
                    && Math.abs(p.z() - q.z()) < 1 && Math.abs(LocalController.difference(p.angle(), q.angle())) < .025) {
                from = a; to = b; largest = b - a;
            }
        }
        if (from < 0) {
            int width = Math.min(original.size() - 1, 4 << random.nextInt(10));
            from = random.nextInt(original.size() - width); to = from + width;
        }
        List<Action> candidate = new ArrayList<>(original.subList(0, from));
        candidate.addAll(original.subList(to, original.size()));
        // Re-simulation is mandatory: time deletion changes actors, RNG and physics.
        return RunTrace.play(episode, candidate, stop);
    }

    static int keyFrontier(List<RunTrace.Sample> path, int cursor, int keys) {
        for (int i = cursor; i < path.size(); i++) if ((path.get(i).keys() & ~keys) != 0) return i;
        return path.size() - 1;
    }

    static int interactionFrontier(List<RunTrace.Sample> path, int cursor, int keys, Set<Integer> activated) {
        int frontier = keyFrontier(path, cursor, keys);
        for (int i = cursor; i <= frontier; i++) {
            int line = path.get(i).usedLine();
            if (line >= 0 && !activated.contains(line)) return i;
        }
        return frontier;
    }

    static int equipmentFrontier(List<RunTrace.Sample> path, int cursor, int keys, int weapons, Set<Integer> activated) {
        int frontier = interactionFrontier(path, cursor, keys, activated);
        int usefulGuns = (1 << doom.system.Doomdef.wp_shotgun) | (1 << doom.system.Doomdef.wp_chaingun)
                | (1 << doom.system.Doomdef.wp_plasma);
        for (int i = cursor; i <= frontier; i++)
            if ((path.get(i).weapons() & usefulGuns & ~weapons) != 0) return i;
        return frontier;
    }

    private RunTrace follow(Episode e, RunTrace incumbent, long variant, BooleanSupplier stop) {
        return followFrom(e, incumbent, variant, 0, stop);
    }

    RunTrace followFrom(Episode e, RunTrace incumbent, long variant, int start, BooleanSupplier stop) {
        return followFrom(e, incumbent, variant, start, RunTrace.actions(incumbent.route()).subList(0, start), stop);
    }

    RunTrace followFrom(Episode e, RunTrace incumbent, long variant, int start, List<Action> prefix, BooleanSupplier stop) {
        e.reset();
        int turnLimit = variant >= 192 ? 1 << (12 + (int) (variant / 48) % 3) : 2048;
        LocalController controller = new LocalController(variant % 4 >= 2, turnLimit);
        List<RunTrace.Sample> path = incumbent.samples();
        List<RunTrace.Sample> observed = new ArrayList<>(); observed.add(RunTrace.sample(e));
        int cursor = start;
        Set<Integer> activated = new HashSet<>();
        Route route = null;
        if (!prefix.isEmpty()) {
            // Re-simulate the preserved inputs; never restore an unverified world snapshot.
            for (int i = 0; i < prefix.size() && e.outcome() == Episode.Outcome.RUNNING && !stop.getAsBoolean();) {
                Action action = prefix.get(i); int count = 0;
                do {
                    observed.add(RunTrace.step(e, action)); i++; count++;
                } while (i < prefix.size() && count < 32 && prefix.get(i).equals(action)
                        && e.outcome() == Episode.Outcome.RUNNING && !stop.getAsBoolean());
                route = new Route(route, action, count, Fingerprint.capture());
            }
            activated.addAll(e.interactions());
        }
        int[] horizons = {256, 192, 128, 224, 160, 96, 240, 208, 176, 144, 112, 80};
        int horizon = horizons[(int) (variant % horizons.length)];
        boolean fight = (variant % 2 == 1) ^ ((variant / horizons.length) % 2 == 1);
        int lastProgress = e.tics(), lastKills = e.player().killcount;
        doom.play.Mobj lastEnemy = null; int enemyHealth = 0;
        while (e.outcome() == Episode.Outcome.RUNNING && e.tics() < incumbent.route().tics - 1 && !stop.getAsBoolean()) {
            Observation o = Observation.read(e);
            if (o.enemy() != null) {
                if (o.enemy() == lastEnemy && o.enemy().health < enemyHealth) lastProgress = e.tics();
                lastEnemy = o.enemy(); enemyHealth = lastEnemy.health;
            }
            MotionProbe probe = new MotionProbe(o);
            MotionProbe cautious = new MotionProbe(o, true);
            // Never erase the demonstrated acquisition of a key we do not yet own.
            // Some demonstrated switches are optional. Alternate retaining their interactions and trying to omit them.
            int frontier = variant % 2 == 1 ? equipmentFrontier(path, cursor, o.keys(), o.weapons(), activated)
                    : keyFrontier(path, cursor, o.keys());
            if (variant >= 48 && variant < 144) {
                for (int i = cursor; i <= frontier; i++) if (!e.mechanisms().containsAll(path.get(i).mechanisms())) { frontier = i; break; }
            }
            int next = cursor;
            // Loop erasure and shortcutting use only this demonstrated path and current local geometry.
            for (int i = cursor + 1; i <= frontier; i = Math.min(frontier, i + 4)) {
                RunTrace.Sample p = path.get(i);
                double d = Math.hypot(p.x() - o.x(), p.y() - o.y());
                if (d <= horizon && p.z() <= o.z() + 24 && (variant >= 96 && variant < 144 && p.z() >= o.z() - 24 ? cautious : probe).distance(p.x(), p.y()) >= d - .01) next = i;
                if (i == frontier) break;
            }
            if (next > cursor) { cursor = next; lastProgress = e.tics(); }
            RunTrace.Sample target = path.get(cursor);
            if (Math.hypot(target.x() - o.x(), target.y() - o.y()) < (variant >= 96 ? 40 : 20) && cursor < path.size() - 1) {
                cursor = Math.min(frontier, cursor + 4); target = path.get(cursor);
            }
            double tx = target.x(), ty = target.y();
            boolean switchTarget = target.usedLine() >= 0 && !activated.contains(target.usedLine());
            if ((switchTarget || cursor >= path.size() - 5) && Math.hypot(tx - o.x(), ty - o.y()) < 28) {
                // At the learned endpoint, reproduce its facing and try the ordinary use action.
                var end = switchTarget ? target : path.getLast();
                if (switchTarget) {
                    var line = doom.play.PSetup.lines[end.usedLine()];
                    tx = ((long) line.v1.x + line.v2.x) / 131072.;
                    ty = ((long) line.v1.y + line.v2.y) / 131072.;
                } else {
                    tx = o.x() + 48 * Math.cos(end.angle()); ty = o.y() + 48 * Math.sin(end.angle());
                }
            }
            double d = Math.hypot(tx - o.x(), ty - o.y());
            if (d > 250) { tx = o.x() + (tx - o.x()) * 250 / d; ty = o.y() + (ty - o.y()) * 250 / d; }
            if (variant >= 96 && variant < 144 && target.z() >= o.z() - 24) probe = cautious;
            if (variant >= 48 && variant < 144 && !e.mechanisms().containsAll(target.mechanisms())
                    && Math.hypot(tx - o.x(), ty - o.y()) < 48 && target.usedLine() < 0) {
                var ahead = path.get(Math.min(path.size() - 1, cursor + 8));
                double dx = ahead.x() - target.x(), dy = ahead.y() - target.y(), length = Math.hypot(dx, dy);
                if (length > 1) { tx = target.x() + dx * 48 / length; ty = target.y() + dy * 48 / length; }
            }
            Explorer.Burst burst = controller.towards(o, probe, tx, ty, e.tics(), fight);
            int count = 0;
            while (count < burst.duration() && e.outcome() == Episode.Outcome.RUNNING && !stop.getAsBoolean()) {
                var sample = RunTrace.step(e, burst.action()); count++; observed.add(sample);
                if (sample.usedLine() >= 0) activated.add(sample.usedLine());
            }
            if (count > 0) route = new Route(route, burst.action(), count, Fingerprint.capture());
            if (e.player().killcount > lastKills) { lastKills = e.player().killcount; lastProgress = e.tics(); }
            // A failed proposal is distinct from a death/timeout; preserve the incumbent.
            if (e.tics() - lastProgress > 350) break;
        }
        return new RunTrace(route, observed, e.outcome(), e.metrics());
    }
}
