// Port of linuxdoom-1.10/p_enemy.c — stub.
//
// p_enemy.c is the ~1500-line monster AI module: the A_Look /
// A_Chase / A_Fire* / A_Pain / A_Death action callbacks bound by
// the info.c states[] table for every monster type, plus the
// shared P_NoiseAlert traversal that wakes monsters when the
// player fires a non-silent weapon.
//
// For now this stub carries only `pNoiseAlert` as a no-op so
// p_pspr.c P_FireWeapon can call through. The full body recursive-
// flood-fills into adjacent sectors via REJECT-aware sound traversal,
// setting `sector.soundtarget = target` on every reachable sector.
// AI A_Look reads soundtarget to decide whether to wake. Lands when
// the rest of p_enemy ports.
package doom.play;

public final class PEnemy {

    // p_enemy.c — P_NoiseAlert.
    // Stub: the full body floods sound into adjacent sectors via
    // P_RecursiveSound and writes Sector.soundtarget; without that,
    // monsters never wake from gunfire. p_pspr.c's P_FireWeapon
    // calls this on every shot.
    public static void pNoiseAlert(Mobj target, Mobj emitter) {
        // Intentionally empty until p_enemy ports.
    }

    private PEnemy() {
    }
}
