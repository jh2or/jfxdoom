package doom.ai;

import doom.system.GGame;
import java.util.Locale;

/** Original Doom episode/map selection, shared by simulation, checkpoints and demos. */
public record Level(int episode, int map) {
    public static final Level E1M1 = new Level(1, 1);
    public Level {
        if (episode < 1 || episode > 3 || map < 1 || map > 9)
            throw new IllegalArgumentException("Expected an original Doom level E1M1..E3M9");
    }
    public static Level parse(String name) {
        String value = name.toUpperCase(Locale.ROOT);
        if (!value.matches("E[1-3]M[1-9]")) throw new IllegalArgumentException("Invalid Doom map: " + name);
        return new Level(value.charAt(1) - '0', value.charAt(3) - '0');
    }
    public int parSeconds() { return GGame.pars[episode][map]; }
    @Override public String toString() { return "E" + episode + "M" + map; }
}
