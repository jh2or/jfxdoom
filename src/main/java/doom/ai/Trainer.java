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
                    + "  --improve winning.lmp  Begin with a previously successful demo.\n"
                    + "  --analyze winning.lmp  Measure movement and write trace.csv / analysis.txt.\n"
                    + "  --verify winning.lmp   Verify completion instead of training.\n"
                    + "Budgets apply to this invocation. Completed runs are optimized until the target is reached.");
            return;
        }
        TrainingConfig config = TrainingConfig.parse(args);
        if (config.verify() != null) {
            int tics = DemoVerifier.verify(config.wad(), config.verify(), config.skill(), config.episodeTics(), null, config.level());
            System.out.println("VERIFIED " + config.level() + " completion in " + tics + " tics"); return;
        }
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
        String summary = "outcome=" + run.outcome() + " " + run.metrics().description() + "\n";
        Route.atomicWrite(c.output().resolve("analysis.txt"), summary.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder trace = new StringBuilder("tic,x,y,z,angle,health,forward,side,turn,buttons,displacement,keys,used_line,armor,armor_type,weapons,ready_weapon,bullets,shells,cells\n");
        RunTrace.Sample previous = run.samples().getFirst();
        for (RunTrace.Sample p : run.samples()) {
            Action action = p.tic() == 0 ? Action.IDLE : actions.get(p.tic() - 1);
            trace.append(String.format(Locale.ROOT, "%d,%.3f,%.3f,%.3f,%.6f,%d,%d,%d,%d,%d,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                    p.tic(), p.x(), p.y(), p.z(), p.angle(), p.health(), action.forward(), action.side(), action.turn(), action.buttons(),
                    Math.hypot(p.x() - previous.x(), p.y() - previous.y()), p.keys(), p.usedLine(), p.inventory().armor(), p.inventory().armorType(),
                    p.weapons(), p.inventory().ready(), p.inventory().bullets(), p.inventory().shells(), p.inventory().cells()));
            previous = p;
        }
        Route.atomicWrite(c.output().resolve("trace.csv"), trace.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.print(summary);
    }

    private static void train(TrainingConfig config) throws Exception {
        String identity = Checkpoint.identity(config);
        Path checkpoint = config.output().resolve("archive.bin");
        if (!config.resume() && (Files.exists(checkpoint) || Files.exists(config.output().resolve("attempts.csv")))) {
            throw new IOException("Output already contains training data; use --resume or another --output directory");
        }
        Checkpoint.Progress progress = config.resume() ? Checkpoint.load(checkpoint, identity, config) : new Checkpoint.Progress(config);
        Episode episode = new Episode(config.wad(), config.skill(), config.episodeTics(), config.level());
        RunTrace best = null;
        if (progress.best != null) best = RunTrace.play(episode, RunTrace.actions(progress.best), () -> false);
        else if (config.improve() != null) best = RunTrace.play(episode,
                DemoInput.read(config.improve(), config.skill(), config.episodeTics(), config.level()), () -> false);
        Route.atomicWrite(config.output().resolve("manifest.txt"), (identity + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (best != null) {
            if (best.outcome() != Episode.Outcome.COMPLETED) throw new IOException("Incumbent did not complete " + config.level());
            installBest(config, identity, best, progress.attempts);
            progress.best = best.route(); Checkpoint.save(checkpoint, identity, progress);
            if (best.route().tics <= config.targetTics()) { System.out.println("Target already reached and verified"); return; }
        }
        Explorer explorer = new Explorer(progress.random);
        RouteOptimizer optimizer = new RouteOptimizer(progress.random);
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
        boolean emptyLog = !Files.exists(log) || Files.size(log) == 0;
        try (BufferedWriter csv = Files.newBufferedWriter(log, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (emptyLog) csv.write("attempt,phase,outcome,prefix_tics,health,kills,archive_entries,route_nodes,discoveries,seconds,tics,movement_tics,blocked_tics,use_tics,turning_tics,idle_tics,revisits,distance,keys\n");
            for (int attempt = 0; attempt < config.attempts() && !stop.getAsBoolean(); attempt++) {
                boolean optimize = best != null && (progress.archive.size() == 0 || progress.attempts % 8 != 7);
                Route route; int prefixTics = 0; RunTrace proposal = null;
                if (optimize) {
                    proposal = optimizer.propose(episode, best, progress.optimizations++, stop);
                    route = proposal.route();
                } else {
                    Archive.Entry selected = progress.archive.select(progress.random);
                    route = selected == null ? null : selected.route;
                    prefixTics = route == null ? 0 : route.tics;
                    episode.reset();
                    for (Route node : Route.steps(route)) {
                        for (int i = 0; i < node.duration && !stop.getAsBoolean(); i++) episode.step(node.action);
                        if (stop.getAsBoolean()) break;
                        if (Fingerprint.capture() != node.fingerprint) throw new IllegalStateException("Archive replay diverged at tic " + episode.tics());
                    }
                    explorer.restart();
                    while (!stop.getAsBoolean() && episode.outcome() == Episode.Outcome.RUNNING
                            && (best == null || episode.tics() < best.route().tics - 1)) {
                        Explorer.Burst burst = explorer.choose(Observation.read(episode), progress.archive, episode.tics());
                        int count = 0;
                        while (count < burst.duration() && episode.outcome() == Episode.Outcome.RUNNING && !stop.getAsBoolean()) {
                            episode.step(burst.action()); count++;
                        }
                        if (count > 0) route = new Route(route, burst.action(), count, Fingerprint.capture());
                        Observation after = Observation.read(episode);
                        if (episode.outcome() == Episode.Outcome.RUNNING && !stop.getAsBoolean()) {
                            Archive.Cell cell = after.cell(config.cellSize());
                            progress.archive.visit(cell); progress.archive.offer(cell, route, after.health());
                        }
                    }
                }
                progress.simulatedTics += episode.tics(); progress.attempts++;
                boolean interrupted = stop.getAsBoolean() && episode.outcome() == Episode.Outcome.RUNNING;
                String outcome = interrupted ? "INTERRUPTED" : episode.outcome() == Episode.Outcome.RUNNING ? "PRUNED" : episode.outcome().name();
                if (interrupted) progress.interrupted++;
                else if (episode.outcome() == Episode.Outcome.RUNNING) progress.pruned++;
                if (episode.outcome() == Episode.Outcome.DEAD) progress.deaths++;
                if (episode.outcome() == Episode.Outcome.TIMEOUT) progress.timeouts++;
                if (episode.outcome() == Episode.Outcome.COMPLETED) progress.successes++;
                csv.write(String.format(Locale.ROOT, "%d,%s,%s,%d,%d,%d,%d,%d,%d,%.3f,%s,%d%n", progress.attempts,
                        optimize ? "OPTIMIZE" : "EXPLORE", outcome, prefixTics, episode.player().health, episode.player().killcount,
                        progress.archive.size(), progress.archive.nodes(), progress.archive.discoveries, (System.nanoTime() - started) / 1e9,
                        episode.metrics().csv(), Observation.keys(episode.player()))); csv.flush();
                if (!interrupted && route != null && (progress.attempts == 1 || progress.attempts % 25 == 0)) {
                    Route.demo(config.output().resolve("latest.lmp"), config.skill(), route, config.level());
                }
                if (episode.outcome() == Episode.Outcome.COMPLETED && (best == null || route.tics < best.route().tics)) {
                    RunTrace candidate = proposal != null ? proposal : RunTrace.play(episode, RunTrace.actions(route), () -> false);
                    installBest(config, identity, candidate, progress.attempts);
                    best = candidate; progress.best = candidate.route();
                    Checkpoint.save(checkpoint, identity, progress);
                    if (best.route().tics <= config.targetTics()) break;
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

    private static void installBest(TrainingConfig config, String identity, RunTrace run, long attempt) throws IOException {
        if (run.outcome() != Episode.Outcome.COMPLETED) throw new IOException("Candidate did not complete " + config.level());
        Episode verification = new Episode(config.wad(), config.skill(), config.episodeTics(), config.level());
        Route.replay(verification, run.route());
        if (verification.outcome() != Episode.Outcome.COMPLETED) throw new IOException("Candidate did not repeat");
        Path candidate = config.output().resolve("cand.lmp");
        Route.demo(candidate, config.skill(), run.route(), config.level());
        int tics = DemoVerifier.verify(config.wad(), candidate, config.skill(), config.episodeTics(), run.route(), config.level());
        Route.demo(config.output().resolve("winning.lmp"), config.skill(), run.route(), config.level());
        Files.deleteIfExists(candidate);
        String result = "VERIFIED " + config.level() + " completion: attempt=" + attempt + ", tics=" + tics + ", gameSeconds=" + tics / 35.0
                + ", targetReached=" + (tics <= config.targetTics()) + "\n" + run.metrics().description() + "\n";
        Route.atomicWrite(config.output().resolve("result.txt"), (result + identity + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.print(result);
    }
}
