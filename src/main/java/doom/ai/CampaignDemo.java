package doom.ai;

import doom.play.PTick;
import doom.system.*;
import doom.wad.WWad;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Native continuous playback; no inventory injection, saves, retries or map warps. */
final class CampaignDemo {
    static final int MAX_TICS = 9 * (21000 + 4200);

    static int play(Path wad, Path demo, int skill, Level expected, boolean completed,
                    Entrance entrance, Route route) throws IOException {
        Level start = DemoInput.startLevel(demo);
        List<Action> actions = DemoInput.read(demo, skill, MAX_TICS, start);
        if (entrance != null && (actions.size() < entrance.prefix().size()
                || !actions.subList(0, entrance.prefix().size()).equals(entrance.prefix())))
            throw new IOException("Demo does not contain the supplied entrance history");
        new Episode(wad, skill, 21000, start);
        WWad.wShutdown(); DMain.dResetWadFiles();
        DMain.dDoomMain(new String[] {"-iwad", wad.toAbsolutePath().toString(), "-nosound", "-playdemo", demo.toAbsolutePath().toString()});
        GGame.gDoPlayDemo(); GGame.gDoLoadLevel(); PTick.gametic = 0;
        Doomstat.singledemo = false;
        List<Route> nodes = Route.steps(route); int endpoint = 0;
        int offset = entrance == null ? 0 : entrance.prefix().size();
        try {
            for (int i = 1; i <= actions.size(); i++) {
                if (!Doomstat.demoplayback) throw new IOException("Campaign demo ended prematurely");
                GGame.gTicker();
                if (GGame.players[0].health <= 0) throw new IOException("Campaign player died at tic " + i);
                if (Doomstat.gamestate == Doomstat.GS_LEVEL && PTick.leveltime > 21000)
                    throw new IOException("Campaign level exceeded ten minutes");
                if (entrance != null && i == offset && Fingerprint.capture() != entrance.fingerprint())
                    throw new IOException("Continuous playback does not reach the training entrance");
                if (endpoint < nodes.size() && i - offset == nodes.get(endpoint).tics) {
                    if (Fingerprint.capture() != nodes.get(endpoint).fingerprint)
                        throw new IOException("Campaign replay diverged at tic " + i);
                    endpoint++;
                }
                if (current().equals(expected) && GGame.gameaction == GGame.ga_completed
                        && (!completed || i != actions.size()))
                    throw new IOException("Unexpected completion before campaign endpoint");
            }
            if (!current().equals(expected) || endpoint != nodes.size()
                    || (completed ? GGame.gameaction != GGame.ga_completed
                        : Doomstat.gamestate != Doomstat.GS_LEVEL || PTick.leveltime != 1 || GGame.gameaction != GGame.ga_nothing))
                throw new IOException("Demo does not end at the requested " + (completed ? "completion" : "entrance") + ": " + expected);
            return PTick.leveltime;
        } finally { Doomstat.demoplayback = false; GGame.demoBuffer = null; }
    }

    static Level current() { return new Level(Doomstat.gameepisode, Doomstat.gamemap); }

    /** Advance the verified winning state using only ordinary intermission input. */
    static Level advance(List<Action> history) throws IOException {
        Level previous = current();
        if (GGame.gameaction != GGame.ga_completed) throw new IOException("Cannot advance an unfinished level");
        for (int i = 0; i < 4200; i++) {
            Action action = new Action(0, 0, 0, i % 35 == 1 ? Doomdef.BT_USE : 0);
            GGame.gTicker(action.command()); history.add(action);
            if (Doomstat.gamestate == Doomstat.GS_FINALE) throw new IOException("Campaign ended before the requested map");
            if (Doomstat.gamestate == Doomstat.GS_LEVEL && !current().equals(previous)) return current();
        }
        throw new IOException("Intermission exceeded its two-minute bound");
    }
}
