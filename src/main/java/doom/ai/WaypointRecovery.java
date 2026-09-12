package doom.ai;

/** Bounded reconnection to the current known waypoint after two quiet, unproductive seconds. */
final class WaypointRecovery {
    static final int QUIET_TICS = 70, ATTEMPT_TICS = 175, MAX_ATTEMPTS = 3;
    private final GeometryNavigator navigator = new GeometryNavigator();
    private int quietSince = -1, progressTic, cursor = -1, attempts, deadline, escapeUntil;
    private double closest = Double.POSITIVE_INFINITY;
    private GeometryNavigator.Point escape;
    private int triedDirections;

    int attempts() { return attempts; }
    boolean active() { return deadline > 0; }

    GeometryNavigator.Point choose(Observation o, int tic, int waypoint, RunTrace.Sample destination) {
        double distance = Math.hypot(destination.x() - o.x(), destination.y() - o.y());
        if (waypoint != cursor || distance < closest - 16) {
            cursor = waypoint; closest = distance; progressTic = tic;
            attempts = 0; triedDirections = 0; deadline = 0; escape = null;
        }
        if (o.enemy() != null || o.threats().stream().anyMatch(e -> e.health > 0)) {
            quietSince = -1; deadline = 0; escape = null;
            return null;
        }
        if (quietSince < 0) quietSince = tic;
        if (tic - quietSince < QUIET_TICS || tic - progressTic < QUIET_TICS) return null;
        if (deadline > 0 && tic >= deadline) { deadline = 0; escape = null; }
        if (deadline == 0) {
            if (attempts >= MAX_ATTEMPTS) return null;
            attempts++; deadline = tic + ATTEMPT_TICS;
            navigator.reset();
            // First try a planned detour. Later attempts back out along different legal directions.
            if (attempts > 1) { escape = backOff(o, destination); escapeUntil = tic + 35; }
        }
        if (escape != null && tic < escapeUntil && Math.hypot(escape.x() - o.x(), escape.y() - o.y()) > 12) return escape;
        escape = null;
        // Destination stays at the current route cursor; required keys/switches are never skipped.
        return navigator.towards(o, tic, new GeometryNavigator.Point(destination.x(), destination.y()), destination.z());
    }

    private GeometryNavigator.Point backOff(Observation o, RunTrace.Sample goal) {
        MotionProbe probe = new MotionProbe(o, true);
        double away = Math.atan2(o.y() - goal.y(), o.x() - goal.x());
        for (int i = 0; i < 8; i++) {
            if ((triedDirections & (1 << i)) != 0) continue;
            double angle = away + i * Math.PI / 4;
            double x = o.x() + 48 * Math.cos(angle), y = o.y() + 48 * Math.sin(angle);
            if (probe.distance(x, y) >= 48 - .01) {
                triedDirections |= 1 << i;
                return new GeometryNavigator.Point(x, y);
            }
        }
        return null;
    }
}
