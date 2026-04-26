// Port of linuxdoom-1.10/p_telept.c — stub.
//
// EV_Teleport scans the thinker list for an MT_TELEPORTMAN in the
// tag-matched sector, then calls P_TeleportMove + spawns two MT_TFOG
// mobjs around the source/destination. Both depend on machinery that
// isn't ported yet:
//
//   - P_TeleportMove (p_map.c) — blockmap+sector relinking
//   - P_SpawnMobj    (p_mobj.c) — full mobj_t allocation/state setup
//   - P_MobjThinker  (p_mobj.c) — function-pointer identity check
//   - mobj_t fields  — type, subsector, flags, floorz, momx/y/z,
//                      reactiontime
//
// Until those land, the stub returns 0 (no teleport happened). Same
// pattern as PMap.pChangeSector (deviation 21). Tests that need to
// drive the "teleport succeeded" path can flip teleportResult.
package doom.play;

import doom.render.Line;

public final class PTelept {

    // Test-only override; replaced by the real C logic when EV_Teleport
    // ports.
    public static int teleportResult = 0;

    // p_telept.c lines 47–131 — EV_Teleport.
    public static int evTeleport(Line line, int side, Mobj thing) {
        return teleportResult;
    }

    private PTelept() {
    }
}
