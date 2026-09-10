package doom.ai;

import doom.play.Player;
import doom.system.Doomdef;

/** Equipment visible to the player; snapshots keep decisions independent of mutable engine globals. */
record Inventory(int armor, int armorType, int bullets, int shells, int cells, int ready, int pending) {
    static final Inventory PISTOL_START = new Inventory(0, 0, 50, 0, 0, Doomdef.wp_pistol, Doomdef.wp_nochange);
    static Inventory read(Player p) {
        return new Inventory(p.armorpoints, p.armortype, p.ammo[Doomdef.am_clip], p.ammo[Doomdef.am_shell],
                p.ammo[Doomdef.am_cell], p.readyweapon, p.pendingweapon);
    }
    int preferredWeapon(int weapons) {
        if (owns(weapons, Doomdef.wp_plasma) && cells > 0) return Doomdef.wp_plasma;
        if (owns(weapons, Doomdef.wp_shotgun) && shells > 0) return Doomdef.wp_shotgun;
        if (owns(weapons, Doomdef.wp_chaingun) && bullets > 0) return Doomdef.wp_chaingun;
        if (owns(weapons, Doomdef.wp_pistol) && bullets > 0) return Doomdef.wp_pistol;
        return owns(weapons, Doomdef.wp_chainsaw) ? Doomdef.wp_chainsaw : Doomdef.wp_fist;
    }
    static boolean owns(int weapons, int weapon) { return (weapons & (1 << weapon)) != 0; }
    int armorBand() { return armor / 25 + 16 * armorType; }
    int shellBand() { return shells == 0 ? 0 : 1 + shells / 4; }
}
