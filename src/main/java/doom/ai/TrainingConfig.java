package doom.ai;

import java.nio.file.Path;
import java.util.*;

public record TrainingConfig(Path wad, Path output, int skill, int episodeTics, int attempts,
                             double minutes, long seed, int archiveSize, int nodeLimit, int cellSize,
                             boolean resume, Path verify, Path improve, Path analyze, int targetTics, Level level, Path seedDemo, LearningStrategy strategy, Path evaluate, Path entrance, boolean firstCompletion, int successCount) {
    public TrainingConfig {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(strategy, "strategy");
        if (successCount < 0 || successCount > 32 || (successCount > 0 && firstCompletion))
            throw new IllegalArgumentException("--successes must be 1..32 and cannot combine with --first-completion");
        int modes = (verify == null ? 0 : 1) + (improve == null ? 0 : 1) + (analyze == null ? 0 : 1) + (seedDemo == null ? 0 : 1) + (evaluate == null ? 0 : 1);
        if (entrance != null && (improve != null || analyze != null || seedDemo != null || evaluate != null))
            throw new IllegalArgumentException("--entrance supports training, resume, and verification only");
        if (modes > 1 || (resume && modes > 0)) throw new IllegalArgumentException("Choose training, --seed-demo, --improve, --analyze, --evaluate, or --verify; resume uses its saved incumbent");
        if (skill < 1 || skill > 5 || episodeTics < 1 || episodeTics > 21000 || attempts < 1
                || !Double.isFinite(minutes) || minutes <= 0 || minutes > 10080
                || archiveSize < 2 || archiveSize > 100000 || nodeLimit < 32 || nodeLimit > 2000000
                || cellSize < 32 || cellSize > 512 || targetTics < 1 || targetTics > 21000) throw new IllegalArgumentException("Invalid training limits");
    }

    public TrainingConfig(Path wad, Path output, int skill, int episodeTics, int attempts, double minutes,
                   long seed, int archiveSize, int nodeLimit, int cellSize, boolean resume, Path verify,
                   Path improve, Path analyze, int targetTics, Level level, Path seedDemo, LearningStrategy strategy,
                   Path evaluate, Path entrance, boolean firstCompletion) {
        this(wad, output, skill, episodeTics, attempts, minutes, seed, archiveSize, nodeLimit, cellSize,
                resume, verify, improve, analyze, targetTics, level, seedDemo, strategy, evaluate, entrance, firstCompletion, 0);
    }

    public TrainingConfig(Path wad, Path output, int skill, int episodeTics, int attempts, double minutes,
                   long seed, int archiveSize, int nodeLimit, int cellSize, boolean resume, Path verify,
                   Path improve, Path analyze, int targetTics, Level level, Path seedDemo, LearningStrategy strategy, Path evaluate) {
        this(wad, output, skill, episodeTics, attempts, minutes, seed, archiveSize, nodeLimit, cellSize,
                resume, verify, improve, analyze, targetTics, level, seedDemo, strategy, evaluate, null, false);
    }

    public TrainingConfig(Path wad, Path output, int skill, int episodeTics, int attempts, double minutes,
                   long seed, int archiveSize, int nodeLimit, int cellSize, boolean resume, Path verify,
                   Path improve, Path analyze, int targetTics, Level level, Path seedDemo) {
        this(wad, output, skill, episodeTics, attempts, minutes, seed, archiveSize, nodeLimit, cellSize,
                resume, verify, improve, analyze, targetTics, level, seedDemo, LearningStrategy.SPEED, null);
    }

    static TrainingConfig parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        Set<String> allowed = Set.of("map", "wad", "output", "skill", "episode-tics", "attempts", "minutes",
                "seed", "archive-size", "node-limit", "cell-size", "resume", "verify", "improve", "analyze", "seed-demo", "target-seconds", "strategy", "evaluate", "entrance", "first-completion", "successes");
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Expected --option: " + args[i]);
            String key = args[i].substring(2);
            if (!allowed.contains(key) || options.containsKey(key)) throw new IllegalArgumentException("Unknown/duplicate option: " + key);
            if (key.equals("resume") || key.equals("first-completion")) options.put(key, "true");
            else {
                if (++i >= args.length) throw new IllegalArgumentException("Missing value: " + key);
                options.put(key, args[i]);
            }
        }
        if (!options.containsKey("wad")) throw new IllegalArgumentException("--wad is required");
        Level level = Level.parse(options.getOrDefault("map", "E1M1"));
        return new TrainingConfig(Path.of(options.get("wad")), Path.of(options.getOrDefault("output", "training/" + level.toString().toLowerCase(Locale.ROOT))),
                Integer.parseInt(options.getOrDefault("skill", "3")), Integer.parseInt(options.getOrDefault("episode-tics", "21000")),
                Integer.parseInt(options.getOrDefault("attempts", options.containsKey("evaluate") ? "64" : "1000")), Double.parseDouble(options.getOrDefault("minutes", "10")),
                Long.parseLong(options.getOrDefault("seed", "1")), Integer.parseInt(options.getOrDefault("archive-size", "12000")),
                Integer.parseInt(options.getOrDefault("node-limit", "250000")), Integer.parseInt(options.getOrDefault("cell-size", "128")),
                options.containsKey("resume"), options.containsKey("verify") ? Path.of(options.get("verify")) : null,
                options.containsKey("improve") ? Path.of(options.get("improve")) : null,
                options.containsKey("analyze") ? Path.of(options.get("analyze")) : null,
                Math.multiplyExact(Integer.parseInt(options.getOrDefault("target-seconds", "120")), 35), level, options.containsKey("seed-demo") ? Path.of(options.get("seed-demo")) : null,
                LearningStrategy.parse(options.getOrDefault("strategy", "speed")),
                options.containsKey("evaluate") ? Path.of(options.get("evaluate")) : null,
                options.containsKey("entrance") ? Path.of(options.get("entrance")) : null, options.containsKey("first-completion"), Integer.parseInt(options.getOrDefault("successes", "0")));
    }

    String identity() {
        return "archive-explorer-v6;" + level + ";skill=" + skill + ";tics=" + episodeTics + ";seed=" + seed
                + ";cells=" + cellSize + ";entries=" + archiveSize + ";nodes=" + nodeLimit + ";strategy=" + strategy.name().toLowerCase(Locale.ROOT) + ";standard-flags" + (successCount == 0 ? "" : ";successes=" + successCount + ";strict-target=" + targetTics);
    }
}
