// MUS → MIDI byte-stream converter.
//
// Doom's music lumps (D_E1M1 etc.) are stored in the DMX MUS
// format, a compact MIDI-like binary that drops the per-event
// timing in favour of a "delay tic count" appended to each event
// that has the high "last" bit set.
//
// This converter writes a standard MIDI Type-0 (single-track)
// stream wrapped in MThd / MTrk chunks so the result can be
// pushed straight at `javax.sound.midi.Sequencer`. Once written,
// the sequencer + the JVM's bundled General MIDI soft synth
// handles the rest.
//
// MUS header (16 bytes, little-endian):
//   0..3   "MUS"
//   4..5   score length
//   6..7   score offset
//   8..9   primary channel count
//   10..11 secondary channel count
//   12..13 instrument count
//   14..15 (reserved)
//   16..   instrument-patch list (2 bytes each), then score data
//
// MUS event byte (1 byte):
//   bit 7   "last" — when set, a VLQ delay follows the event data
//   bits 6-4 event type (0..7)
//   bits 3-0 channel (0..15)
//
// Event types:
//   0  release note         (data: note)
//   1  play note            (data: note + optional volume bit)
//   2  pitch bend           (data: pitch)
//   3  system event         (data: controller — translated to MIDI CC)
//   4  change controller    (data: controller, value)
//   5  end of measure       (no data — undocumented; just emit nothing)
//   6  score end            (terminator)
//   7  unused
//
// MUS channel 15 is the percussion channel — MIDI channel 9. The
// converter swaps them on the fly. All other channels pass through.
package doom.sound;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class MusToMidi {

    // MUS event types (high three bits of the event byte, shifted right by 4).
    private static final int MUS_RELEASE = 0;
    private static final int MUS_PLAY    = 1;
    private static final int MUS_BEND    = 2;
    private static final int MUS_SYSTEM  = 3;
    private static final int MUS_CONTROL = 4;
    private static final int MUS_END     = 6;

    // MIDI controller map for MUS_SYSTEM events 10..14.
    private static final int[] MUS_SYSTEM_CC = {
            0,   // 0 unused
            0,   // 1 unused
            0,   // 2 unused
            0,   // 3 unused
            0,   // 4 unused
            0,   // 5 unused
            0,   // 6 unused
            0,   // 7 unused
            0,   // 8 unused
            0,   // 9 unused
            120, // 10 all sounds off
            123, // 11 all notes off
            126, // 12 mono
            127, // 13 poly
            121, // 14 reset all controllers
    };

    // MIDI controller map for MUS_CONTROL events 0..9.
    private static final int[] MUS_CONTROL_CC = {
            0,   // 0 patch (special — handled inline as program change)
            0,   // 1 bank select
            1,   // 2 modulation
            7,   // 3 volume
            10,  // 4 pan
            11,  // 5 expression
            91,  // 6 reverb depth
            93,  // 7 chorus depth
            64,  // 8 sustain pedal
            67,  // 9 soft pedal
    };

    private MusToMidi() {
    }

    /**
     * Convert a MUS lump to a MIDI byte stream playable by
     * {@code javax.sound.midi.Sequencer}.
     *
     * Returns {@code null} if the input doesn't start with the
     * MUS magic header.
     */
    public static byte[] convert(byte[] mus) throws IOException {
        if (mus == null || mus.length < 16) return null;
        if (mus[0] != 'M' || mus[1] != 'U' || mus[2] != 'S' || mus[3] != 0x1A) {
            return null;
        }
        ByteBuffer hdr = ByteBuffer.wrap(mus, 0, 16).order(ByteOrder.LITTLE_ENDIAN);
        hdr.position(4);
        int scoreLen    = hdr.getShort() & 0xffff;
        int scoreStart  = hdr.getShort() & 0xffff;
        // primaryChans, secChans, instCount, reserved skipped.
        if (scoreStart < 0 || scoreStart >= mus.length) return null;
        int scoreEnd = Math.min(mus.length, scoreStart + scoreLen);

        // MIDI track bytes accumulate into `track`. After we know
        // the size we wrap MThd + MTrk headers around it.
        ByteArrayOutputStream track = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(track);

        // Per-MUS-channel "last volume" — MUS play-note events use
        // either a 1-byte note (reusing the prior volume) or a
        // 2-byte note+volume pair. MIDI always wants the volume.
        int[] lastVolume = new int[16];

        // Track the pending delta-time accumulator (VLQ on the MIDI
        // side).
        int delta = 0;

        int p = scoreStart;
        boolean ended = false;
        while (p < scoreEnd && !ended) {
            int evByte = mus[p++] & 0xff;
            boolean last = (evByte & 0x80) != 0;
            int type    = (evByte >> 4) & 0x07;
            int musChan =  evByte       & 0x0f;
            int midiChan = musChan == 15 ? 9 : musChan == 9 ? 15 : musChan;

            switch (type) {
                case MUS_RELEASE: {
                    int note = mus[p++] & 0x7f;
                    writeVlq(out, delta);
                    out.writeByte(0x80 | midiChan);
                    out.writeByte(note);
                    out.writeByte(64);  // velocity (release)
                    delta = 0;
                    break;
                }
                case MUS_PLAY: {
                    int data = mus[p++] & 0xff;
                    int note = data & 0x7f;
                    int vol;
                    if ((data & 0x80) != 0) {
                        vol = mus[p++] & 0x7f;
                        lastVolume[musChan] = vol;
                    } else {
                        vol = lastVolume[musChan];
                    }
                    writeVlq(out, delta);
                    out.writeByte(0x90 | midiChan);
                    out.writeByte(note);
                    out.writeByte(vol);
                    delta = 0;
                    break;
                }
                case MUS_BEND: {
                    int bend = mus[p++] & 0xff;
                    // MUS bend is 0..255 with 128 = centre. MIDI is
                    // a 14-bit value (0..16383) with 8192 = centre.
                    int midiBend = bend << 6;
                    writeVlq(out, delta);
                    out.writeByte(0xE0 | midiChan);
                    out.writeByte(midiBend & 0x7f);
                    out.writeByte((midiBend >> 7) & 0x7f);
                    delta = 0;
                    break;
                }
                case MUS_SYSTEM: {
                    int controller = mus[p++] & 0xff;
                    int cc = (controller < MUS_SYSTEM_CC.length) ? MUS_SYSTEM_CC[controller] : 0;
                    if (cc != 0) {
                        writeVlq(out, delta);
                        out.writeByte(0xB0 | midiChan);
                        out.writeByte(cc);
                        out.writeByte(0);
                        delta = 0;
                    }
                    break;
                }
                case MUS_CONTROL: {
                    int controller = mus[p++] & 0xff;
                    int value      = mus[p++] & 0xff;
                    if (controller == 0) {
                        // Program change (patch) — MIDI status 0xC0.
                        writeVlq(out, delta);
                        out.writeByte(0xC0 | midiChan);
                        out.writeByte(value & 0x7f);
                        delta = 0;
                    } else {
                        int cc = (controller < MUS_CONTROL_CC.length)
                                ? MUS_CONTROL_CC[controller] : 0;
                        if (cc != 0) {
                            writeVlq(out, delta);
                            out.writeByte(0xB0 | midiChan);
                            out.writeByte(cc);
                            out.writeByte(value & 0x7f);
                            delta = 0;
                        }
                    }
                    break;
                }
                case MUS_END:
                    ended = true;
                    break;
                default:
                    // Unknown type — skip a guessed-1-byte payload.
                    p++;
                    break;
            }

            if (last && !ended) {
                // VLQ delay (1-byte chunks, bit 7 = continuation).
                int d = 0;
                while (p < scoreEnd) {
                    int b = mus[p++] & 0xff;
                    d = (d << 7) | (b & 0x7f);
                    if ((b & 0x80) == 0) break;
                }
                delta += d;
            }
        }

        // End-of-track meta-event.
        writeVlq(out, delta);
        out.writeByte(0xff);
        out.writeByte(0x2f);
        out.writeByte(0x00);
        out.flush();

        byte[] trackBytes = track.toByteArray();

        // Wrap with MThd (14 bytes) + MTrk header (8 bytes) chunks.
        ByteArrayOutputStream midi = new ByteArrayOutputStream();
        DataOutputStream m = new DataOutputStream(midi);
        // MThd
        m.writeBytes("MThd");
        m.writeInt(6);
        m.writeShort(0);   // format 0
        m.writeShort(1);   // one track
        // Division — MUS plays at 140 Hz (per the DMX spec); 70 ticks
        // per quarter note ≈ 120 BPM. Use 70 / quarter, which the
        // sequencer divides into the track delta-times directly.
        m.writeShort(70);
        // MTrk
        m.writeBytes("MTrk");
        m.writeInt(trackBytes.length);
        m.write(trackBytes);
        m.flush();
        return midi.toByteArray();
    }

    // Standard MIDI variable-length-quantity encoder. Writes the
    // 7-bit groups of `value` with the high bit set on every byte
    // except the last.
    private static void writeVlq(DataOutputStream out, int value) throws IOException {
        if (value < 0) value = 0;
        int buf = value & 0x7f;
        value >>>= 7;
        while (value != 0) {
            buf <<= 8;
            buf |= 0x80 | (value & 0x7f);
            value >>>= 7;
        }
        while (true) {
            out.writeByte(buf & 0xff);
            if ((buf & 0x80) != 0) {
                buf >>= 8;
            } else {
                break;
            }
        }
    }
}
