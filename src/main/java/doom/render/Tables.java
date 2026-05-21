// Port of linuxdoom-1.10/tables.c and tables.h.
// Lookup tables for sin, cos, tan, and atan, plus SlopeDiv.
//
// The three big tables (finetangent[4096], finesine[10240], tantoangle[2049])
// live in binary little-endian int32 resources under doom/render/, extracted
// verbatim from tables.c by scripts/extract_tables.py. See PORT_STATUS deviation (2).
package doom.render;

import doom.fixed.Fixed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class Tables {

    // Binary Angle Measurement constants (tables.h).
    // Stored as int; the C uses these as unsigned 32-bit `angle_t` — bit patterns match.
    public static final int ANG45 = 0x20000000;
    public static final int ANG90 = 0x40000000;
    public static final int ANG180 = 0x80000000;
    public static final int ANG270 = 0xc0000000;

    public static final int FINEANGLES = 8192;
    public static final int FINEMASK = FINEANGLES - 1;

    // #define ANGLETOFINESHIFT 19
    public static final int ANGLETOFINESHIFT = 19;

    public static final int SLOPERANGE = 2048;
    public static final int SLOPEBITS = 11;
    public static final int DBITS = Fixed.FRACBITS - SLOPEBITS;

    // fixed_t finesine[5*FINEANGLES/4];   // 10240 entries
    public static final int[] finesine = loadInts("finesine.dat", 5 * FINEANGLES / 4);

    // fixed_t* finecosine = &finesine[FINEANGLES/4];  // PI/2 phase shift alias
    // Java has no aliasing int* into an int[], so we expose the base array + offset,
    // and callers should use `finesine[(angle + FINEANGLES/4) & FINEMASK_FOR_COS]`.
    // For index-by-angle callers we also provide a cosine accessor:
    public static final int FINECOSINE_OFFSET = FINEANGLES / 4;

    // fixed_t finetangent[FINEANGLES/2];   // 4096 entries
    //
    // In the C build, `finetangent` is immediately followed in
    // memory by `finesine` (both are file-statics in tables.c).
    // r_segs.c reads `finetangent[angle]` for `angle` in [0, 8191]
    // without bounds-checking — indices 4096..8191 silently
    // overrun into `finesine[0..4095]`. The Doom renderer
    // depends on those "overrun" values for wall-column
    // projection at certain seg angles.
    //
    // Java's bounded arrays throw on OOB. To preserve the C's
    // effective behavior, we allocate a 8192-entry buffer with
    // the raw tangent values in [0, 4095] and finesine[0..4095]
    // mirrored into [4096, 8191] — exactly what the C reads.
    // See deviation 40.
    public static final int[] finetangent = loadFinetangentExtended();

    private static int[] loadFinetangentExtended() {
        int[] base = loadInts("finetangent.dat", FINEANGLES / 2);
        int[] ext = new int[FINEANGLES];
        System.arraycopy(base, 0, ext, 0, FINEANGLES / 2);
        // finesine is loaded above; copy its first 4096 entries
        // into the upper half of finetangent to mimic the C's
        // adjacent-array OOB read.
        System.arraycopy(finesine, 0, ext, FINEANGLES / 2, FINEANGLES / 2);
        return ext;
    }

    // angle_t tantoangle[SLOPERANGE+1];    // 2049 entries
    public static final int[] tantoangle = loadInts("tantoangle.dat", SLOPERANGE + 1);

    private Tables() {
    }

    // int SlopeDiv(unsigned num, unsigned den)
    //     if (den < 512) return SLOPERANGE;
    //     ans = (num<<3)/(den>>8);
    //     return ans <= SLOPERANGE ? ans : SLOPERANGE;
    //
    // `num` and `den` are unsigned in C. Use int + unsigned-shift to match.
    public static int slopeDiv(int num, int den) {
        // Unsigned compare: `Integer.compareUnsigned`. In practice `den < 512`
        // uses the low bits only and never sees large negative-looking values
        // in Doom, but being faithful:
        if (Integer.compareUnsigned(den, 512) < 0) {
            return SLOPERANGE;
        }
        int ans = (num << 3) / (den >>> 8);
        return (ans <= SLOPERANGE) ? ans : SLOPERANGE;
    }

    // finecosine accessor — index into finesine with the PI/2 offset.
    public static int finecosine(int index) {
        return finesine[index + FINECOSINE_OFFSET];
    }

    private static int[] loadInts(String resource, int expected) {
        String path = "/doom/render/" + resource;
        try (InputStream in = Tables.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource: " + path);
            }
            byte[] bytes = in.readAllBytes();
            if (bytes.length != expected * 4) {
                throw new IllegalStateException(
                        "size mismatch for " + path + ": got " + bytes.length
                                + " bytes, want " + (expected * 4));
            }
            ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int[] out = new int[expected];
            for (int i = 0; i < expected; i++) {
                out[i] = buf.getInt();
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("failed reading " + path, e);
        }
    }

}
