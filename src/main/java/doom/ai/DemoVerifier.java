package doom.ai;

import doom.play.PTick;
import doom.system.*;
import doom.wad.WWad;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/** Replay the exported artifact through the engine's actual external-demo startup path. */
final class DemoVerifier {
    static int verify(Path wad, Path demo, int skill, int limit, Route expected) throws IOException {
        return verify(wad, demo, skill, limit, expected, Level.E1M1);
    }

    static int verify(Path wad, Path demo, int skill, int limit, Route expected, Level level) throws IOException {
        byte[] data = Files.readAllBytes(demo);
        if (data.length < 14 || (data.length - 14) % 4 != 0 || (data[0] & 255) != GGame.DEMOVERSION
                || data[1] != skill - 1 || data[2] != level.episode() || data[3] != level.map()
                || data[4] != 0 || data[5] != 0 || data[6] != 0 || data[7] != 0 || data[8] != 0
                || data[9] != 1 || data[10] != 0 || data[11] != 0 || data[12] != 0
                || (data[data.length - 1] & 255) != GGame.DEMOMARKER) {
            throw new IOException("Expected a standard single-player " + level + " demo at the requested skill");
        }
        int count = (data.length - 14) / 4;
        if (count > limit) throw new IOException("Demo exceeds episode limit");
        // A new episode clears state from any previous attempt before CLI playback initializes it.
        new Episode(wad, skill, limit, level);
        WWad.wShutdown(); DMain.dResetWadFiles();
        DMain.dDoomMain(new String[] {"-iwad", wad.toAbsolutePath().toString(), "-nosound", "-playdemo", demo.toAbsolutePath().toString()});
        if (GGame.gameaction != GGame.ga_playdemo || DMain.advancedemo) throw new IOException("Playback not queued");
        GGame.gDoPlayDemo(); GGame.gDoLoadLevel(); PTick.gametic = 0;
        Doomstat.singledemo = false;
        List<Route> nodes = Route.steps(expected); int endpoint = 0;
        for (int tic = 1; tic <= count; tic++) {
            if (!Doomstat.demoplayback) throw new IOException("Demo ended before its command stream");
            GGame.gTicker();
            if (endpoint < nodes.size() && nodes.get(endpoint).tics == tic) {
                if (Fingerprint.capture() != nodes.get(endpoint).fingerprint) throw new IOException("Exported demo diverged at tic " + tic);
                endpoint++;
            }
            if (GGame.gameaction == GGame.ga_completed) {
                if (expected != null && (endpoint != nodes.size() || tic != expected.tics)) throw new IOException("Unexpected completion time");
                Doomstat.demoplayback = false;
                return tic;
            }
            if (GGame.players[0].health <= 0) throw new IOException("Demo player died at tic " + tic);
        }
        Doomstat.demoplayback = false;
        throw new IOException("Demo ended without completing " + level);
    }
}
