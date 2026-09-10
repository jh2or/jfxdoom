package doom.ai;

import java.util.*;
import java.util.function.BooleanSupplier;

/** A route's observed trajectory. Optimization may use only positions already reached in this trace. */
record RunTrace(Route route, List<Sample> samples, Episode.Outcome outcome, MovementMetrics.Summary metrics) {
    record Sample(int tic, double x, double y, double z, double angle, int health, int keys, int usedLine, Inventory inventory, int weapons) {
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
                Integer.toUnsignedLong(mo.angle) * (2 * Math.PI / 4294967296.0), e.player().health, Observation.keys(e.player()), -1, Inventory.read(e.player()), Observation.weapons(e.player()));
    }

    /** Observe a nearby switch texture change caused by a fresh ordinary Use press. */
    static Sample step(Episode e, Action action) {
        Map<Integer, Long> before = new LinkedHashMap<>();
        if ((action.buttons() & doom.system.Doomdef.BT_USE) != 0 && e.player().usedown == 0) {
            for (int i = 0; i < doom.play.PSetup.lines.length; i++) {
                var l = doom.play.PSetup.lines[i];
                if (Observation.distanceToLine(e.player().mo.x / 65536.0, e.player().mo.y / 65536.0, l) <= 80)
                    before.put(i, texture(l));
            }
        }
        e.step(action);
        Sample p = sample(e);
        for (var old : before.entrySet()) if (old.getValue() != texture(doom.play.PSetup.lines[old.getKey()]))
            return new Sample(p.tic(), p.x(), p.y(), p.z(), p.angle(), p.health(), p.keys(), old.getKey(), p.inventory(), p.weapons());
        return p;
    }

    private static long texture(doom.render.Line line) {
        long h = 0;
        for (int side : line.sidenum) if (side >= 0 && side < doom.play.PSetup.sides.length) {
            var s = doom.play.PSetup.sides[side];
            h = 31 * h + s.toptexture; h = 31 * h + s.midtexture; h = 31 * h + s.bottomtexture;
        }
        return h;
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
