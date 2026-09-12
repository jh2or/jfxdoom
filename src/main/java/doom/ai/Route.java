package doom.ai;

import doom.system.GGame;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Immutable shared action prefixes; each node has an endpoint fingerprint. */
public final class Route {
    public final Route parent;
    public final Action action;
    public final int duration;
    public final int tics;
    public final long fingerprint;

    public Route(Route parent, Action action, int duration, long fingerprint) {
        if (duration < 1 || duration > 32) throw new IllegalArgumentException("Burst must be 1..32 tics");
        this.parent = parent;
        this.action = action;
        this.duration = duration;
        this.tics = (parent == null ? 0 : parent.tics) + duration;
        this.fingerprint = fingerprint;
    }

    public static List<Route> steps(Route end) {
        List<Route> result = new ArrayList<>();
        for (Route n = end; n != null; n = n.parent) result.add(n);
        Collections.reverse(result);
        return result;
    }

    public static void replay(Episode episode, Route end) {
        episode.reset();
        for (Route n : steps(end)) {
            for (int i = 0; i < n.duration; i++) episode.step(n.action);
            if (Fingerprint.capture() != n.fingerprint) {
                throw new IllegalStateException("Replay diverged at tic " + episode.tics());
            }
        }
    }

    public static void demo(Path path, int skill, Route end) throws IOException {
        demo(path, skill, end, Level.E1M1);
    }

    public static void demo(Path path, int skill, Route end, Level level) throws IOException {
        demoActions(path, skill, RunTrace.actions(end), level);
    }

    static void demoActions(Path path, int skill, List<Action> actions, Level level) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(new byte[] {(byte) GGame.DEMOVERSION, (byte) (skill - 1), (byte) level.episode(), (byte) level.map(),
                0, 0, 0, 0, 0, 1, 0, 0, 0});
        for (Action action : actions) {
            int packed = action.packed();
            for (int b = 0; b < 4; b++) bytes.write(packed >>> (8 * b));
        }
        bytes.write(GGame.DEMOMARKER);
        atomicWrite(path, bytes.toByteArray());
    }

    static void atomicWrite(Path path, byte[] bytes) throws IOException {
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), ".training-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
