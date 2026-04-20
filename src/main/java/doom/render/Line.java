// Port of linuxdoom-1.10/r_defs.h line_t (lines 179–215).
// Runtime record for a map linedef, populated by p_setup.
package doom.render;

public final class Line {

    // Vertices, from v1 to v2.
    public Vertex v1;
    public Vertex v2;

    // Precalculated v2 - v1 for side checking.
    public int dx; // fixed_t
    public int dy; // fixed_t

    // Animation related.
    public int flags;   // short
    public int special; // short
    public int tag;     // short

    // Visual appearance: SideDefs.
    //  sidenum[1] will be -1 if one sided
    public int[] sidenum = new int[2];

    // Neat. Another bounding box, for the extent
    //  of the LineDef.
    public int[] bbox = new int[4]; // fixed_t

    // To aid move clipping.
    public Slopetype slopetype;

    // Front and back sector.
    // Note: redundant? Can be retrieved from SideDefs.
    public Sector frontsector;
    public Sector backsector;

    // if == validcount, already checked
    public int validcount;

    // thinker_t for reversable actions (anonymous type in C).
    public Object specialdata;
}
