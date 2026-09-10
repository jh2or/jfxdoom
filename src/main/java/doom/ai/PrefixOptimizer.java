package doom.ai;

import java.util.function.BooleanSupplier;

/** Shorten a discovered route to a useful intermediate state before the first complete solution. */
final class PrefixOptimizer {
    static Route shorten(Episode episode, Route original, RouteOptimizer optimizer, long variant, BooleanSupplier stop) {
        RunTrace learned = RunTrace.play(episode, RunTrace.actions(original), stop);
        if (stop.getAsBoolean() || learned.outcome() != Episode.Outcome.RUNNING) return original;
        RunTrace.Sample goal = learned.samples().getLast();
        BooleanSupplier reached = () -> {
            var p = episode.player();
            return episode.tics() > 0 && p.health >= goal.health()
                    && (Observation.keys(p) & goal.keys()) == goal.keys()
                    && (Observation.weapons(p) & goal.weapons()) == goal.weapons()
                    && Math.abs(p.mo.z / 65536.0 - goal.z()) <= 24
                    && Math.hypot(p.mo.x / 65536.0 - goal.x(), p.mo.y / 65536.0 - goal.y()) < 24;
        };
        RunTrace candidate = optimizer.propose(episode, learned, variant * 3, () -> stop.getAsBoolean() || reached.getAsBoolean());
        if (!stop.getAsBoolean() && candidate.route() != null && candidate.route().tics < original.tics
                && (candidate.outcome() == Episode.Outcome.COMPLETED
                || (candidate.outcome() == Episode.Outcome.RUNNING && reached.getAsBoolean()))) return candidate.route();
        return original;
    }
}
