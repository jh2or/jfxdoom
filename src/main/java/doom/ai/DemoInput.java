package doom.ai;

import doom.system.GGame;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Bounded reader for ordinary Doom input recordings; never treats the end marker as success. */
final class DemoInput {
    static List<Action> read(Path path, int skill, int limit) throws IOException {
        return read(path, skill, limit, Level.E1M1);
    }

    static List<Action> read(Path path, int skill, int limit, Level level) throws IOException {
        long size = Files.size(path);
        if (size < 14 || size > 14L + limit * 4L || (size - 14) % 4 != 0) throw new IOException("Invalid demo size or episode limit");
        byte[] data = Files.readAllBytes(path);
        if ((data[0] & 255) != GGame.DEMOVERSION || data[1] != skill - 1 || data[2] != level.episode() || data[3] != level.map()
                || data[4] != 0 || data[5] != 0 || data[6] != 0 || data[7] != 0 || data[8] != 0
                || data[9] != 1 || data[10] != 0 || data[11] != 0 || data[12] != 0
                || (data[data.length - 1] & 255) != GGame.DEMOMARKER) throw new IOException("Expected a standard single-player " + level + " demo at the requested skill");
        List<Action> actions = new ArrayList<>();
        try {
            for (int i = 13; i < data.length - 1; i += 4) {
                actions.add(new Action(data[i], data[i + 1], data[i + 2] << 8, data[i + 3] & 255));
            }
        } catch (IllegalArgumentException e) { throw new IOException("Unsupported demo input", e); }
        return actions;
    }
}
