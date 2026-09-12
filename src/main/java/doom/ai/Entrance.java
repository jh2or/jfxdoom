package doom.ai;

import doom.play.*;
import doom.render.RMain;
import doom.system.*;
import doom.ui.MMenu;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** An immutable, earned level entrance. Durable identity is its complete input history. */
final class Entrance {
    private final byte[] saved;
    private final int gametic, validcount, levelTics;
    private final int[] targets, tracers, sounds;
    private final long fingerprint;
    private final boolean didSecret;
    private final int attacker;
    private final List<Action> prefix;
    private final Level level, start;

    private Entrance(List<Action> prefix, Level level, Level start) throws IOException {
        if (Doomstat.gamestate != Doomstat.GS_LEVEL || GGame.gameaction != GGame.ga_nothing
                || PTick.leveltime != 1 || GGame.players[0].health <= 0)
            throw new IOException("Expected a live entrance immediately after the first level tic");
        this.prefix = List.copyOf(prefix); this.level = level; this.start = start;
        levelTics = PTick.leveltime; gametic = PTick.gametic; validcount = RMain.validcount;
        saved = SaveGame.save(); fingerprint = Fingerprint.capture();
        List<Mobj> actors = actors();
        didSecret = GGame.players[0].didsecret; attacker = actors.indexOf(GGame.players[0].attacker);
        targets = new int[actors.size()]; tracers = new int[actors.size()];
        for (int i = 0; i < actors.size(); i++) {
            targets[i] = actors.indexOf(actors.get(i).target);
            tracers[i] = actors.indexOf(actors.get(i).tracer);
        }
        sounds = new int[PSetup.sectors.length];
        for (int i = 0; i < sounds.length; i++) sounds[i] = actors.indexOf(PSetup.sectors[i].soundtarget);
    }

    static Entrance load(Path wad, int skill, Path demo, Level expected) throws IOException {
        Level start = DemoInput.startLevel(demo);
        List<Action> prefix = DemoInput.read(demo, skill, CampaignDemo.MAX_TICS, start);
        CampaignDemo.play(wad, demo, skill, expected, false, null, null);
        return new Entrance(prefix, expected, start);
    }

    void restore() {
        try {
            GGame.demorecording = Doomstat.demoplayback = Doomstat.singledemo = false;
            GGame.demoBuffer = null; GGame.demoP = 0; GGame.secretexit = 0;
            MMenu.menuactive = false;
            SaveGame.load(saved);
            PTick.gametic = gametic; RMain.validcount = validcount;
            List<Mobj> actors = actors();
            if (actors.size() != targets.length) throw new IOException("Entrance actor count changed");
            for (int i = 0; i < actors.size(); i++) {
                actors.get(i).target = targets[i] < 0 ? null : actors.get(targets[i]);
                actors.get(i).tracer = tracers[i] < 0 ? null : actors.get(tracers[i]);
            }
            GGame.players[0].didsecret = didSecret;
            GGame.players[0].attacker = attacker < 0 ? null : actors.get(attacker);
            for (int i = 0; i < sounds.length; i++)
                PSetup.sectors[i].soundtarget = sounds[i] < 0 ? null : actors.get(sounds[i]);
            if (Fingerprint.capture() != fingerprint) throw new IOException("Entrance restore diverged");
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static List<Mobj> actors() {
        List<Mobj> result = new ArrayList<>();
        for (Thinker t = PTick.thinkercap.next; t != PTick.thinkercap; t = t.next)
            if (t instanceof Mobj mo) result.add(mo);
        return result;
    }
    List<Action> prefix() { return prefix; }
    int levelTics() { return levelTics; }
    long fingerprint() { return fingerprint; }
    Level level() { return level; }

    void demo(Path path, int skill, Route route) throws IOException {
        List<Action> actions = new ArrayList<>(prefix);
        actions.addAll(RunTrace.actions(route));
        Route.demoActions(path, skill, actions, start);
    }
    String description() {
        var p = GGame.players[0];
        return "map=" + level + "\nprefix_tics=" + prefix.size() + "\nlevel_tics=" + levelTics
                + "\nfingerprint=" + Long.toUnsignedString(fingerprint) + "\nhealth=" + p.health
                + "\narmor=" + p.armorpoints + "\narmor_type=" + p.armortype + "\nweapons=" + Observation.weapons(p)
                + "\nammo=" + Arrays.toString(p.ammo) + "\nmax_ammo=" + Arrays.toString(p.maxammo)
                + "\nbackpack=" + p.backpack + "\nkeys=" + Observation.keys(p) + "\n";
    }
}
