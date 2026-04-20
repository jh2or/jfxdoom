// Port of the patch_t / column_t (post_t) types from r_defs.h.
//
// In C, a `patch_t*` is a pointer directly at the start of a WAD lump. The
// header is the first 8 bytes (width, height, leftoffset, topoffset, all
// short), followed by `columnofs[width]` int32 offsets, followed by the
// raw column data. Walking a post uses pointer arithmetic from the patch
// base — e.g.
//   column = (column_t*)((byte*)patch + LONG(patch->columnofs[col]))
//   source = (byte*)column + 3
//   next   = (column_t*)((byte*)column + column->length + 4)
//
// Java lacks pointer arithmetic, so we keep the raw lump bytes on the patch
// object and translate "pointer to column" as "byte offset into data" — the
// callers in V_DrawPatch / R_DrawColumn then use indexed byte[] access.
//
// Sizes: short = 2 bytes, int = 4 bytes, column header = topdelta + length
// + pad + length pixels + pad  → 2 byte header + 1 pad + N pixels + 1 pad,
// i.e. `column->length + 4` bytes total.  A column terminates when its
// topdelta byte is 0xff.
package doom.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class Patch {

    // patch_t header (r_defs.h lines 357–364).
    public int width;       // short
    public int height;      // short
    public int leftoffset;  // short
    public int topoffset;   // short
    public int[] columnofs; // int[width] — absolute offsets into `data`

    // The raw lump bytes. columnofs[] indexes into this array.
    public byte[] data;

    private Patch() {
    }

    // Parse a patch lump from its raw bytes. The C would just cast the lump
    // to `patch_t*`; here we pull the header fields out explicitly and keep
    // the full buffer so column walks still work.
    public static Patch fromLump(byte[] lump) {
        Patch p = new Patch();
        ByteBuffer buf = ByteBuffer.wrap(lump).order(ByteOrder.LITTLE_ENDIAN);
        p.width = buf.getShort() & 0xFFFF;
        p.height = buf.getShort() & 0xFFFF;
        p.leftoffset = buf.getShort(); // signed, pixels
        p.topoffset = buf.getShort();  // signed, pixels
        p.columnofs = new int[p.width];
        for (int i = 0; i < p.width; i++) {
            p.columnofs[i] = buf.getInt();
        }
        p.data = lump;
        return p;
    }
}
