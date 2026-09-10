package doom.ai;

import java.util.*;

/** Bounded archive with two alternatives per coarse cell and shared, reference-counted prefixes. */
final class Archive {
    record Cell(int x, int y, int facing, int health, int ammo, int weapons, int opening, int keys, int armor, int shells) {
        Cell(int x, int y, int facing, int health, int ammo, int weapons, int opening, int keys) {
            this(x, y, facing, health, ammo, weapons, opening, keys, 0, 0);
        }
        Location location() { return new Location(x, y, keys); }
    }
    record Location(int x, int y, int keys) {}
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
    long discoveries;

    Archive(int capacity, int nodeCapacity, int cellSize) {
        this.capacity = capacity; this.nodeCapacity = nodeCapacity; this.cellSize = cellSize;
    }

    int size() { return size; }
    int nodes() { return references.size(); }
    List<Entry> entries() {
        List<Entry> result = new ArrayList<>(size);
        for (List<Entry> group : cells.values()) result.addAll(group);
        return result;
    }

    int visits(double x, double y, int keys) {
        Location key = new Location((int) Math.floor(x / cellSize), (int) Math.floor(y / cellSize), keys);
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
        while (size > capacity || references.size() > nodeCapacity) {
            Entry worst = null;
            double score = Double.NEGATIVE_INFINITY;
            for (List<Entry> group : cells.values()) for (Entry e : group) {
                // Keep spatial/key coverage before redundant inventory/facing variants.
                double candidate = populations.get(e.cell.location()) * 1e9
                        + e.selected * 100.0 + e.route.tics - 8.0 * e.health;
                if (candidate > score) { score = candidate; worst = e; }
            }
            List<Entry> group = cells.get(worst.cell);
            group.remove(worst); size--; release(worst.route); population(worst, -1);
            if (group.isEmpty()) cells.remove(worst.cell);
        }
    }

    Entry select(LearnerRandom random) {
        if (size == 0 || random.nextDouble() < .05) return null;
        List<Entry> entries = entries();
        double total = 0;
        for (Entry e : entries) total += weight(e) / populations.get(e.cell.location());
        double choice = random.nextDouble() * total;
        for (Entry e : entries) {
            choice -= weight(e) / populations.get(e.cell.location());
            if (choice <= 0) { e.selected++; return e; }
        }
        Entry last = entries.getLast(); last.selected++; return last;
    }

    private double weight(Entry e) {
        return (1.0 + Math.min(e.health, 100) / 50.0)
                / Math.sqrt((1.0 + e.selected) * (1.0 + visits.getOrDefault(e.cell.location(), 0)));
    }
}
