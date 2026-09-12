package doom.ai;

import java.util.Locale;

/** Read-only per-tic diagnostics. Losses are observed net decreases, not engine damage hooks. */
final class SurvivalMetrics {
    record Summary(int minimumHealth, int healthLost, int armorLost, int criticalTics,
                   int exitHealth, int exitArmor) {
        // Coarse bands avoid trading large amounts of time for a single extra health point.
        int compareSafety(Summary other) {
            int c = Integer.compare(Math.min(100, minimumHealth) / 10, Math.min(100, other.minimumHealth) / 10);
            if (c == 0) c = Integer.compare(other.criticalTics / 35, criticalTics / 35);
            if (c == 0) c = Integer.compare((other.healthLost + other.armorLost) / 20, (healthLost + armorLost) / 20);
            if (c == 0) c = Integer.compare(reserves() / 20, other.reserves() / 20);
            return c;
        }
        private int reserves() { return Math.min(100, exitHealth) + Math.min(100, exitArmor); }
        String csv() { return minimumHealth + "," + healthLost + "," + armorLost + "," + criticalTics + "," + exitArmor; }
        String description() {
            return String.format(Locale.ROOT, "minimum_health=%d health_lost=%d armor_lost=%d critical_seconds=%.2f exit_health=%d exit_armor=%d",
                    minimumHealth, healthLost, armorLost, criticalTics / 35.0, exitHealth, exitArmor);
        }
    }
    private int minimum, health, armor, healthLost, armorLost, critical;
    SurvivalMetrics(int health, int armor) { this.minimum = this.health = health; this.armor = armor; }
    void record(int nextHealth, int nextArmor) {
        minimum = Math.min(minimum, nextHealth);
        healthLost += Math.max(0, health - nextHealth);
        armorLost += Math.max(0, armor - nextArmor);
        if (nextHealth < 25) critical++;
        health = nextHealth; armor = nextArmor;
    }
    Summary summary() { return new Summary(minimum, healthLost, armorLost, critical, health, armor); }
}
