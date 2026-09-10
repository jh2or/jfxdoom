package doom.ai;

import java.util.*;

/** Consistent per-tic movement diagnostics, shared by training and demo analysis. */
final class MovementMetrics {
    record Summary(int tics, int movementTics, int blockedTics, int useTics, int turningTics,
                   int idleTics, int revisits, double distance) {
        String csv() {
            return String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%d,%d,%.2f", tics, movementTics, blockedTics,
                    useTics, turningTics, idleTics, revisits, distance);
        }
        String description() {
            return String.format(Locale.ROOT, "game_seconds=%.2f blocked_seconds=%.2f blocked_movement=%.1f%% use_seconds=%.2f idle_seconds=%.2f revisits=%d distance=%.0f",
                    tics / 35.0, blockedTics / 35.0, 100.0 * blockedTics / Math.max(1, movementTics), useTics / 35.0, idleTics / 35.0, revisits, distance);
        }
    }
    private int tics, movement, blocked, use, turning, idle, revisits;
    private double distance;
    private final Set<Long> visited = new HashSet<>();
    private long lastCell = Long.MIN_VALUE;

    void record(Action action, double x, double y, double nextX, double nextY) {
        tics++;
        double moved = Math.hypot(nextX - x, nextY - y); distance += moved;
        if (action.forward() != 0 || action.side() != 0) {
            movement++;
            if (moved < .5) blocked++;
        } else if (action.turn() != 0) turning++;
        else idle++;
        if ((action.buttons() & doom.system.Doomdef.BT_USE) != 0) use++;
        long cell = ((long) (int) Math.floor(nextX / 64) << 32) | ((int) Math.floor(nextY / 64) & 0xffffffffL);
        if (cell != lastCell) { if (!visited.add(cell)) revisits++; lastCell = cell; }
    }

    Summary summary() { return new Summary(tics, movement, blocked, use, turning, idle, revisits, distance); }
}
