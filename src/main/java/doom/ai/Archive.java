package doom.ai;

import java.util.*;

/** Bounded archive with two alternatives per coarse cell and shared, reference-counted prefixes. */
final class Archive {
    record Cell(int x, int y, int facing, int health, int ammo, int weapons, int opening, int keys, int armor, int shells, int progress) {
        Cell(int x, int y, int facing, int health, int ammo, int weapons, int opening, int keys, int armor, int shells) {
            this(x, y, facing, health, ammo, weapons, opening, keys, armor, shells, 0);
        }
        Cell(int x, int y, int facing, int health, int ammo, int weapons, int opening, int keys) {
            this(x, y, facing, health, ammo, weapons, opening, keys, 0, 0);
        }
        Location location() { return new Location(x, y, keys, progress); }
    }
    record Location(int x, int y, int keys, int progress) {
        Location(int x, int y, int keys) { this(x, y, keys, 0); }
    }
    static final class Entry {
        final Cell cell;
        final Route route;
        final int health;
        final long discovered;
        int selected;
        Entry(Cell cell, Route route, int health, long discovered) {
            this.cell = cell; this.route = route; this.health = health; this.discovered = discovered;
        }
    }
    final int capacity, nodeCapacity, cellSize;
    final LinkedHashMap<Cell, List<Entry>> cells = new LinkedHashMap<>();
    final LinkedHashMap<Location, Integer> visits = new LinkedHashMap<>();
    private final IdentityHashMap<Route, Integer> references = new IdentityHashMap<>();
    private final Map<Location, Integer> populations = new HashMap<>();
    private int size;
    private final LearningStrategy strategy;
    final LinkedHashMap<Location, Integer> danger = new LinkedHashMap<>();
    long discoveries;

    Archive(int capacity, int nodeCapacity, int cellSize) { this(capacity, nodeCapacity, cellSize, LearningStrategy.SPEED); }
    Archive(int capacity, int nodeCapacity, int cellSize, LearningStrategy strategy) {
        this.strategy = strategy;
        this.capacity = capacity; this.nodeCapacity = nodeCapacity; this.cellSize = cellSize;
    }

    int size() { return size; }
    int nodes() { return references.size(); }
    List<Entry> entries() {
        List<Entry> result = new ArrayList<>(size);
        for (List<Entry> group : cells.values()) result.addAll(group);
        return result;
    }

    int visits(double x, double y, int keys) { return visits(x, y, keys, 0); }
    int visits(double x, double y, int keys, int progress) {
        Location key = new Location((int) Math.floor(x / cellSize), (int) Math.floor(y / cellSize), keys, progress);
        return visits.getOrDefault(key, 0);
    }

    void visit(Cell cell) {
        Location key = cell.location();
        visits.compute(key, (k, old) -> old == null ? 1 : Math.min(1000000, old + 1));
        if (visits.size() > capacity * 4) visits.remove(visits.keySet().iterator().next());
    }

    boolean offer(Cell cell, Route route, int health) {
        if (route == null || health <= 0) return false;
        List<Entry> group = cells.computeIfAbsent(cell, k -> new ArrayList<>(2));
        for (Entry old : group) {
            if (old.route.tics <= route.tics && old.health >= health) return false;
        }
        for (Iterator<Entry> it = group.iterator(); it.hasNext();) {
            Entry old = it.next();
            if (route.tics <= old.route.tics && health >= old.health) { it.remove(); release(old.route); size--; population(old, -1); }
        }
        Entry next = new Entry(cell, route, health, ++discoveries);
        group.add(next); retain(route); size++; population(next, 1);
        if (group.size() > 2) {
            // Preserve the fastest and healthiest alternative.
            Entry fastest = Collections.min(group, Comparator.comparingInt(e -> e.route.tics));
            Entry healthiest = Collections.max(group, Comparator.comparingInt(e -> e.health));
            Entry remove = group.stream().filter(e -> e != fastest && e != healthiest).findFirst().orElse(next);
            group.remove(remove); release(remove.route); size--; population(remove, -1);
        }
        trim();
        return group.contains(next);
    }

    void restore(Entry entry) {
        cells.computeIfAbsent(entry.cell, k -> new ArrayList<>(2)).add(entry);
        size++; retain(entry.route); population(entry, 1);
    }

    private void retain(Route route) {
        for (Route n = route; n != null; n = n.parent) {
            Integer count = references.put(n, references.getOrDefault(n, 0) + 1);
            if (count != null) break;
        }
    }

    private void release(Route route) {
        for (Route n = route; n != null; n = n.parent) {
            int count = references.get(n);
            if (count > 1) { references.put(n, count - 1); break; }
            references.remove(n);
        }
    }

    private void population(Entry entry, int delta) {
        populations.compute(entry.cell.location(), (key, count) -> {
            int next = (count == null ? 0 : count) + delta;
            return next == 0 ? null : next;
        });
    }

    private void trim() {
        if (size <= capacity && references.size() <= nodeCapacity) return;
        Map<Integer, Integer> phases = new HashMap<>();
        for (List<Entry> group : cells.values()) for (Entry e : group)
            phases.merge(e.cell.keys(), 1, Integer::sum);
        while (size > capacity || references.size() > nodeCapacity) {
            Entry worst = null;
            boolean worstLastPhase = true;
            double score = Double.NEGATIVE_INFINITY;
            for (List<Entry> group : cells.values()) for (Entry e : group) {
                // Keep spatial/key coverage before redundant inventory/facing variants.
                double candidate = populations.get(e.cell.location()) * 1e9
                        + e.selected * 100.0 + e.route.tics - 8.0 * e.health;
                // A long, rare key detour must remain available to the next trial.
                // Prefer removing redundant key states; if all are last representatives,
                // fall back to the ordinary ranking so both hard budgets still hold.
                boolean lastPhase = phases.get(e.cell.keys()) == 1;
                if (worst == null || worstLastPhase && !lastPhase
                        || worstLastPhase == lastPhase && candidate > score) {
                    score = candidate; worst = e; worstLastPhase = lastPhase;
                }
            }
            List<Entry> group = cells.get(worst.cell);
            group.remove(worst); size--; release(worst.route); population(worst, -1);
            phases.compute(worst.cell.keys(), (key, count) -> count - 1);
            if (group.isEmpty()) cells.remove(worst.cell);
        }
    }

    Entry select(LearnerRandom random) {
        if (size == 0 || random.nextDouble() < .05) return null;
        List<Entry> entries = entries();
        Map<Integer, Double> phases = new HashMap<>();
        for (Entry e : entries) phases.merge(e.cell.keys(), weight(e) / populations.get(e.cell.location()), Double::sum);
        double total = 0;
        for (int keys : phases.keySet()) total += Math.scalb(1.0, 2 * Integer.bitCount(keys));
        double choice = random.nextDouble() * total;
        for (Entry e : entries) {
            // First sample inventory progress, then spatial novelty within it. A rare key discovery
            // must not compete directly with hundreds of locations from before that acquisition.
            choice -= weight(e) / populations.get(e.cell.location()) / phases.get(e.cell.keys())
                    * Math.scalb(1.0, 2 * Integer.bitCount(e.cell.keys()));
            if (choice <= 0) { e.selected++; return e; }
        }
        Entry last = entries.getLast(); last.selected++; return last;
    }

    void recordDanger(Cell cell, int loss, boolean died) {
        if (strategy != LearningStrategy.SURVIVAL || (loss <= 0 && !died)) return;
        danger.merge(cell.location(), Math.max(1, loss / 5) + (died ? 20 : 0), (a, b) -> Math.min(200, a + b));
        if (danger.size() > capacity) danger.remove(danger.keySet().iterator().next());
    }

    double weight(Entry e) {
        if (strategy == LearningStrategy.SURVIVAL) {
            double health = Math.max(.1, Math.min(100, e.health) / 100.0);
            double armor = Math.min(100, (e.cell.armor() % 16) * 25) / 100.0;
            return (.1 + 4 * health * health + armor)
                    / ((1 + danger.getOrDefault(e.cell.location(), 0) * .2)
                    * Math.sqrt((1.0 + e.selected) * (1.0 + visits.getOrDefault(e.cell.location(), 0))));
        }
        return (1.0 + Math.min(e.health, 100) / 50.0)
                / Math.sqrt((1.0 + e.selected) * (1.0 + visits.getOrDefault(e.cell.location(), 0)));
    }
}
