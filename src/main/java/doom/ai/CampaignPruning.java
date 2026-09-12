package doom.ai;

import java.util.*;

/** Deterministic health-first selection at campaign continuation boundaries. */
final class CampaignPruning {
    enum Scope { PARENT, FRONTIER }
    record Candidate(String id, String parent, SuccessPool.Entry success) {}
    record Policy(int every, int keep, Scope scope) {
        Policy {
            if (every < 0 || every > 9 || keep < 1 || keep > 32 || scope == null)
                throw new IllegalArgumentException("prune-every=0..9, keep=1..32, scope=parent|frontier");
        }
        boolean boundary(int completedMaps) { return every > 0 && completedMaps % every == 0; }
        Set<String> retain(List<Candidate> candidates, int completedMaps) {
            if (!boundary(completedMaps)) {
                Set<String> all = new LinkedHashSet<>();
                for (Candidate candidate : candidates) all.add(candidate.id());
                return Set.copyOf(all);
            }
            Map<String,List<Candidate>> groups = new LinkedHashMap<>();
            for (Candidate candidate : candidates)
                groups.computeIfAbsent(scope == Scope.PARENT ? candidate.parent() : "all", ignored -> new ArrayList<>()).add(candidate);
            Set<String> selected = new LinkedHashSet<>();
            Comparator<Candidate> order = Comparator.<Candidate>comparingInt(c -> c.success().health()).reversed()
                    .thenComparing(Comparator.<Candidate>comparingInt(c -> c.success().armor()).reversed())
                    .thenComparingInt(c -> c.success().tics()).thenComparing(Candidate::id);
            for (List<Candidate> group : groups.values()) {
                group.sort(order);
                for (Candidate candidate : group.subList(0, Math.min(keep, group.size()))) selected.add(candidate.id());
            }
            return Set.copyOf(selected);
        }
    }
    private CampaignPruning() {}
}
