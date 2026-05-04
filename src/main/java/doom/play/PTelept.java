// Port of linuxdoom-1.10/p_telept.c — full.
//
// EV_Teleport: scan the thinker list for an MT_TELEPORTMAN in the
// tag-matched sector, then call P_TeleportMove + spawn two MT_TFOG
// mobjs around the source/destination, freeze the player for 18 tics,
// and re-aim the thing's angle to the destination's angle.
package doom.play;

import doom.render.Line;
import doom.render.Tables;
import doom.sound.SSound;
import doom.sound.Sounds;

public final class PTelept {

    private PTelept() {
    }

    //
    // EV_Teleport
    //
    // p_telept.c lines 47–131.
    public static int evTeleport(Line line, int side, Mobj thing) {
        int i;
        int tag;
        Mobj m;
        Mobj fog;
        int an;
        Thinker thinker;
        doom.render.Sector sector;
        int oldx;
        int oldy;
        int oldz;

        // don't teleport missiles
        if ((thing.flags & Mobj.MF_MISSILE) != 0) {
            return 0;
        }

        // Don't teleport if hit back of line,
        //  so you can get out of teleporter.
        if (side == 1) {
            return 0;
        }


        tag = line.tag;
        for (i = 0; i < PSetup.numsectors; i++) {
            if (PSetup.sectors[i].tag == tag) {
                // Walk the thinker chain looking for an MT_TELEPORTMAN
                // in this sector.
                for (thinker = PTick.thinkercap.next;
                        thinker != PTick.thinkercap;
                        thinker = thinker.next) {
                    // not a mobj
                    // C: thinker.function.acp1 != (actionf_p1)P_MobjThinker
                    if (!(thinker instanceof Mobj)) {
                        continue;
                    }

                    m = (Mobj) thinker;

                    // not a teleportman
                    if (m.type != Info.MT_TELEPORTMAN) {
                        continue;
                    }

                    sector = m.subsector.sector;
                    // wrong sector
                    // C: sector - sectors != i
                    if (sectorIndex(sector) != i) {
                        continue;
                    }

                    oldx = thing.x;
                    oldy = thing.y;
                    oldz = thing.z;

                    if (!PMap.pTeleportMove(thing, m.x, m.y)) {
                        return 0;
                    }

                    thing.z = thing.floorz;  // fixme: not needed?
                    if (thing.player != null) {
                        thing.player.viewz =
                                thing.z + thing.player.viewheight;
                    }

                    // spawn teleport fog at source and destination
                    fog = PMobj.pSpawnMobj(oldx, oldy, oldz, Info.MT_TFOG);
                    SSound.sStartSound(fog, Sounds.sfx_telept);
                    an = (m.angle >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;
                    fog = PMobj.pSpawnMobj(
                            m.x + 20 * Tables.finecosine(an),
                            m.y + 20 * Tables.finesine[an],
                            thing.z, Info.MT_TFOG);

                    // emit sound, where?
                    SSound.sStartSound(fog, Sounds.sfx_telept);

                    // don't move for a bit
                    if (thing.player != null) {
                        thing.reactiontime = 18;
                    }

                    thing.angle = m.angle;
                    thing.momx = thing.momy = thing.momz = 0;
                    return 1;
                }
            }
        }
        return 0;
    }

    // C: `sector - sectors` to convert a sector_t pointer back into an
    // int index. Java needs a linear scan; same pattern as
    // PFloor.sectorIndex / PSight.sectorIndex.
    private static int sectorIndex(doom.render.Sector s) {
        for (int i = 0; i < PSetup.numsectors; i++) {
            if (PSetup.sectors[i] == s) {
                return i;
            }
        }
        return -1;
    }
}
