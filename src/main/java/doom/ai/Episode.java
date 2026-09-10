package doom.ai;

import doom.play.*;
import doom.render.RMain;
import doom.system.*;
import doom.ui.*;
import doom.wad.WWad;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/** Single-owner adapter to the static engine. Never run two instances concurrently. */
public final class Episode {
    public enum Outcome { RUNNING, COMPLETED, DEAD, TIMEOUT }
    private final int skill;
    private final Level level;
    private final int limit;
    private int tics;
    private Outcome outcome;
    private MovementMetrics movement = new MovementMetrics();

    public Episode(Path wad, int skill, int limit) throws IOException { this(wad, skill, limit, Level.E1M1); }

    public Episode(Path wad, int skill, int limit, Level level) throws IOException {
        this.level = java.util.Objects.requireNonNull(level);
        if (skill < 1 || skill > 5 || limit < 1 || limit > 21000) {
            throw new IllegalArgumentException("Skill must be 1..5; episode tics 1..21000");
        }
        this.skill = skill;
        this.limit = limit;
        WWad.wShutdown();
        DMain.dResetWadFiles();
        GGame.demorecording = Doomstat.demoplayback = Doomstat.singledemo = false;
        DMain.dDoomMain(new String[] {"-iwad", wad.toAbsolutePath().toString(), "-nosound"});
        if (Doomstat.gamemode == Doomstat.GameMode.commercial || WWad.wCheckNumForName(level.toString()) < 0) {
            throw new IllegalArgumentException("Training requires a Doom 1 WAD containing " + level);
        }
        reset();
    }

    public void reset() {
        GGame.demorecording = Doomstat.demoplayback = Doomstat.singledemo = false;
        GGame.demoBuffer = null;
        GGame.demoP = 0;
        GGame.secretexit = 0;
        GGame.turnheld = 0;
        GGame.consoleplayer = 0;
        Doomstat.netgame = Doomstat.netdemo = false;
        Doomstat.deathmatch = 0;
        Doomstat.fastparm = Doomstat.respawnparm = Doomstat.respawnmonsters = Doomstat.nomonsters = false;
        DMain.advancedemo = false;
        MMenu.menuactive = false;
        AmMap.automapactive = false;
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            GGame.players[i] = new Player();
            GGame.playeringame[i] = i == 0;
        }
        PMobj.iquehead = PMobj.iquetail = 0;
        Arrays.fill(PMobj.itemrespawnque, null);
        Arrays.fill(PMobj.itemrespawntime, 0);
        PTick.gametic = 0;
        RMain.validcount = 1;
        GGame.gInitNew(skill - 1, level.episode(), level.map());
        GGame.gDoLoadLevel();
        HuStuff.huStart();
        StStuff.stStart();
        tics = 0;
        movement = new MovementMetrics();
        outcome = Outcome.RUNNING;
    }

    public Outcome step(Action action) {
        if (outcome != Outcome.RUNNING) throw new IllegalStateException("Episode already ended: " + outcome);
        double x = player().mo.x / 65536.0, y = player().mo.y / 65536.0;
        GGame.gTicker(action.command());
        movement.record(action, x, y, player().mo.x / 65536.0, player().mo.y / 65536.0);
        tics++;
        outcome = classify(GGame.gameaction == GGame.ga_completed,
                player().playerstate == Player.PST_DEAD || player().health <= 0, tics, limit);
        return outcome;
    }

    static Outcome classify(boolean completed, boolean dead, int tics, int limit) {
        if (completed) return Outcome.COMPLETED;
        if (dead) return Outcome.DEAD;
        return tics >= limit ? Outcome.TIMEOUT : Outcome.RUNNING;
    }

    public Player player() { return GGame.players[0]; }
    MovementMetrics.Summary metrics() { return movement.summary(); }
    public int tics() { return tics; }
    public int limit() { return limit; }
    public Level level() { return level; }
    public int skill() { return skill; }
    public Outcome outcome() { return outcome; }
}
