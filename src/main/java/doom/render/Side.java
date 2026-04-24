// Port of linuxdoom-1.10/r_defs.h side_t (lines 144–161).
// Runtime record for a map sidedef, populated by p_setup.
package doom.render;

public final class Side {

    // Added to the calculated texture column.
    public int textureoffset;   // fixed_t

    // Added to the calculated texture top.
    public int rowoffset;       // fixed_t

    // Texture indices. Names are not maintained here.
    public int toptexture;      // short
    public int bottomtexture;   // short
    public int midtexture;      // short

    // Sector the SideDef is facing.
    public Sector sector;
}
