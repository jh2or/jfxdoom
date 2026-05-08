// Port of linuxdoom-1.10/r_defs.h spriteframe_t (lines 427–440).
//
// One frame in a sprite's animation cycle. Each frame can be
// rotated to face the viewer at one of 8 octant angles, OR a single
// frame is reused for every angle (rotate = false). flip[r] sets
// the horizontal-mirror bit for the r'th rotation.
package doom.render;

public final class Spriteframe {

    // If false, all 8 rotations point at the same lump (and flip[]
    // is unused).
    public boolean rotate;

    // Lump indices into firstspritelump..lastspritelump for view
    // angles 0..7. Initialized to -1 by R_InitSpriteDefs.
    public final short[] lump = new short[8];

    // 1 = flipped horizontally for the r'th rotation, 0 = not.
    public final byte[] flip = new byte[8];
}
