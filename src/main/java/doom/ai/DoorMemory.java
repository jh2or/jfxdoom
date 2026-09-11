package doom.ai;

import doom.play.Player;
import doom.render.Line;
import doom.system.Dstrings;
import doom.system.Doomdef;
import doom.ui.HuStuff;
import java.util.*;

/** Remembers lock requirements from ordinary player-visible denial messages, never line specials. */
final class DoorMemory {
    private final Map<Line, Integer> requirements = new IdentityHashMap<>();

    void reset() { requirements.clear(); }

    /** Called only after a fresh Use edge, after movement and the game's HUD ticker. */
    void observe(Player player) {
        // The HUD consumes Player.message. Its counter is reset only for a fresh displayed message.
        // Do not reuse pending/old messages when HUD messages are disabled or suppressed.
        if (HuStuff.messageCounter != HuStuff.HU_MSGTIMEOUT) return;
        var text = HuStuff.wMessage.l[HuStuff.wMessage.cl];
        String message = new String(text.l, 0, text.len);
        if (requiredKeys(message) == 0) return;
        var mo = player.mo;
        double angle = Integer.toUnsignedLong(mo.angle) * (2 * Math.PI / 4294967296.0);
        var o = new Observation(mo.x / 65536., mo.y / 65536., angle, player.health,
                0, 0, 0, null, mo.z / 65536., mo.height / 65536., mo.radius / 65536., 0, 0, 0, Observation.keys(player));
        denied(new MotionProbe(o).useTarget(angle, Set.of()), message);
    }

    void denied(Line line, String message) {
        int keys = requiredKeys(message);
        if (line != null && keys != 0) requirements.put(line, keys);
    }

    Set<Line> blocked(int keys) {
        Set<Line> blocked = new HashSet<>();
        requirements.forEach((line, required) -> { if ((keys & required) == 0) blocked.add(line); });
        return Set.copyOf(blocked);
    }

    private static int requiredKeys(String message) {
        if (Dstrings.PD_BLUEK.equals(message) || Dstrings.PD_BLUEO.equals(message)) return (1 << Doomdef.it_bluecard) | (1 << Doomdef.it_blueskull);
        if (Dstrings.PD_YELLOWK.equals(message) || Dstrings.PD_YELLOWO.equals(message)) return (1 << Doomdef.it_yellowcard) | (1 << Doomdef.it_yellowskull);
        if (Dstrings.PD_REDK.equals(message) || Dstrings.PD_REDO.equals(message)) return (1 << Doomdef.it_redcard) | (1 << Doomdef.it_redskull);
        return 0;
    }
}
