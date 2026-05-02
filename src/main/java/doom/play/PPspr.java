// Port of linuxdoom-1.10/p_pspr.c — stub.
//
// p_pspr.c hosts the player's "psprite" overlay (the held weapon
// graphic at the bottom of the screen) plus the A_FirePistol /
// A_FireMissile / A_Punch / A_Refire / ... action callbacks the
// info.c states[] table bind to. The full port lands once Player
// gains the psprites[] / readyweapon / pendingweapon / ammo[] fields
// (waits on p_user / p_inter / d_player.h fully ported).
//
// For now this stub exposes only `pSetupPsprites(Player)` as a
// no-op so P_SpawnPlayer can call it during a level-load spawn —
// matching the same pattern as SSound.sStartSound and the GGame
// exit hooks.
package doom.play;

public final class PPspr {

    // p_pspr.c — P_SetupPsprites.
    // Initializes the held-weapon overlay state for `player`. With
    // psprites[] not yet on Player this is a no-op; the gun will
    // start invisible until p_pspr / p_user port and the call wires
    // through.
    public static void pSetupPsprites(Player player) {
        // Intentionally empty until psprites[] lands on Player.
    }

    private PPspr() {
    }
}
