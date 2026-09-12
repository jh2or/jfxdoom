package doom.ai;

import java.util.HashSet;
import java.util.Set;
import doom.play.Mobj;

/** Circling familiar ground is not progress; new ground, equipment and combat progress are. */
final class ProgressWatch {
    private final Set<Archive.Location> visited = new HashSet<>();
    private int lastProgress, weapons, keys, progress, health;
    private Mobj enemy;
    private int enemyHealth;
    ProgressWatch(Observation initial, int tic) { lastProgress = tic; observe(initial, tic); }
    void observe(Observation o, int tic) {
        boolean advanced = visited.add(o.cell(64).location()) || (o.weapons() & ~weapons) != 0
                || o.keys() != keys || o.progress() != progress || o.health() > health;
        if (o.enemy() != null && o.enemy() == enemy && o.enemy().health < enemyHealth) advanced = true;
        if (advanced) lastProgress = tic;
        weapons = o.weapons(); keys = o.keys(); progress = o.progress(); health = o.health();
        enemy = o.enemy(); enemyHealth = enemy == null ? 0 : enemy.health;
    }
    boolean stalled(int tic) { return tic - lastProgress >= 1050; }
}
