package doom.sound;

import javax.sound.sampled.SourceDataLine;

/** Bounded PCM backlog owned by the game thread; device writes never wait for space. */
final class BufferedSoundOutput {
    private static final int FRAME_BYTES = 4; // signed 16-bit stereo
    private final SourceDataLine line;
    private final byte[] pending;
    private int head;
    private int size;

    BufferedSoundOutput(SourceDataLine line, int capacity) {
        if (capacity <= 0 || capacity % FRAME_BYTES != 0) {
            throw new IllegalArgumentException("PCM capacity must contain whole stereo frames");
        }
        this.line = line;
        pending = new byte[capacity];
    }

    void submit(byte[] pcm) {
        if (pcm.length > pending.length || pcm.length % FRAME_BYTES != 0) {
            throw new IllegalArgumentException("PCM submission must fit and contain whole frames");
        }
        flushAvailable();
        // Retain short bursts in order. On sustained overload, discard only
        // the oldest queued frames to bound memory and avoid stale sound lag.
        int discard = Math.max(0, size + pcm.length - pending.length);
        head = (head + discard) % pending.length;
        size -= discard;
        int tail = (head + size) % pending.length;
        int first = Math.min(pcm.length, pending.length - tail);
        System.arraycopy(pcm, 0, pending, tail, first);
        System.arraycopy(pcm, first, pending, 0, pcm.length - first);
        size += pcm.length;
        flushAvailable();
    }

    private void flushAvailable() {
        // SourceDataLine.available guarantees this many bytes can be written
        // without blocking. There is only one writer, on the game thread.
        int available = line.available();
        available -= available % FRAME_BYTES;
        while (size > 0 && available > 0) {
            int count = Math.min(Math.min(size, available), pending.length - head);
            int written = line.write(pending, head, count);
            if (written <= 0) return;
            head = (head + written) % pending.length;
            size -= written;
            available -= written;
        }
    }

    void close() {
        // Quit must not wait for a stalled device to drain buffered sound.
        try {
            line.stop();
            line.flush();
        } finally {
            line.close();
            size = 0;
        }
    }
}
