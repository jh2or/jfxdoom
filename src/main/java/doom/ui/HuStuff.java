// Port of linuxdoom-1.10/hu_stuff.c — stub.
//
// hu_stuff.c hosts the heads-up text widgets (level-name banner,
// pickup messages, chat-line) on top of hu_lib's text-widget core.
// The full port lands once hu_lib's caller-side state (key bindings,
// font load, English message strings) is wired through. For now
// only the call P_SpawnPlayer makes — `HU_Start` to wake the HUD
// after a spawn — has a no-op landing pad here, matching the same
// pattern as StStuff.stStart and the SSound stub.
package doom.ui;

public final class HuStuff {

    // hu_stuff.c — HU_Start.
    // Stub: clears any in-flight chat-line and re-arms the
    // level-banner popup. The current consumers (P_SpawnPlayer) only
    // need the call to fire; the visible reset lands with the full
    // port.
    public static void huStart() {
        // Intentionally empty until hu_stuff.c lands.
    }

    private HuStuff() {
    }
}
