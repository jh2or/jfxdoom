package doom.ai;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Versioned, checksummed archive. Common route prefixes are serialized once. */
final class Checkpoint {
    private static final int MAGIC = 0x44414936;
    static final class Progress {
        final Archive archive;
        final LearnerRandom random;
        long attempts, deaths, timeouts, interrupted, simulatedTics, optimizations, successes, pruned;
        Route best;
        Progress(TrainingConfig config) {
            archive = new Archive(config.archiveSize(), config.nodeLimit(), config.cellSize(), config.strategy());
            random = new LearnerRandom(config.seed());
        }
    }

    static String identity(TrainingConfig config) throws Exception {
        MessageDigest engine = MessageDigest.getInstance("SHA-256");
        Path code = Path.of(Trainer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isDirectory(code)) {
            try (var paths = Files.walk(code.resolve("doom"))) {
                for (Path p : paths.filter(Files::isRegularFile).sorted().toList()) {
                    engine.update(code.relativize(p).toString().replace('\\', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    update(engine, p);
                }
            }
        } else update(engine, code);
        MessageDigest wad = MessageDigest.getInstance("SHA-256"); update(wad, config.wad());
        return config.identity() + "\nwad-sha256=" + HexFormat.of().formatHex(wad.digest())
                + "\nengine-sha256=" + HexFormat.of().formatHex(engine.digest());
    }

    private static void update(MessageDigest digest, Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = input.read(buffer)) >= 0) digest.update(buffer, 0, n);
        }
    }

    static void save(Path path, String identity, Progress progress) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeUTF(identity); out.writeLong(progress.random.state);
            out.writeLong(progress.attempts); out.writeLong(progress.deaths); out.writeLong(progress.timeouts);
            out.writeLong(progress.interrupted); out.writeLong(progress.simulatedTics);
            out.writeLong(progress.optimizations); out.writeLong(progress.successes); out.writeLong(progress.pruned);
            Archive a = progress.archive;
            out.writeLong(a.discoveries);
            List<Route> nodes = new ArrayList<>();
            IdentityHashMap<Route, Integer> ids = new IdentityHashMap<>();
            ids.put(null, -1);
            List<Archive.Entry> entries = a.entries();
            List<Route> endpoints = new ArrayList<>();
            for (Archive.Entry e : entries) endpoints.add(e.route);
            if (progress.best != null) endpoints.add(progress.best);
            for (Route end : endpoints) {
                ArrayDeque<Route> missing = new ArrayDeque<>();
                for (Route n = end; !ids.containsKey(n); n = n.parent) missing.push(n);
                while (!missing.isEmpty()) { Route n = missing.pop(); ids.put(n, nodes.size()); nodes.add(n); }
            }
            out.writeInt(nodes.size());
            for (Route n : nodes) {
                out.writeInt(ids.get(n.parent)); out.writeInt(n.action.packed());
                out.writeInt(n.duration); out.writeLong(n.fingerprint);
            }
            out.writeInt(ids.get(progress.best));
            out.writeInt(entries.size());
            for (Archive.Entry e : entries) {
                Archive.Cell c = e.cell;
                out.writeInt(c.x()); out.writeInt(c.y()); out.writeInt(c.facing()); out.writeInt(c.health());
                out.writeInt(c.ammo()); out.writeInt(c.weapons()); out.writeInt(c.opening()); out.writeInt(c.keys()); out.writeInt(c.armor()); out.writeInt(c.shells()); out.writeInt(c.progress());
                out.writeInt(ids.get(e.route)); out.writeInt(e.health); out.writeLong(e.discovered); out.writeInt(e.selected);
            }
            out.writeInt(a.danger.size());
            for (var entry : a.danger.entrySet()) { out.writeInt(entry.getKey().x()); out.writeInt(entry.getKey().y()); out.writeInt(entry.getKey().keys()); out.writeInt(entry.getKey().progress()); out.writeInt(entry.getValue()); }
            out.writeInt(a.visits.size());
            for (var visit : a.visits.entrySet()) { out.writeInt(visit.getKey().x()); out.writeInt(visit.getKey().y()); out.writeInt(visit.getKey().keys()); out.writeInt(visit.getKey().progress()); out.writeInt(visit.getValue()); }
        }
        byte[] body = bytes.toByteArray();
        CRC32 crc = new CRC32(); crc.update(body);
        ByteArrayOutputStream envelope = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(envelope))) {
            out.writeInt(body.length); out.write(body); out.writeLong(crc.getValue());
        }
        Route.atomicWrite(path, envelope.toByteArray());
    }

    static Progress load(Path path, String identity, TrainingConfig config) throws IOException {
        long maxBytes = 1024L + (config.nodeLimit() + config.episodeTics()) * 20L + config.archiveSize() * 192L;
        byte[] body;
        try (DataInputStream input = new DataInputStream(new GZIPInputStream(Files.newInputStream(path)))) {
            int length = input.readInt();
            if (length < 0 || length > maxBytes) throw new IOException("Invalid checkpoint size");
            body = input.readNBytes(length);
            if (body.length != length) throw new EOFException("Truncated checkpoint");
            CRC32 crc = new CRC32(); crc.update(body);
            if (input.readLong() != crc.getValue() || input.read() != -1) throw new IOException("Checkpoint checksum mismatch");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            if (in.readInt() != MAGIC || !in.readUTF().equals(identity)) {
                throw new IOException("Checkpoint does not match this WAD, engine build, or learner configuration");
            }
            Progress p = new Progress(config); p.random.state = in.readLong();
            p.attempts = nonnegative(in.readLong()); p.deaths = nonnegative(in.readLong());
            p.timeouts = nonnegative(in.readLong()); p.interrupted = nonnegative(in.readLong());
            p.simulatedTics = nonnegative(in.readLong());
            p.optimizations = nonnegative(in.readLong()); p.successes = nonnegative(in.readLong()); p.pruned = nonnegative(in.readLong());
            p.archive.discoveries = nonnegative(in.readLong());
            Route[] nodes = new Route[count(in, config.nodeLimit() + config.episodeTics())];
            for (int i = 0; i < nodes.length; i++) {
                int parent = in.readInt();
                if (parent < -1 || parent >= i) throw new IOException("Invalid prefix reference");
                Action action = Action.unpack(in.readInt()); int duration = in.readInt(); long fingerprint = in.readLong();
                nodes[i] = new Route(parent < 0 ? null : nodes[parent], action, duration, fingerprint);
                if (nodes[i].tics > config.episodeTics()) throw new IOException("Archived prefix reaches episode limit");
            }
            int best = in.readInt();
            if (best < -1 || best >= nodes.length) throw new IOException("Invalid best route");
            p.best = best < 0 ? null : nodes[best];
            int entries = count(in, config.archiveSize());
            for (int i = 0; i < entries; i++) {
                Archive.Cell c = new Archive.Cell(in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                        in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
                int route = in.readInt(), health = in.readInt(); long discovered = nonnegative(in.readLong());
                int selected = in.readInt();
                if (route < 0 || route >= nodes.length || nodes[route].tics >= config.episodeTics() || health <= 0 || selected < 0 || c.keys() < 0 || c.keys() > 63 || c.armor() < 0 || c.shells() < 0
                        || p.archive.cells.getOrDefault(c, List.of()).size() >= 2) throw new IOException("Invalid archive entry");
                Archive.Entry entry = new Archive.Entry(c, nodes[route], health, discovered); entry.selected = selected;
                p.archive.restore(entry);
            }
            if (p.archive.nodes() > config.nodeLimit()) throw new IOException("Archive exceeds shared-prefix limit");
            int dangers = count(in, config.archiveSize());
            for (int i = 0; i < dangers; i++) {
                Archive.Location key = new Archive.Location(in.readInt(), in.readInt(), in.readInt(), in.readInt());
                int value = in.readInt();
                if (key.keys() < 0 || key.keys() > 63 || value < 1 || value > 200 || p.archive.danger.put(key, value) != null)
                    throw new IOException("Invalid danger memory");
            }
            int visits = count(in, config.archiveSize() * 4);
            for (int i = 0; i < visits; i++) {
                Archive.Location key = new Archive.Location(in.readInt(), in.readInt(), in.readInt(), in.readInt()); int value = in.readInt();
                if (key.keys() < 0 || key.keys() > 63 || value < 1 || value > 1000000 || p.archive.visits.put(key, value) != null) throw new IOException("Invalid visits");
            }
            if (in.read() != -1) throw new IOException("Trailing checkpoint data");
            return p;
        } catch (IllegalArgumentException e) { throw new IOException("Invalid checkpoint", e); }
    }

    private static int count(DataInputStream in, int max) throws IOException {
        int value = in.readInt();
        if (value < 0 || value > max) throw new IOException("Invalid checkpoint count");
        return value;
    }
    private static long nonnegative(long value) throws IOException {
        if (value < 0) throw new IOException("Invalid checkpoint counter");
        return value;
    }
}
