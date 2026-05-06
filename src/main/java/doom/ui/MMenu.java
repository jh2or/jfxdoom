// Port of linuxdoom-1.10/m_menu.c — stub.
//
// m_menu.c is the ~2000-line in-game menu system: navigation between
// the new-game / options / load-save / quit screens, slider widgets,
// the message-yes/no prompt, and the per-tic menu drawer. The full
// port lands once HuStuff / Sounds / DMain are wired up — until then
// this stub carries only the cross-module `menuactive` flag that
// p_tick.c P_Ticker reads to suspend the playsim while the menu is up.
package doom.ui;

public final class MMenu {

    // m_menu.c — set by M_StartControlPanel / cleared by
    // M_ClearMenus. Other modules (p_tick) read it as a pure
    // external.
    public static boolean menuactive;

    private MMenu() {
    }
}
