package doom.ai;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Proposes loop deletion and feedback-controlled shortcuts along an already successful trajectory. */
final class RouteOptimizer {
    private final LearnerRandom random;
    RouteOptimizer(LearnerRandom random) { this.random = random; }

    RunTrace propose(Episode episode, RunTrace incumbent, long attempt, BooleanSupplier stop) {
        if (attempt % 3 == 0) return follow(episode, incumbent, attempt / 3, stop);
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

    private RunTrace follow(Episode e, RunTrace incumbent, long variant, BooleanSupplier stop) {
        e.reset();
        List<RunTrace.Sample> path = incumbent.samples();
        List<RunTrace.Sample> observed = new ArrayList<>(); observed.add(RunTrace.sample(e));
        int cursor = 0;
        Route route = null;
        int horizon = new int[] {256, 192, 128}[(int) (variant % 3)];
        int lastProgress = 0;
        while (e.outcome() == Episode.Outcome.RUNNING && e.tics() < incumbent.route().tics - 1 && !stop.getAsBoolean()) {
            Observation o = Observation.read(e);
            MotionProbe probe = new MotionProbe(o);
            int next = cursor;
            // Loop erasure and shortcutting use only this demonstrated path and current local geometry.
            for (int i = cursor + 1; i < path.size(); i += 4) {
                RunTrace.Sample p = path.get(i);
                double d = Math.hypot(p.x() - o.x(), p.y() - o.y());
                if (d <= horizon && Math.abs(p.z() - o.z()) <= 24 && probe.distance(p.x(), p.y()) >= d - .01) next = i;
            }
            if (next > cursor) { cursor = next; lastProgress = e.tics(); }
            RunTrace.Sample target = path.get(cursor);
            if (Math.hypot(target.x() - o.x(), target.y() - o.y()) < 20 && cursor < path.size() - 1) {
                cursor = Math.min(path.size() - 1, cursor + 4); target = path.get(cursor);
            }
            double tx = target.x(), ty = target.y();
            if (cursor >= path.size() - 5 && Math.hypot(tx - o.x(), ty - o.y()) < 28) {
                // At the learned endpoint, reproduce its facing and try the ordinary use action.
                var end = path.getLast();
                tx = o.x() + 48 * Math.cos(end.angle()); ty = o.y() + 48 * Math.sin(end.angle());
            }
            double d = Math.hypot(tx - o.x(), ty - o.y());
            if (d > 250) { tx = o.x() + (tx - o.x()) * 250 / d; ty = o.y() + (ty - o.y()) * 250 / d; }
            Explorer.Burst burst = LocalController.towards(o, probe, tx, ty, e.tics(), variant % 2 == 1);
            int count = 0;
            while (count < burst.duration() && e.outcome() == Episode.Outcome.RUNNING && !stop.getAsBoolean()) {
                e.step(burst.action()); count++; observed.add(RunTrace.sample(e));
            }
            if (count > 0) route = new Route(route, burst.action(), count, Fingerprint.capture());
            // A failed proposal is distinct from a death/timeout; preserve the incumbent.
            if (e.tics() - lastProgress > 350) break;
        }
        return new RunTrace(route, observed, e.outcome(), e.metrics());
    }
}
