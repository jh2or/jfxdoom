package doom.ai;

import java.util.Locale;

/** Separate objectives; speed remains the default for existing commands. */
public enum LearningStrategy {
    SPEED, SURVIVAL;

    static LearningStrategy parse(String value) {
        try { return valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Strategy must be speed or survival", e); }
    }

    boolean better(RunTrace candidate, RunTrace incumbent) {
        if (candidate.outcome() != Episode.Outcome.COMPLETED || candidate.safety().exitHealth() <= 0) return false;
        if (incumbent == null || incumbent.outcome() != Episode.Outcome.COMPLETED) return true;
        if (this == SURVIVAL) {
            int comparison = candidate.safety().compareSafety(incumbent.safety());
            if (comparison != 0) return comparison > 0;
        }
        return candidate.route().tics < incumbent.route().tics;
    }
}
