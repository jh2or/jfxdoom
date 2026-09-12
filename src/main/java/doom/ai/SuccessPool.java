package doom.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Append-only, replay-verified alternatives from one exact level entrance. */
final class SuccessPool {
    record Entry(String file, long attempt, int tics, String sha256, int health, int armor) { }
    private final TrainingConfig config;
    private final List<Entry> entries;

    SuccessPool(TrainingConfig config) throws IOException {
        this.config = config;
        entries = new ArrayList<>(read(config.output()));
        Set<String> hashes = new HashSet<>();
        for (Entry e : entries) {
            if (e.tics() <= 0 || e.health() <= 0 || e.attempt() < 0 || e.tics() >= config.targetTics() || !hashes.add(e.sha256())
                    || !digest(Files.readAllBytes(config.output().resolve(e.file()))).equals(e.sha256()))
                throw new IOException("Invalid or changed success pool");
        }
    }
    boolean full() { return entries.size() >= config.successCount(); }
    int size() { return entries.size(); }

    boolean offer(Episode episode, RunTrace run, long attempt) throws IOException {
        int tics = run.route() == null ? 0 : run.route().tics + (episode.entrance() == null ? 0 : episode.entrance().levelTics());
        if (full() || !config.acceptsCurrentExit() || !qualifies(run.outcome(), run.safety().exitHealth(), tics, config.targetTics())) return false;
        Path candidate = config.output().resolve("poolcand.lmp");
        try {
            Trainer.writeDemo(config, episode, candidate, run.route());
            String hash = digest(Files.readAllBytes(candidate));
            if (entries.stream().anyMatch(e -> e.sha256().equals(hash))) return false;
            int verified = Trainer.verifyRun(config, episode, run, candidate);
            if (verified != tics) throw new IOException("Qualified route time changed on verification");
            String name = String.format(Locale.ROOT, "s%03d.lmp", entries.size() + 1);
            Route.atomicWrite(config.output().resolve(name), Files.readAllBytes(candidate));
            entries.add(new Entry(name, attempt, tics, hash, run.safety().exitHealth(), run.safety().exitArmor()));
            StringBuilder index = new StringBuilder("file,attempt,tics,sha256,health,armor\n");
            for (Entry e : entries) index.append(e.file()).append(',').append(e.attempt()).append(',').append(e.tics())
                    .append(',').append(e.sha256()).append(',').append(e.health()).append(',').append(e.armor()).append('\n');
            Route.atomicWrite(config.output().resolve("successes.csv"), index.toString().getBytes(StandardCharsets.UTF_8));
            System.out.printf("QUALIFIED %d/%d %s attempt=%d seconds=%.2f demo=%s%n", size(), config.successCount(), config.level(), attempt, tics / 35.0, name);
            return true;
        } finally { Files.deleteIfExists(candidate); }
    }

    static boolean qualifies(Episode.Outcome outcome, int health, int tics, int target) {
        return outcome == Episode.Outcome.COMPLETED && health > 0 && tics > 0 && tics < target;
    }
    static List<Entry> read(Path output) throws IOException {
        Path path = output.resolve("successes.csv");
        if (!Files.exists(path)) return List.of();
        List<String> lines = Files.readAllLines(path); List<Entry> entries = new ArrayList<>();
        if (lines.isEmpty() || !lines.getFirst().equals("file,attempt,tics,sha256,health,armor")) throw new IOException("Invalid success index");
        try {
            for (String line : lines.subList(1, lines.size())) {
                String[] f = line.split(",");
                if (f.length != 6 || !f[0].equals(String.format(Locale.ROOT, "s%03d.lmp", entries.size() + 1))) throw new IOException("Invalid success entry");
                entries.add(new Entry(f[0], Long.parseLong(f[1]), Integer.parseInt(f[2]), f[3], Integer.parseInt(f[4]), Integer.parseInt(f[5])));
            }
        } catch (NumberFormatException e) { throw new IOException("Invalid success numbers", e); }
        return List.copyOf(entries);
    }
    static String digest(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
