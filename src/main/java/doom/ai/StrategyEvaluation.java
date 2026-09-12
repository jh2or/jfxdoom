package doom.ai;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Matched input perturbations of one frozen route; no archive updates or early pruning. */
final class StrategyEvaluation {
    static List<Action> perturbation(long seed, int trial) {
        LearnerRandom random = new LearnerRandom(seed);
        // Up to 128 unique cases, deterministically permuted by seed. Inputs remain ordinary demo commands.
        int offset = random.nextInt(128);
        int scenario = Math.floorMod(trial + offset, 128);
        List<Action> actions = new ArrayList<>();
        actions.add(new Action(0, 0, (scenario % 8 - 4) * 256, 0));
        for (int i = 0; i < scenario / 8; i++) actions.add(Action.IDLE);
        return actions;
    }

    static void run(TrainingConfig config) throws Exception {
        if (config.attempts() > 128) throw new IllegalArgumentException("Evaluation supports 1..128 distinct perturbations; choose --attempts <= 128");
        Files.createDirectories(config.output());
        Path output = config.output().resolve("evaluation.csv");
        if (Files.exists(output)) throw new IOException("Evaluation output already exists; choose another directory");
        Episode episode = new Episode(config.wad(), config.skill(), config.episodeTics(), config.level());
        RunTrace reference = RunTrace.play(episode, DemoInput.read(config.evaluate(), config.skill(), config.episodeTics(), config.level()), () -> false);
        if (reference.outcome() != Episode.Outcome.COMPLETED) throw new IOException("Evaluation requires a completed reference route");
        String identity = Checkpoint.identity(config);
        Route.atomicWrite(config.output().resolve("manifest.txt"), (identity + "\nreference=" + config.evaluate().toAbsolutePath()
                + "\nreference-sha256=" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(config.evaluate()))))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        RouteOptimizer optimizer = new RouteOptimizer(new LearnerRandom(config.seed()), config.strategy());
        long deadline = System.nanoTime() + (long) (config.minutes() * 60e9);
        int completed = 0, deaths = 0, timeouts = 0, interrupted = 0;
        RunTrace best = null;
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW)) {
            writer.write("trial,strategy,outcome,initial_delay_tics,initial_turn,tics,game_seconds,health,kills,minimum_health,health_lost,armor_lost,critical_tics,exit_armor\n");
            for (int trial = 0; trial < config.attempts(); trial++) {
                List<Action> perturbation = perturbation(config.seed(), trial);
                RunTrace run = optimizer.evaluate(episode, reference, perturbation, () -> System.nanoTime() >= deadline);
                String outcome = run.outcome() == Episode.Outcome.RUNNING ? "INTERRUPTED" : run.outcome().name();
                if (run.outcome() == Episode.Outcome.COMPLETED) completed++;
                else if (run.outcome() == Episode.Outcome.DEAD) deaths++;
                else if (run.outcome() == Episode.Outcome.TIMEOUT) timeouts++;
                else interrupted++;
                writer.write(String.format(Locale.ROOT, "%d,%s,%s,%d,%d,%d,%.6f,%d,%d,%s%n", trial + 1,
                        config.strategy(), outcome, perturbation.size() - 1, perturbation.getFirst().turn(), episode.tics(), episode.tics() / 35.0,
                        episode.player().health, episode.player().killcount, episode.safety().csv()));
                writer.flush();
                if (config.strategy().better(run, best)) {
                    best = run;
                    Route.demo(config.output().resolve("winning.lmp"), config.skill(), run.route(), config.level());
                }
                if (run.outcome() == Episode.Outcome.RUNNING) break;
            }
        }
        if (best != null) DemoVerifier.verify(config.wad(), config.output().resolve("winning.lmp"), config.skill(), config.episodeTics(), best.route(), config.level());
        String result = String.format(Locale.ROOT, "strategy=%s completed=%d deaths=%d timeouts=%d interrupted=%d%n", config.strategy(), completed, deaths, timeouts, interrupted);
        Files.writeString(config.output().resolve("evaluation.txt"), result + (best == null ? "" : best.metrics().description() + "\n" + best.safety().description() + "\n"));
        System.out.print(result);
    }
}
