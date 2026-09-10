package doom.ai;

import java.util.*;
import java.util.function.BooleanSupplier;

/** A route's observed trajectory. Optimization may use only positions already reached in this trace. */
record RunTrace(Route route, List<Sample> samples, Episode.Outcome outcome, MovementMetrics.Summary metrics) {
    record Sample(int tic, double x, double y, double z, double angle, int health) {}

    static Sample sample(Episode e) {
        var mo = e.player().mo;
        return new Sample(e.tics(), mo.x / 65536.0, mo.y / 65536.0, mo.z / 65536.0,
                Integer.toUnsignedLong(mo.angle) * (2 * Math.PI / 4294967296.0), e.player().health);
    }

    static List<Action> actions(Route route) {
        List<Action> result = new ArrayList<>();
        for (Route node : Route.steps(route)) for (int i = 0; i < node.duration; i++) result.add(node.action);
        return result;
    }

    static RunTrace play(Episode e, List<Action> actions, BooleanSupplier stop) {
        e.reset();
        List<Sample> samples = new ArrayList<>(); samples.add(sample(e));
        Route route = null;
        for (int i = 0; i < actions.size() && e.outcome() == Episode.Outcome.RUNNING && !stop.getAsBoolean();) {
            Action action = actions.get(i); int count = 0;
            do {
                e.step(action); i++; count++; samples.add(sample(e));
            } while (i < actions.size() && count < 32 && actions.get(i).equals(action)
                    && e.outcome() == Episode.Outcome.RUNNING && !stop.getAsBoolean());
            route = new Route(route, action, count, Fingerprint.capture());
        }
        return new RunTrace(route, samples, e.outcome(), e.metrics());
    }
}
