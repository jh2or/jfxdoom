package doom.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;

import org.junit.jupiter.api.Test;

class MusToMidiTest {

    // Minimal MUS lump with one play-note + score-end. Returns the
    // converted byte stream.
    private static byte[] miniMus() throws IOException {
        byte[] lump = new byte[20];
        lump[0] = 'M'; lump[1] = 'U'; lump[2] = 'S'; lump[3] = 0x1A;
        // score length = 4 (event byte + note byte + score-end + tail VLQ)
        lump[4] = 4; lump[5] = 0;
        // score offset = 16
        lump[6] = 16; lump[7] = 0;
        // primaryChans, secChans, instCount, reserved — all 0
        // (lump[8..15] zero by default)
        // Score @ 16: play-note (type=1, channel=0, last bit off),
        // note byte (high bit = "has volume"), volume, end.
        lump[16] = 0x10;                     // event byte: type=1, chan=0, last=0
        lump[17] = (byte) (0x80 | 60);       // play C4 with volume bit
        lump[18] = 64;                       // volume
        lump[19] = 0x60;                     // event byte: type=6 (score end)
        return lump;
    }

    // ---- header gate ----

    @Test
    void convertReturnsNullForNonMusMagic() throws IOException {
        byte[] notMus = "MThd".getBytes();
        assertNull(MusToMidi.convert(notMus));
    }

    @Test
    void convertReturnsNullForShortLump() throws IOException {
        assertNull(MusToMidi.convert(new byte[8]));
    }

    @Test
    void convertReturnsNullForNullInput() throws IOException {
        assertNull(MusToMidi.convert(null));
    }

    // ---- MIDI header shape ----

    // The converter prepends MThd (14 bytes) + MTrk header (8 bytes).
    @Test
    void convertStartsWithMthdChunk() throws IOException {
        byte[] midi = MusToMidi.convert(miniMus());
        assertNotNull(midi);
        assertEquals('M', midi[0]);
        assertEquals('T', midi[1]);
        assertEquals('h', midi[2]);
        assertEquals('d', midi[3]);
    }

    // MThd-chunk length is always 6.
    @Test
    void convertMthdChunkLengthIsSix() throws IOException {
        byte[] midi = MusToMidi.convert(miniMus());
        int len = ((midi[4] & 0xff) << 24)
                | ((midi[5] & 0xff) << 16)
                | ((midi[6] & 0xff) << 8)
                |  (midi[7] & 0xff);
        assertEquals(6, len);
    }

    // Format 0 (single track) + 1 track count + division 70.
    @Test
    void convertMthdHeaderFields() throws IOException {
        byte[] midi = MusToMidi.convert(miniMus());
        int format    = ((midi[8]  & 0xff) << 8) | (midi[9]  & 0xff);
        int trackNum  = ((midi[10] & 0xff) << 8) | (midi[11] & 0xff);
        int division  = ((midi[12] & 0xff) << 8) | (midi[13] & 0xff);
        assertEquals(0,  format);
        assertEquals(1,  trackNum);
        assertEquals(70, division);
    }

    // MTrk follows MThd (offset 14).
    @Test
    void convertHasMtrkAfterMthd() throws IOException {
        byte[] midi = MusToMidi.convert(miniMus());
        assertEquals('M', midi[14]);
        assertEquals('T', midi[15]);
        assertEquals('r', midi[16]);
        assertEquals('k', midi[17]);
    }

    // The converted stream parses cleanly via javax.sound.midi.
    @Test
    void convertResultLoadsAsValidMidiSequence() throws Exception {
        byte[] midi = MusToMidi.convert(miniMus());
        Sequence seq = MidiSystem.getSequence(new ByteArrayInputStream(midi));
        assertNotNull(seq);
        assertEquals(1, seq.getTracks().length);
        // One Note On + one end-of-track meta event.
        assertTrue(seq.getTracks()[0].size() >= 2);
    }

    // ---- event translation: percussion channel swap ----

    // MUS channel 15 is the percussion channel; MIDI uses channel 9.
    // A play-note on MUS chan 15 should emit MIDI 0x99 (Note On,
    // channel 9).
    @Test
    void convertSwapsPercussionChannel() throws IOException {
        byte[] mus = new byte[19];
        mus[0]='M'; mus[1]='U'; mus[2]='S'; mus[3]=0x1A;
        mus[4]=3; mus[5]=0;       // score length
        mus[6]=16; mus[7]=0;      // score offset
        // event byte: play-note, channel 15
        mus[16] = (byte) (0x10 | 15);
        mus[17] = (byte) (0x80 | 35);  // bass drum
        mus[18] = 100;                 // volume
        byte[] midi = MusToMidi.convert(mus);
        assertNotNull(midi);
        // Find a 0x99 byte (Note On, channel 9) in the track body.
        boolean foundChan9 = false;
        for (int i = 22; i < midi.length - 2; i++) {
            if ((midi[i] & 0xff) == 0x99) { foundChan9 = true; break; }
        }
        assertTrue(foundChan9, "expected MIDI Note On on channel 9");
    }
}
