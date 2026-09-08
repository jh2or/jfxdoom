package doom.ui;

import doom.system.Doomdef;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Bounded wall-clock adapter; never skips or reorders simulation commands. */
final class TicScheduler {
    static final long TIC_NS = 1_000_000_000L / Doomdef.TICRATE;
    static final int MAX_CATCH_UP_TICS = 4;
    private boolean started;
    private long lastNs;
    private long pendingNs;

    void pulse(long now, BooleanSupplier tick, IntConsumer display) {
        if (!started) {
            started = true;
            lastNs = now;
            return;
        }
        long elapsed = Math.max(0, now - lastNs);
        lastNs = now;
        // After suspension or sustained overload, slow game time relative to
        // wall time instead of retaining an unbounded backlog. Every actual
        // game tic still consumes exactly one command (including demo input).
        pendingNs = Math.min(MAX_CATCH_UP_TICS * TIC_NS,
                pendingNs + Math.min(elapsed, MAX_CATCH_UP_TICS * TIC_NS));
        int advanced = 0;
        while (pendingNs >= TIC_NS) {
            pendingNs -= TIC_NS;
            if (!tick.getAsBoolean()) {
                pendingNs = 0;
                return; // Quit/relaunch during a tic: don't paint the old scene.
            }
            advanced++;
        }
        if (advanced > 0) display.accept(advanced);
    }
}
