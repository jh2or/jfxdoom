package doom.ai;

import doom.render.Line;
import java.util.IdentityHashMap;

/** Local interaction memory. Infers opening/closing from geometry, without reading line specials. */
final class UseController {
    private static final int RETRY_TICS = 8;
    private static final class Seen {
        double gap;
        int changed, pressed = -RETRY_TICS;
        Seen(double gap, int tic) { this.gap = gap; changed = tic; }
    }
    private final IdentityHashMap<Line, Seen> seen = new IdentityHashMap<>();
    // An archived prefix (and a new Doom player) may end with use held down.
    private boolean held = true;

    void reset() { seen.clear(); held = true; }

    boolean press(MotionProbe probe, double angle, int tic) {
        Line target = probe.useTarget(angle, seen.keySet());
        boolean release = held;
        held = false;
        if (target == null) return false;
        double gap = MotionProbe.opening(target);
        Seen old = seen.computeIfAbsent(target, l -> new Seen(gap, tic));
        boolean closing = gap < old.gap;
        if (gap != old.gap) old.changed = tic;
        old.gap = gap;
        // Observe a moving passage until it is traversable. A closing door can be reopened.
        boolean opening = target.backsector != null && gap > 0 && !closing;
        if (release || tic - old.pressed < RETRY_TICS
                || (opening && !probe.switchPanel(target) && (!probe.blocks(target) || tic - old.changed < RETRY_TICS))) return false;
        old.pressed = tic;
        held = true;
        return true;
    }
}
