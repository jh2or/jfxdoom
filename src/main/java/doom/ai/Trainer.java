package doom.ai;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Standalone archive exploration followed by verified optimization of successful Doom routes. */
public final class Trainer {
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            System.out.println("Usage: doom.ai.Trainer --wad DOOM.WAD [--map E1M1] [--output training/e1m1] [--resume]\n"
                    + "  --skill 3 --episode-tics 21000 --attempts 1000 --minutes 10 --seed 1\n"
                    + "  --archive-size 12000 --node-limit 250000 --cell-size 128 --target-seconds 120\n"
                    + "  --successes 3  Collect distinct verified demos strictly below --target-seconds.\n"
                    + "  --first-completion  Stop after the first verified success for either strategy.\n"
                    + "  --entrance entrance.lmp  Retry an earned campaign entrance (full E1M1 prefix).\n"
                    + "  --strategy speed|survival  Survival ranks safety before time and ignores the time target.\n"
                    + "  --evaluate route.lmp  Evaluate a frozen route with controlled input perturbations.\n"
                    + "  --seed-demo trial.lmp  Import surviving prefixes of a previous trial.\n"
                    + "  --improve winning.lmp  Begin with a previously successful demo.\n"
                    + "  --analyze winning.lmp  Measure movement/safety and write trace.csv / analysis.txt.\n"
                    + "  --verify winning.lmp   Verify completion instead of training.\n"
                    + "Budgets apply to this invocation. Completed runs are optimized until the target is reached.");
            return;
        }
        TrainingConfig config = TrainingConfig.parse(args);
        if (config.verify() != null) {
            int tics = config.entrance() == null
                    ? DemoVerifier.verify(config.wad(), config.verify(), config.skill(), config.episodeTics(), null, config.level())
                    : CampaignDemo.play(config.wad(), config.verify(), config.skill(), config.level(), true,
                            Entrance.load(config.wad(), config.skill(), config.entrance(), config.level()), null);
            System.out.println("VERIFIED " + config.level() + " completion in " + tics + " tics"); return;
        }
        if (config.evaluate() != null) { StrategyEvaluation.run(config); return; }
        if (config.analyze() != null) { analyze(config); return; }
        if (config.resume() && config.improve() != null) throw new IllegalArgumentException("Use --improve only when starting a new experiment");
        Files.createDirectories(config.output());
        try (FileChannel channel = FileChannel.open(config.output().resolve("training.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another trainer owns this output directory");
            train(config);
        }
    }

    private static void analyze(TrainingConfig c) throws IOException {
        Episode e = new Episode(c.wad(), c.skill(), c.episodeTics(), c.level());
        List<Action> actions = DemoInput.read(c.analyze(), c.skill(), c.episodeTics(), c.level());
        RunTrace run = RunTrace.play(e, actions, () -> false);
        Files.createDirectories(c.output());
        String summary = "outcome=" + run.outcome() + " " + run.metrics().description() + "\n" + run.safety().description() + "\n";
        Route.atomicWrite(c.output().resolve("analysis.txt"), summary.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder trace = new StringBuilder("tic,x,y,z,angle,health,forward,side,turn,buttons,displacement,keys,used_line,armor,armor_type,weapons,ready_weapon,bullets,shells,cells,rockets\n");
        RunTrace.Sample previous = run.samples().getFirst();
        for (RunTrace.Sample p : run.samples()) {
            Action action = p.tic() == 0 ? Action.IDLE : actions.get(p.tic() - 1);
            trace.append(String.format(Locale.ROOT, "%d,%.3f,%.3f,%.3f,%.6f,%d,%d,%d,%d,%d,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                    p.tic(), p.x(), p.y(), p.z(), p.angle(), p.health(), action.forward(), action.side(), action.turn(), action.buttons(),
                    Math.hypot(p.x() - previous.x(), p.y() - previous.y()), p.keys(), p.usedLine(), p.inventory().armor(), p.inventory().armorType(),
                    p.weapons(), p.inventory().ready(), p.inventory().bullets(), p.inventory().shells(), p.inventory().cells(), p.inventory().rockets()));
            previous = p;
        }
        Route.atomicWrite(c.output().resolve("trace.csv"), trace.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.print(summary);
    }

    private static void train(TrainingConfig config) throws Exception {
        String identity = Checkpoint.identity(config);
        Path checkpoint = config.output().resolve("archive.bin");
        if (!config.resume() && (Files.exists(checkpoint) || Files.exists(config.output().resolve("attempts.csv")) || Files.exists(config.output().resolve("successes.csv")))) {
            throw new IOException("Output already contains training data; use --resume or another --output directory");
        }
        Checkpoint.Progress progress = config.resume() ? Checkpoint.load(checkpoint, identity, config) : new Checkpoint.Progress(config);
        Entrance entrance = config.entrance() == null ? null : Entrance.load(config.wad(), config.skill(), config.entrance(), config.level());
        Episode episode = new Episode(config.wad(), config.skill(), config.episodeTics(), config.level(), entrance);
        if (entrance != null) Route.atomicWrite(config.output().resolve("entrance.txt"), entrance.description().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (config.seedDemo() != null) {
            importTrial(episode, config, progress);
            Route.atomicWrite(config.output().resolve("seed.lmp"), Files.readAllBytes(config.seedDemo()));
        }
        SuccessPool pool = config.successCount() == 0 ? null : new SuccessPool(config);
        RunTrace best = null;
        if (progress.best != null) best = RunTrace.play(episode, RunTrace.actions(progress.best), () -> false);
        else if (config.improve() != null) best = RunTrace.play(episode,
                DemoInput.read(config.improve(), config.skill(), config.episodeTics(), config.level()), () -> false);
        Route.atomicWrite(config.output().resolve("manifest.txt"), (identity + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (best != null) {
            if (best.outcome() != Episode.Outcome.COMPLETED || !config.acceptsCurrentExit()) throw new IOException("Incumbent did not complete " + config.level());
            installBest(config, identity, best, progress.attempts, episode);
            progress.best = best.route(); Checkpoint.save(checkpoint, identity, progress);
            if (pool != null) pool.offer(episode, best, progress.attempts);
            if (finished(config, best, pool)) { System.out.println("Target already reached and verified"); return; }
        }
        Explorer explorer = new Explorer(progress.random, true, config.strategy());
        RouteOptimizer optimizer = new RouteOptimizer(progress.random, config.strategy());
        AtomicBoolean stopping = new AtomicBoolean();
        Thread worker = Thread.currentThread();
        Thread hook = new Thread(() -> {
            stopping.set(true);
            try { worker.join(10000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "training-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        long started = System.nanoTime(), deadline = started + (long) (config.minutes() * 60e9);
        AtomicLong heartbeat = new AtomicLong(started);
        BooleanSupplier stop = () -> { heartbeat.set(System.nanoTime()); return stopping.get() || System.nanoTime() >= deadline; };
        Thread watchdog = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                if (System.nanoTime() - heartbeat.get() > 60_000_000_000L) {
                    System.err.println("Training stalled for 60 seconds. Last completed checkpoint remains resumable.");
                    for (StackTraceElement frame : worker.getStackTrace()) System.err.println("  at " + frame);
                    System.exit(2);
                }
            }
        }, "training-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        long startingTics = progress.simulatedTics, lastReport = started;
        Path log = config.output().resolve("attempts.csv");
        int optimizationCycle = config.strategy() == LearningStrategy.SURVIVAL ? 2 : 8;
        boolean emptyLog = !Files.exists(log) || Files.size(log) == 0;
        try (BufferedWriter csv = Files.newBufferedWriter(log, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (emptyLog) csv.write("attempt,phase,outcome,prefix_tics,health,kills,archive_entries,route_nodes,discoveries,seconds,tics,movement_tics,blocked_tics,use_tics,turning_tics,idle_tics,revisits,distance,keys,minimum_health,health_lost,armor_lost,critical_tics,exit_armor\n");
            for (int attempt = 0; attempt < config.attempts() && !stop.getAsBoolean(); attempt++) {
                boolean optimize = best != null && (progress.archive.size() == 0 || progress.attempts % optimizationCycle != optimizationCycle - 1);
                Archive.Entry selected = null;
                Route route; int prefixTics = 0; RunTrace proposal = null;
                if (optimize) {
                    proposal = optimizer.propose(episode, best, progress.optimizations++, stop);
                    route = proposal.route();
                } else {
                    selected = progress.archive.select(progress.random);
                    route = selected == null ? null : selected.route;
                    if (config.strategy() == LearningStrategy.SPEED && route != null && route.tics > 700 && progress.attempts % 4 == 0)
                        route = PrefixOptimizer.shorten(episode, route, optimizer, progress.attempts / 4, stop);
                    prefixTics = route == null ? 0 : route.tics;
                    episode.reset(); explorer.restart();
                    for (Route node : Route.steps(route)) {
                        for (int i = 0; i < node.duration && !stop.getAsBoolean(); i++) {
                            episode.step(node.action);
                            if (episode.tics() % 16 == 0) explorer.observe(Observation.read(episode, config.strategy()), episode.tics());
                        }
                        if (stop.getAsBoolean()) break;
                        if (Fingerprint.capture() != node.fingerprint) throw new IllegalStateException("Archive replay diverged at tic " + episode.tics());
                    }
                    explorer.rememberInteractions(episode.interactions());
                    ProgressWatch watch = new ProgressWatch(Observation.read(episode, config.strategy()), episode.tics());
                    while (!stop.getAsBoolean() && episode.outcome() == Episode.Outcome.RUNNING
                            && (config.strategy() != LearningStrategy.SURVIVAL || !watch.stalled(episode.tics()))
                            && (config.strategy() == LearningStrategy.SURVIVAL || best == null || episode.tics() < best.route().tics - 1)) {
                        Observation before = Observation.read(episode, config.strategy());
                        Explorer.Burst burst = explorer.choose(before, progress.archive, episode.tics());
                        int count = 0;
                        while (count < burst.duration() && episode.outcome() == Episode.Outcome.RUNNING && !stop.getAsBoolean()) {
                            episode.step(burst.action()); count++;
                        }
                        if (count > 0) route = new Route(route, burst.action(), count, Fingerprint.capture());
                        explorer.rememberInteractions(episode.interactions());
                        Observation after = Observation.read(episode, config.strategy());
                        watch.observe(after, episode.tics());
                        progress.archive.recordDanger(before.cell(config.cellSize()), before.health() - after.health() + Math.max(0, before.inventory().armor() - after.inventory().armor()), episode.outcome() == Episode.Outcome.DEAD);
                        if (episode.outcome() == Episode.Outcome.RUNNING && !stop.getAsBoolean()) {
                            Archive.Cell cell = after.cell(config.cellSize());
                            progress.archive.visit(cell); progress.archive.offer(cell, route, after.health());
                        }
                    }
                }
                if (selected != null && episode.outcome() == Episode.Outcome.DEAD)
                    progress.archive.recordDanger(selected.cell, 0, true);
                progress.simulatedTics += episode.tics(); progress.attempts++;
                boolean interrupted = stop.getAsBoolean() && episode.outcome() == Episode.Outcome.RUNNING;
                String outcome = interrupted ? "INTERRUPTED" : episode.outcome() == Episode.Outcome.RUNNING ? (config.strategy() == LearningStrategy.SURVIVAL ? "STALLED" : "PRUNED") : episode.outcome().name();
                if (interrupted) progress.interrupted++;
                else if (episode.outcome() == Episode.Outcome.RUNNING) progress.pruned++;
                if (episode.outcome() == Episode.Outcome.DEAD) progress.deaths++;
                if (episode.outcome() == Episode.Outcome.TIMEOUT) progress.timeouts++;
                if (episode.outcome() == Episode.Outcome.COMPLETED) progress.successes++;
                csv.write(String.format(Locale.ROOT, "%d,%s,%s,%d,%d,%d,%d,%d,%d,%.3f,%s,%d,%s%n", progress.attempts,
                        optimize ? "OPTIMIZE" : "EXPLORE", outcome, prefixTics, episode.player().health, episode.player().killcount,
                        progress.archive.size(), progress.archive.nodes(), progress.archive.discoveries, (System.nanoTime() - started) / 1e9,
                        episode.metrics().csv(), Observation.keys(episode.player()), episode.safety().csv())); csv.flush();
                if (!interrupted && route != null && (progress.attempts == 1 || progress.attempts % 25 == 0)) {
                    writeDemo(config, episode, config.output().resolve("latest.lmp"), route);
                }
                if (episode.outcome() == Episode.Outcome.COMPLETED) {
                    RunTrace candidate = proposal != null ? proposal : RunTrace.play(episode, RunTrace.actions(route), () -> false);
                    if (pool != null) pool.offer(episode, candidate, progress.attempts);
                    if (config.acceptsCurrentExit() && config.strategy().better(candidate, best)) {
                        installBest(config, identity, candidate, progress.attempts, episode);
                        best = candidate; progress.best = candidate.route();
                        Checkpoint.save(checkpoint, identity, progress);
                    }
                    if (finished(config, best, pool)) break;
                }
                if (progress.attempts % 10 == 0) Checkpoint.save(checkpoint, identity, progress);
                long now = System.nanoTime();
                if (now - lastReport > 10_000_000_000L) {
                    System.out.printf("attempts=%d best_seconds=%.2f entries=%d locations=%d rate=%.0f tics/s%n", progress.attempts,
                            best == null ? -1 : best.route().tics / 35.0, progress.archive.size(), progress.archive.visits.size(),
                            (progress.simulatedTics - startingTics) / ((now - started) / 1e9));
                    lastReport = now;
                }
            }
        } finally {
            watchdog.interrupt();
            try { Checkpoint.save(checkpoint, identity, progress); }
            finally { try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { /* JVM shutdown */ } }
        }
        System.out.printf("Saved %s: attempts=%d successes=%d deaths=%d timeouts=%d pruned=%d interrupted=%d best_seconds=%.2f%n",
                checkpoint, progress.attempts, progress.successes, progress.deaths, progress.timeouts, progress.pruned, progress.interrupted,
                best == null ? -1 : best.route().tics / 35.0);
    }

    /** Re-simulate every imported command; never carry snapshots or dead suffixes between builds. */
    static void importTrial(Episode episode, TrainingConfig config, Checkpoint.Progress progress) throws IOException {
        List<Action> actions = DemoInput.read(config.seedDemo(), config.skill(), config.episodeTics(), config.level());
        episode.reset(); Route route = null;
        for (int i = 0; i < actions.size() && episode.outcome() == Episode.Outcome.RUNNING;) {
            Action action = actions.get(i); int count = 0;
            do { episode.step(action); i++; count++; }
            while (i < actions.size() && count < 4 && actions.get(i).equals(action) && episode.outcome() == Episode.Outcome.RUNNING);
            route = new Route(route, action, count, Fingerprint.capture());
            if (episode.outcome() == Episode.Outcome.RUNNING) {
                Observation o = Observation.read(episode, config.strategy());
                progress.archive.offer(o.cell(config.cellSize()), route, o.health());
            }
        }
        if (episode.outcome() == Episode.Outcome.COMPLETED) progress.best = route;
        System.out.printf("Imported %d simulated tics and %d surviving cells; outcome=%s%n",
                episode.tics(), progress.archive.size(), episode.outcome());
    }

    private static boolean finished(TrainingConfig config, RunTrace best, SuccessPool pool) {
        return pool != null ? pool.full() : best != null && (config.firstCompletion()
                || (config.strategy() == LearningStrategy.SPEED && best.route().tics <= config.targetTics()));
    }

    static void writeDemo(TrainingConfig config, Episode episode, Path path, Route route) throws IOException {
        if (episode.entrance() == null) Route.demo(path, config.skill(), route, config.level());
        else episode.entrance().demo(path, config.skill(), route);
    }

    static int verifyRun(TrainingConfig config, Episode verification, RunTrace run, Path candidate) throws IOException {
        if (run.outcome() != Episode.Outcome.COMPLETED) throw new IOException("Candidate did not complete " + config.level());
        Route.replay(verification, run.route());
        if (verification.outcome() != Episode.Outcome.COMPLETED) throw new IOException("Candidate did not repeat");
        writeDemo(config, verification, candidate, run.route());
        int tics = verification.entrance() == null
                ? DemoVerifier.verify(config.wad(), candidate, config.skill(), config.episodeTics(), run.route(), config.level())
                : CampaignDemo.play(config.wad(), candidate, config.skill(), config.level(), true, verification.entrance(), run.route());
        return tics;
    }

    private static void installBest(TrainingConfig config, String identity, RunTrace run, long attempt, Episode verification) throws IOException {
        Path candidate = config.output().resolve("cand.lmp");
        int tics = verifyRun(config, verification, run, candidate);
        writeDemo(config, verification, config.output().resolve("winning.lmp"), run.route());
        Files.deleteIfExists(candidate);
        String result = "VERIFIED " + config.level() + " completion: attempt=" + attempt + ", tics=" + tics + ", gameSeconds=" + tics / 35.0
                + ", targetReached=" + (tics <= config.targetTics()) + "\n" + run.metrics().description() + "\n" + run.safety().description() + "\n";
        Route.atomicWrite(config.output().resolve("result.txt"), (result + identity + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.print(result);
    }
}
