package doom.ai;

import java.util.*;
import java.util.function.BooleanSupplier;

/** A route's observed trajectory. Optimization may use only positions already reached in this trace. */
record RunTrace(Route route, List<Sample> samples, Episode.Outcome outcome, MovementMetrics.Summary metrics) {
    record Sample(int tic, double x, double y, double z, double angle, int health, int keys, int usedLine, Inventory inventory, int weapons, Set<Integer> interactions, Set<Integer> mechanisms) {
        Sample { interactions = Set.copyOf(interactions); mechanisms = Set.copyOf(mechanisms); }
        Sample(int tic, double x, double y, double z, double angle, int health, int keys, int usedLine, Inventory inventory, int weapons, Set<Integer> interactions) {
            this(tic, x, y, z, angle, health, keys, usedLine, inventory, weapons, interactions, Set.of());
        }
        Sample(int tic, double x, double y, double z, double angle, int health, int keys, int usedLine, Inventory inventory, int weapons) {
            this(tic, x, y, z, angle, health, keys, usedLine, inventory, weapons, Set.of());
        }
        Sample(int tic, double x, double y, double z, double angle, int health, int keys, int usedLine) {
            this(tic, x, y, z, angle, health, keys, usedLine, Inventory.PISTOL_START, 3);
        }
        Sample(int tic, double x, double y, double z, double angle, int health, int keys) {
            this(tic, x, y, z, angle, health, keys, -1);
        }
    }

    static Sample sample(Episode e) {
        var mo = e.player().mo;
        return new Sample(e.tics(), mo.x / 65536.0, mo.y / 65536.0, mo.z / 65536.0,
                Integer.toUnsignedLong(mo.angle) * (2 * Math.PI / 4294967296.0), e.player().health, Observation.keys(e.player()), e.lastUsedLine(), Inventory.read(e.player()), Observation.weapons(e.player()), e.interactions(), e.mechanisms());
    }

    /** The episode observes interactions for training, prefix replay and trajectory replay alike. */
    static Sample step(Episode e, Action action) {
        e.step(action);
        return sample(e);
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
                samples.add(step(e, action)); i++; count++;
            } while (i < actions.size() && count < 32 && actions.get(i).equals(action)
                    && e.outcome() == Episode.Outcome.RUNNING && !stop.getAsBoolean());
            route = new Route(route, action, count, Fingerprint.capture());
        }
        return new RunTrace(route, samples, e.outcome(), e.metrics());
    }
}
