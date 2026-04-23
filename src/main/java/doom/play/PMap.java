// Port of linuxdoom-1.10/p_map.c — stub.
//
// p_map.c is the ~900-line movement/collision module: P_TryMove,
// P_LineOpening, P_ChangeSector, P_UseLines, P_RadiusAttack, and the
// blockmap iterator scaffolding. Almost every function pulls in mobj
// fields, blockmap globals, and PIT_* iterator callbacks — none of
// which are ported yet.
//
// For now this class only carries P_ChangeSector, the one function
// T_MovePlane (p_floor.c) calls. See deviation 21 for the stub
// contract: absent mobjs, no crush is possible, so the stub returns
// false (no blocking mobj detected). Tests that need to force the
// crush return flip `changeSectorResult` before calling into
// T_MovePlane.
package doom.play;

import doom.render.Sector;

public final class PMap {

    // Test-only override for the stub return value. When the full
    // P_ChangeSector lands (with mobj iteration over sector blocklist),
    // this field is removed and the C logic takes over.
    public static boolean changeSectorResult = false;

    // p_map.c — P_ChangeSector.
    // The C signature: `boolean P_ChangeSector(sector_t*, boolean crunch)`.
    // It walks the sector's thing list via the blockmap and calls
    // PIT_ChangeSector on each one; PIT_ChangeSector returns false if
    // any mobj is blocked, which propagates up as "something is in the
    // way". The true return means "crushed" to T_MovePlane.
    public static boolean pChangeSector(Sector sector, boolean crunch) {
        return changeSectorResult;
    }

    private PMap() {
    }
}
