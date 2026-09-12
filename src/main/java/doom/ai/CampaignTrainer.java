package doom.ai;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** A tree of earned entrances: every parent must produce its own quota of fast successes. */
public final class CampaignTrainer {
    record Node(String id, Level level, Path entrance, Set<Level> visited) { }
    record Options(TrainingConfig training, Level from, Level through, int branches, int workers, int rounds) { }

    static Options parse(String[] args) {
        List<String> remaining = new ArrayList<>(); Map<String, String> special = new HashMap<>();
        Set<String> keys = Set.of("--from", "--through", "--branches", "--workers", "--rounds");
        for (int i = 0; i < args.length; i++) {
            if (keys.contains(args[i])) {
                String key = args[i];
                if (++i == args.length || special.putIfAbsent(key, args[i]) != null)
                    throw new IllegalArgumentException("Expected one value for " + key);
            } else remaining.add(args[i]);
        }
        Level from = Level.parse(special.getOrDefault("--from", "E1M1"));
        Level through = Level.parse(special.getOrDefault("--through", "E1M2"));
        if (!remaining.contains("--output")) remaining.addAll(List.of("--output", "training/campaign"));
        TrainingConfig c = TrainingConfig.parse(remaining.toArray(String[]::new));
        int branches = Integer.parseInt(special.getOrDefault("--branches", "3"));
        int workers = Integer.parseInt(special.getOrDefault("--workers", "1"));
        int rounds = Integer.parseInt(special.getOrDefault("--rounds", "1"));
        if (branches < 1 || branches > 32 || workers < 1 || workers > 16 || rounds < 1 || rounds > 10000)
            throw new IllegalArgumentException("branches=1..32, workers=1..16, rounds=1..10000");
        if (from.episode() != through.episode() || remaining.contains("--map") || c.entrance() != null
                || c.seedDemo() != null || c.improve() != null || c.analyze() != null || c.evaluate() != null
                || c.firstCompletion() || c.successCount() != 0)
            throw new IllegalArgumentException("Use --from/--through in the same episode; campaign builds its own entrances and success quotas");
        return new Options(c, from, through, branches, workers, rounds);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            System.out.println("Usage: doom.ai.CampaignTrainer --wad DOOM.WAD --from E1M1 --through E1M3\n"
                    + "  --branches 3 --strategy speed --workers 3 --output training/campaign [--resume]\n"
                    + "  --attempts 1000 --minutes 10 --rounds 1 --seed 1\n"
                    + "Each parent collects distinct verified successes strictly below 2xPAR, then ALL successes branch.\n"
                    + "Attempts/minutes apply per node per round. --rounds repeats unfinished searches, retaining learning.\n"
                    + "  --verify campaign.lmp  Verify continuous playback through --through (does not enforce a par target).");
            return;
        }
        Options options = parse(args); TrainingConfig c = options.training();
        if (c.verify() != null) {
            if (!DemoInput.startLevel(c.verify()).equals(options.from())) throw new IOException("Unexpected demo start map");
            int tics = CampaignDemo.play(c.wad(), c.verify(), c.skill(), options.through(), true, null, null);
            System.out.println("VERIFIED continuous campaign through " + options.through() + "; final map tics=" + tics); return;
        }
        Files.createDirectories(c.output());
        try (FileChannel channel = FileChannel.open(c.output().resolve("campaign.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another campaign owns this output directory");
            run(options);
        }
    }

    private static void run(Options o) throws Exception {
        TrainingConfig c = o.training(); Path root = c.output(), manifest = root.resolve("campaign-manifest.txt");
        String identity = "campaign-tree-v1;from=" + o.from() + ";through=" + o.through() + ";branches=" + o.branches()
                + ";strict-par-multiplier=2\n" + Checkpoint.identity(c) + "\n";
        if (Files.exists(manifest)) {
            if (!c.resume()) throw new IOException("Campaign output exists; use --resume or another --output");
            if (!Files.readString(manifest).equals(identity)) throw new IOException("Campaign WAD, build or configuration changed");
        } else {
            if (c.resume()) throw new IOException("No campaign to resume");
            write(manifest, identity);
        }
        write(root.resolve("status.txt"), "RUNNING: branching campaign\n");
        List<Node> frontier = List.of(new Node("r", o.from(), null, Set.of()));
        List<String> tree = new ArrayList<>(List.of("id,parent,map,demo,attempt,tics,health,armor,sha256"));
        Set<Process> running = ConcurrentHashMap.newKeySet();
        Thread hook = new Thread(() -> running.forEach(Process::destroy), "campaign-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        ExecutorService executor = Executors.newFixedThreadPool(o.workers());
        int leaves = 0;
        try {
            while (!frontier.isEmpty()) {
                List<Future<?>> jobs = new ArrayList<>();
                for (Node node : frontier) jobs.add(executor.submit(() -> { trainNode(o, node, running); return null; }));
                for (Future<?> job : jobs) job.get();
                List<Node> next = new ArrayList<>(); boolean incomplete = false;
                for (Node node : frontier) {
                    Path out = root.resolve("nodes").resolve(node.id());
                    List<SuccessPool.Entry> successes = SuccessPool.read(out);
                    if (successes.size() < o.branches()) incomplete = true;
                    if (successes.size() > o.branches()) throw new IOException("Success quota exceeded");
                    int index = 0;
                    for (SuccessPool.Entry e : successes) {
                        String id = node.id() + "-" + (++index); Path demo = out.resolve(e.file());
                        if (!SuccessPool.digest(Files.readAllBytes(demo)).equals(e.sha256())) throw new IOException("Changed success demo");
                        int tics = CampaignDemo.play(c.wad(), demo, c.skill(), node.level(), true, null, null);
                        if (tics != e.tics() || tics >= node.level().parSeconds() * 70) throw new IOException("Success violates map time target");
                        tree.add(id + "," + node.id() + "," + node.level() + "," + root.relativize(demo) + "," + e.attempt()
                                + "," + tics + "," + e.health() + "," + e.armor() + "," + e.sha256());
                        if (node.level().equals(o.through())) {
                            Path finalDemo = root.resolve("demos").resolve(String.format(Locale.ROOT, "c%04d.lmp", ++leaves));
                            Route.atomicWrite(finalDemo, Files.readAllBytes(demo));
                            continue;
                        }
                        List<Action> history = new ArrayList<>(DemoInput.read(demo, c.skill(), CampaignDemo.MAX_TICS, o.from()));
                        Level level = CampaignDemo.advance(history);
                        Set<Level> visited = new HashSet<>(node.visited()); visited.add(node.level());
                        if (visited.contains(level)) throw new IOException("Campaign routing loop at " + level);
                        Path entry = root.resolve("nodes").resolve(id).resolve("entrance.lmp");
                        if (Files.exists(entry)) {
                            if (!DemoInput.read(entry, c.skill(), CampaignDemo.MAX_TICS, o.from()).equals(history))
                                throw new IOException("Saved entrance differs from parent success");
                        } else Route.demoActions(entry, c.skill(), history, o.from());
                        next.add(new Node(id, level, entry, Set.copyOf(visited)));
                    }
                }
                write(root.resolve("tree.csv"), String.join("\n", tree) + "\n");
                if (incomplete) {
                    write(root.resolve("status.txt"), "INCOMPLETE: a parent has fewer than " + o.branches()
                            + " qualifying successes; use --resume (or more --rounds)\n");
                    return;
                }
                frontier = next;
            }
            write(root.resolve("status.txt"), "VERIFIED complete campaign tree " + o.from() + " through " + o.through()
                    + "; branches=" + o.branches() + "; final_demos=" + leaves + "; every map strictly below 2xPAR\n");
            System.out.print(Files.readString(root.resolve("status.txt")));
        } catch (Exception e) {
            write(root.resolve("status.txt"), "ERROR: " + e + "\n"); throw e;
        } finally {
            executor.shutdownNow(); running.forEach(Process::destroy);
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { }
        }
    }

    private static void trainNode(Options o, Node node, Set<Process> running) throws Exception {
        TrainingConfig c = o.training(); Path out = c.output().resolve("nodes").resolve(node.id());
        Files.createDirectories(out);
        for (int round = 1; round <= o.rounds(); round++) {
            if (SuccessPool.read(out).size() >= o.branches()) return;
            List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-XX:ActiveProcessorCount=1", "-Xmx768m", "-cp", childClasspath(), Trainer.class.getName(),
                    "--wad", c.wad().toString(), "--map", node.level().toString(), "--output", out.toString(),
                    "--skill", "" + c.skill(), "--episode-tics", "" + c.episodeTics(), "--attempts", "" + c.attempts(),
                    "--minutes", "" + c.minutes(), "--seed", "" + c.seed(), "--archive-size", "" + c.archiveSize(),
                    "--node-limit", "" + c.nodeLimit(), "--cell-size", "" + c.cellSize(),
                    "--strategy", c.strategy().name().toLowerCase(Locale.ROOT), "--successes", "" + o.branches(),
                    "--target-seconds", "" + node.level().parSeconds() * 2));
            if (node.entrance() != null) command.addAll(List.of("--entrance", node.entrance().toString()));
            if (Files.exists(out.resolve("archive.bin"))) command.add("--resume");
            System.out.println("BRANCH " + node.id() + " " + node.level() + " round=" + round + " qualified=" + SuccessPool.read(out).size());
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(out.resolve("trainer.log").toFile())).start();
            running.add(process);
            try {
                int exit = process.waitFor();
                if (exit != 0) throw new IOException("Trainer failed for " + node.id() + " (exit " + exit + "); see " + out.resolve("trainer.log"));
            } finally { running.remove(process); if (process.isAlive()) process.destroy(); }
        }
    }

    /** Gradle test workers may keep runtime jars in a child URL classloader. */
    private static String childClasspath() throws Exception {
        Set<String> paths = new LinkedHashSet<>();
        paths.add(Path.of(Trainer.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        for (ClassLoader loader = Trainer.class.getClassLoader(); loader != null; loader = loader.getParent())
            if (loader instanceof URLClassLoader urls) for (var url : urls.getURLs()) paths.add(Path.of(url.toURI()).toString());
        paths.addAll(Arrays.asList(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
        return String.join(java.io.File.pathSeparator, paths);
    }
    private static void write(Path path, String text) throws IOException { Route.atomicWrite(path, text.getBytes(StandardCharsets.UTF_8)); }
}
