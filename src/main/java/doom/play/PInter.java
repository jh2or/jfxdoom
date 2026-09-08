// Port of linuxdoom-1.10/p_inter.c — full.
//
// Player ↔ world interactions: pickup-routing for special things
// (P_TouchSpecialThing — sprite-id dispatch over the 30+ pickup
// classes), the seven Give* helpers (ammo/weapon/body/armor/card/
// power), and the damage / kill pipeline (P_DamageMobj /
// P_KillMobj). All call sites that the rest of the playsim needs
// land here.
//
// Pickup messages from d_englsh.h live as `public static final
// String` constants on this class per deviation 10 (string consumers
// each own the strings they reference).
//
// Weapon drop and automap stop call implemented modules; tactile feedback
// remains a no-op, matching linuxdoom.
package doom.play;

import doom.fixed.Fixed;
import doom.render.RMain;
import doom.render.Tables;
import doom.sound.SSound;
import doom.sound.Sounds;
import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.GGame;
import doom.system.ISystem;
import doom.ui.AmMap;

public final class PInter {

    // p_inter.c line 51 — bonus screen-flash count added on each
    // pickup that grants the gold-tinted overlay.
    public static final int BONUSADD = 6;

    // p_inter.c lines 58–59 — per-ammo caps (max storable / clip
    // load size). Indexed by Doomdef.am_*.
    public static final int[] maxammo  = { 200, 50, 300, 50 };
    public static final int[] clipammo = { 10, 4, 20, 1 };

    // d_englsh.h pickup messages (defines 80–120). Strings live with
    // their consumers per deviation 10.
    public static final String GOTARMOR     = "Picked up the armor.";
    public static final String GOTMEGA      = "Picked up the MegaArmor!";
    public static final String GOTHTHBONUS  = "Picked up a health bonus.";
    public static final String GOTARMBONUS  = "Picked up an armor bonus.";
    public static final String GOTSTIM      = "Picked up a stimpack.";
    public static final String GOTMEDINEED  = "Picked up a medikit that you REALLY need!";
    public static final String GOTMEDIKIT   = "Picked up a medikit.";
    public static final String GOTSUPER     = "Supercharge!";

    public static final String GOTBLUECARD  = "Picked up a blue keycard.";
    public static final String GOTYELWCARD  = "Picked up a yellow keycard.";
    public static final String GOTREDCARD   = "Picked up a red keycard.";
    public static final String GOTBLUESKUL  = "Picked up a blue skull key.";
    public static final String GOTYELWSKUL  = "Picked up a yellow skull key.";
    public static final String GOTREDSKULL  = "Picked up a red skull key.";

    public static final String GOTINVUL     = "Invulnerability!";
    public static final String GOTBERSERK   = "Berserk!";
    public static final String GOTINVIS     = "Partial Invisibility";
    public static final String GOTSUIT      = "Radiation Shielding Suit";
    public static final String GOTMAP       = "Computer Area Map";
    public static final String GOTVISOR     = "Light Amplification Visor";
    public static final String GOTMSPHERE   = "MegaSphere!";

    public static final String GOTCLIP      = "Picked up a clip.";
    public static final String GOTCLIPBOX   = "Picked up a box of bullets.";
    public static final String GOTROCKET    = "Picked up a rocket.";
    public static final String GOTROCKBOX   = "Picked up a box of rockets.";
    public static final String GOTCELL      = "Picked up an energy cell.";
    public static final String GOTCELLBOX   = "Picked up an energy cell pack.";
    public static final String GOTSHELLS    = "Picked up 4 shotgun shells.";
    public static final String GOTSHELLBOX  = "Picked up a box of shotgun shells.";
    public static final String GOTBACKPACK  = "Picked up a backpack full of ammo!";

    public static final String GOTBFG9000   = "You got the BFG9000!  Oh, yes.";
    public static final String GOTCHAINGUN  = "You got the chaingun!";
    public static final String GOTCHAINSAW  = "A chainsaw!  Find some meat!";
    public static final String GOTLAUNCHER  = "You got the rocket launcher!";
    public static final String GOTPLASMA    = "You got the plasma gun!";
    public static final String GOTSHOTGUN   = "You got the shotgun!";
    public static final String GOTSHOTGUN2  = "You got the super shotgun!";

    private PInter() {
    }

    //
    // P_GiveAmmo
    // Num is the number of clip loads, not the individual count
    // (0 = 1/2 clip). Returns false if the ammo can't be picked up
    // at all.
    //
    // p_inter.c lines 73–160.
    public static boolean pGiveAmmo(Player player, int ammo, int num) {
        int oldammo;

        if (ammo == Doomdef.am_noammo) {
            return false;
        }

        if (ammo < 0 || ammo > Doomdef.NUMAMMO) {
            ISystem.iError("P_GiveAmmo: bad type " + ammo);
        }

        if (player.ammo[ammo] == player.maxammo[ammo]) {
            return false;
        }

        if (num != 0) {
            num *= clipammo[ammo];
        } else {
            num = clipammo[ammo] / 2;
        }

        if (Doomstat.gameskill == Doomstat.sk_baby
                || Doomstat.gameskill == Doomstat.sk_nightmare) {
            // give double ammo in trainer mode,
            // you'll need in nightmare
            num <<= 1;
        }


        oldammo = player.ammo[ammo];
        player.ammo[ammo] += num;

        if (player.ammo[ammo] > player.maxammo[ammo]) {
            player.ammo[ammo] = player.maxammo[ammo];
        }

        // If non zero ammo,
        // don't change up weapons,
        // player was lower on purpose.
        if (oldammo != 0) {
            return true;
        }

        // We were down to zero,
        // so select a new weapon.
        // Preferences are not user selectable.
        switch (ammo) {
            case Doomdef.am_clip:
                if (player.readyweapon == Doomdef.wp_fist) {
                    if (player.weaponowned[Doomdef.wp_chaingun]) {
                        player.pendingweapon = Doomdef.wp_chaingun;
                    } else {
                        player.pendingweapon = Doomdef.wp_pistol;
                    }
                }
                break;

            case Doomdef.am_shell:
                if (player.readyweapon == Doomdef.wp_fist
                        || player.readyweapon == Doomdef.wp_pistol) {
                    if (player.weaponowned[Doomdef.wp_shotgun]) {
                        player.pendingweapon = Doomdef.wp_shotgun;
                    }
                }
                break;

            case Doomdef.am_cell:
                if (player.readyweapon == Doomdef.wp_fist
                        || player.readyweapon == Doomdef.wp_pistol) {
                    if (player.weaponowned[Doomdef.wp_plasma]) {
                        player.pendingweapon = Doomdef.wp_plasma;
                    }
                }
                break;

            case Doomdef.am_misl:
                if (player.readyweapon == Doomdef.wp_fist) {
                    if (player.weaponowned[Doomdef.wp_missile]) {
                        player.pendingweapon = Doomdef.wp_missile;
                    }
                }
                // C: falls through to default. break to match.
                break;
            default:
                break;
        }

        return true;
    }

    //
    // P_GiveWeapon
    // The weapon name may have a MF_DROPPED flag ored in.
    //
    // p_inter.c lines 167–220.
    public static boolean pGiveWeapon(Player player, int weapon, boolean dropped) {
        boolean gaveammo;
        boolean gaveweapon;

        if (Doomstat.netgame
                && (Doomstat.deathmatch != 2)
                && !dropped) {
            // leave placed weapons forever on net games
            if (player.weaponowned[weapon]) {
                return false;
            }

            player.bonuscount += BONUSADD;
            player.weaponowned[weapon] = true;

            if (Doomstat.deathmatch != 0) {
                pGiveAmmo(player, DItems.weaponinfo[weapon].ammo, 5);
            } else {
                pGiveAmmo(player, DItems.weaponinfo[weapon].ammo, 2);
            }
            player.pendingweapon = weapon;

            if (player == GGame.players[GGame.consoleplayer]) {
                SSound.sStartSound(null, Sounds.sfx_wpnup);
            }
            return false;
        }

        if (DItems.weaponinfo[weapon].ammo != Doomdef.am_noammo) {
            // give one clip with a dropped weapon,
            // two clips with a found weapon
            if (dropped) {
                gaveammo = pGiveAmmo(player, DItems.weaponinfo[weapon].ammo, 1);
            } else {
                gaveammo = pGiveAmmo(player, DItems.weaponinfo[weapon].ammo, 2);
            }
        } else {
            gaveammo = false;
        }

        if (player.weaponowned[weapon]) {
            gaveweapon = false;
        } else {
            gaveweapon = true;
            player.weaponowned[weapon] = true;
            player.pendingweapon = weapon;
        }

        return (gaveweapon || gaveammo);
    }

    //
    // P_GiveBody
    // Returns false if the body isn't needed at all.
    //
    // p_inter.c lines 228–242.
    public static boolean pGiveBody(Player player, int num) {
        if (player.health >= Doomdef.MAXHEALTH) {
            return false;
        }

        player.health += num;
        if (player.health > Doomdef.MAXHEALTH) {
            player.health = Doomdef.MAXHEALTH;
        }
        player.mo.health = player.health;

        return true;
    }

    //
    // P_GiveArmor
    // Returns false if the armor is worse than the current armor.
    //
    // p_inter.c lines 251–266.
    public static boolean pGiveArmor(Player player, int armortype) {
        int hits;

        hits = armortype * 100;
        if (player.armorpoints >= hits) {
            return false;       // don't pick up
        }

        player.armortype = armortype;
        player.armorpoints = hits;

        return true;
    }

    //
    // P_GiveCard
    //
    // p_inter.c lines 273–283.
    public static void pGiveCard(Player player, int card) {
        if (player.cards[card]) {
            return;
        }

        player.bonuscount = BONUSADD;
        player.cards[card] = true;
    }

    //
    // P_GivePower
    //
    // p_inter.c lines 289–331.
    public static boolean pGivePower(Player player, int power) {
        if (power == Doomdef.pw_invulnerability) {
            player.powers[power] = Doomdef.INVULNTICS;
            return true;
        }

        if (power == Doomdef.pw_invisibility) {
            player.powers[power] = Doomdef.INVISTICS;
            player.mo.flags |= Mobj.MF_SHADOW;
            return true;
        }

        if (power == Doomdef.pw_infrared) {
            player.powers[power] = Doomdef.INFRATICS;
            return true;
        }

        if (power == Doomdef.pw_ironfeet) {
            player.powers[power] = Doomdef.IRONTICS;
            return true;
        }

        if (power == Doomdef.pw_strength) {
            pGiveBody(player, 100);
            player.powers[power] = 1;
            return true;
        }

        if (player.powers[power] != 0) {
            return false;       // already got it
        }

        player.powers[power] = 1;
        return true;
    }

    //
    // P_TouchSpecialThing
    //
    // p_inter.c lines 339–661.
    public static void pTouchSpecialThing(Mobj special, Mobj toucher) {
        Player player;
        int i;
        int delta;
        int sound;

        delta = special.z - toucher.z;

        if (delta > toucher.height
                || delta < -8 * Fixed.FRACUNIT) {
            // out of reach
            return;
        }


        sound = Sounds.sfx_itemup;
        player = toucher.player;

        // Dead thing touching.
        // Can happen with a sliding player corpse.
        if (toucher.health <= 0) {
            return;
        }

        // Identify by sprite.
        switch (special.sprite) {
            // armor
            case Info.SPR_ARM1:
                if (!pGiveArmor(player, 1)) {
                    return;
                }
                player.message = GOTARMOR;
                break;

            case Info.SPR_ARM2:
                if (!pGiveArmor(player, 2)) {
                    return;
                }
                player.message = GOTMEGA;
                break;

            // bonus items
            case Info.SPR_BON1:
                player.health++;        // can go over 100%
                if (player.health > 200) {
                    player.health = 200;
                }
                player.mo.health = player.health;
                player.message = GOTHTHBONUS;
                break;

            case Info.SPR_BON2:
                player.armorpoints++;   // can go over 100%
                if (player.armorpoints > 200) {
                    player.armorpoints = 200;
                }
                if (player.armortype == 0) {
                    player.armortype = 1;
                }
                player.message = GOTARMBONUS;
                break;

            case Info.SPR_SOUL:
                player.health += 100;
                if (player.health > 200) {
                    player.health = 200;
                }
                player.mo.health = player.health;
                player.message = GOTSUPER;
                sound = Sounds.sfx_getpow;
                break;

            case Info.SPR_MEGA:
                if (Doomstat.gamemode != Doomstat.GameMode.commercial) {
                    return;
                }
                player.health = 200;
                player.mo.health = player.health;
                pGiveArmor(player, 2);
                player.message = GOTMSPHERE;
                sound = Sounds.sfx_getpow;
                break;

            // cards
            // leave cards for everyone
            case Info.SPR_BKEY:
                if (!player.cards[Doomdef.it_bluecard]) {
                    player.message = GOTBLUECARD;
                }
                pGiveCard(player, Doomdef.it_bluecard);
                if (!Doomstat.netgame) {
                    break;
                }
                return;

            case Info.SPR_YKEY:
                if (!player.cards[Doomdef.it_yellowcard]) {
                    player.message = GOTYELWCARD;
                }
                pGiveCard(player, Doomdef.it_yellowcard);
                if (!Doomstat.netgame) {
                    break;
                }
                return;

            case Info.SPR_RKEY:
                if (!player.cards[Doomdef.it_redcard]) {
                    player.message = GOTREDCARD;
                }
                pGiveCard(player, Doomdef.it_redcard);
                if (!Doomstat.netgame) {
                    break;
                }
                return;

            case Info.SPR_BSKU:
                if (!player.cards[Doomdef.it_blueskull]) {
                    player.message = GOTBLUESKUL;
                }
                pGiveCard(player, Doomdef.it_blueskull);
                if (!Doomstat.netgame) {
                    break;
                }
                return;

            case Info.SPR_YSKU:
                if (!player.cards[Doomdef.it_yellowskull]) {
                    player.message = GOTYELWSKUL;
                }
                pGiveCard(player, Doomdef.it_yellowskull);
                if (!Doomstat.netgame) {
                    break;
                }
                return;

            case Info.SPR_RSKU:
                if (!player.cards[Doomdef.it_redskull]) {
                    player.message = GOTREDSKULL;
                }
                pGiveCard(player, Doomdef.it_redskull);
                if (!Doomstat.netgame) {
                    break;
                }
                return;

            // medikits, heals
            case Info.SPR_STIM:
                if (!pGiveBody(player, 10)) {
                    return;
                }
                player.message = GOTSTIM;
                break;

            case Info.SPR_MEDI:
                if (!pGiveBody(player, 25)) {
                    return;
                }
                if (player.health < 25) {
                    player.message = GOTMEDINEED;
                } else {
                    player.message = GOTMEDIKIT;
                }
                break;


            // power ups
            case Info.SPR_PINV:
                if (!pGivePower(player, Doomdef.pw_invulnerability)) {
                    return;
                }
                player.message = GOTINVUL;
                sound = Sounds.sfx_getpow;
                break;

            case Info.SPR_PSTR:
                if (!pGivePower(player, Doomdef.pw_strength)) {
                    return;
                }
                player.message = GOTBERSERK;
                if (player.readyweapon != Doomdef.wp_fist) {
                    player.pendingweapon = Doomdef.wp_fist;
                }
                sound = Sounds.sfx_getpow;
                break;

            case Info.SPR_PINS:
                if (!pGivePower(player, Doomdef.pw_invisibility)) {
                    return;
                }
                player.message = GOTINVIS;
                sound = Sounds.sfx_getpow;
                break;

            case Info.SPR_SUIT:
                if (!pGivePower(player, Doomdef.pw_ironfeet)) {
                    return;
                }
                player.message = GOTSUIT;
                sound = Sounds.sfx_getpow;
                break;

            case Info.SPR_PMAP:
                if (!pGivePower(player, Doomdef.pw_allmap)) {
                    return;
                }
                player.message = GOTMAP;
                sound = Sounds.sfx_getpow;
                break;

            case Info.SPR_PVIS:
                if (!pGivePower(player, Doomdef.pw_infrared)) {
                    return;
                }
                player.message = GOTVISOR;
                sound = Sounds.sfx_getpow;
                break;

            // ammo
            case Info.SPR_CLIP:
                if ((special.flags & Mobj.MF_DROPPED) != 0) {
                    if (!pGiveAmmo(player, Doomdef.am_clip, 0)) {
                        return;
                    }
                } else {
                    if (!pGiveAmmo(player, Doomdef.am_clip, 1)) {
                        return;
                    }
                }
                player.message = GOTCLIP;
                break;

            case Info.SPR_AMMO:
                if (!pGiveAmmo(player, Doomdef.am_clip, 5)) {
                    return;
                }
                player.message = GOTCLIPBOX;
                break;

            case Info.SPR_ROCK:
                if (!pGiveAmmo(player, Doomdef.am_misl, 1)) {
                    return;
                }
                player.message = GOTROCKET;
                break;

            case Info.SPR_BROK:
                if (!pGiveAmmo(player, Doomdef.am_misl, 5)) {
                    return;
                }
                player.message = GOTROCKBOX;
                break;

            case Info.SPR_CELL:
                if (!pGiveAmmo(player, Doomdef.am_cell, 1)) {
                    return;
                }
                player.message = GOTCELL;
                break;

            case Info.SPR_CELP:
                if (!pGiveAmmo(player, Doomdef.am_cell, 5)) {
                    return;
                }
                player.message = GOTCELLBOX;
                break;

            case Info.SPR_SHEL:
                if (!pGiveAmmo(player, Doomdef.am_shell, 1)) {
                    return;
                }
                player.message = GOTSHELLS;
                break;

            case Info.SPR_SBOX:
                if (!pGiveAmmo(player, Doomdef.am_shell, 5)) {
                    return;
                }
                player.message = GOTSHELLBOX;
                break;

            case Info.SPR_BPAK:
                if (!player.backpack) {
                    for (i = 0; i < Doomdef.NUMAMMO; i++) {
                        player.maxammo[i] *= 2;
                    }
                    player.backpack = true;
                }
                for (i = 0; i < Doomdef.NUMAMMO; i++) {
                    pGiveAmmo(player, i, 1);
                }
                player.message = GOTBACKPACK;
                break;

            // weapons
            case Info.SPR_BFUG:
                if (!pGiveWeapon(player, Doomdef.wp_bfg, false)) {
                    return;
                }
                player.message = GOTBFG9000;
                sound = Sounds.sfx_wpnup;
                break;

            case Info.SPR_MGUN:
                if (!pGiveWeapon(player, Doomdef.wp_chaingun,
                        (special.flags & Mobj.MF_DROPPED) != 0)) {
                    return;
                }
                player.message = GOTCHAINGUN;
                sound = Sounds.sfx_wpnup;
                break;

            case Info.SPR_CSAW:
                if (!pGiveWeapon(player, Doomdef.wp_chainsaw, false)) {
                    return;
                }
                player.message = GOTCHAINSAW;
                sound = Sounds.sfx_wpnup;
                break;

            case Info.SPR_LAUN:
                if (!pGiveWeapon(player, Doomdef.wp_missile, false)) {
                    return;
                }
                player.message = GOTLAUNCHER;
                sound = Sounds.sfx_wpnup;
                break;

            case Info.SPR_PLAS:
                if (!pGiveWeapon(player, Doomdef.wp_plasma, false)) {
                    return;
                }
                player.message = GOTPLASMA;
                sound = Sounds.sfx_wpnup;
                break;

            case Info.SPR_SHOT:
                if (!pGiveWeapon(player, Doomdef.wp_shotgun,
                        (special.flags & Mobj.MF_DROPPED) != 0)) {
                    return;
                }
                player.message = GOTSHOTGUN;
                sound = Sounds.sfx_wpnup;
                break;

            case Info.SPR_SGN2:
                if (!pGiveWeapon(player, Doomdef.wp_supershotgun,
                        (special.flags & Mobj.MF_DROPPED) != 0)) {
                    return;
                }
                player.message = GOTSHOTGUN2;
                sound = Sounds.sfx_wpnup;
                break;

            default:
                ISystem.iError("P_SpecialThing: Unknown gettable thing");
        }

        if ((special.flags & Mobj.MF_COUNTITEM) != 0) {
            player.itemcount++;
        }
        PMobj.pRemoveMobj(special);
        player.bonuscount += BONUSADD;
        if (player == GGame.players[GGame.consoleplayer]) {
            SSound.sStartSound(null, sound);
        }
    }


    //
    // KillMobj
    //
    // p_inter.c lines 668–758.
    public static void pKillMobj(Mobj source, Mobj target) {
        int item;
        Mobj mo;

        target.flags &= ~(Mobj.MF_SHOOTABLE | Mobj.MF_FLOAT | Mobj.MF_SKULLFLY);

        if (target.type != Info.MT_SKULL) {
            target.flags &= ~Mobj.MF_NOGRAVITY;
        }

        target.flags |= Mobj.MF_CORPSE | Mobj.MF_DROPOFF;
        target.height >>= 2;

        if (source != null && source.player != null) {
            // count for intermission
            if ((target.flags & Mobj.MF_COUNTKILL) != 0) {
                source.player.killcount++;
            }

            if (target.player != null) {
                // C: source->player->frags[target->player - players]++
                source.player.frags[playerIndex(target.player)]++;
            }
        } else if (!Doomstat.netgame
                && (target.flags & Mobj.MF_COUNTKILL) != 0) {
            // count all monster deaths,
            // even those caused by other monsters
            GGame.players[0].killcount++;
        }

        if (target.player != null) {
            // count environment kills against you
            if (source == null) {
                target.player.frags[playerIndex(target.player)]++;
            }

            target.flags &= ~Mobj.MF_SOLID;
            target.player.playerstate = Player.PST_DEAD;
            PPspr.pDropWeapon(target.player);

            if (target.player == GGame.players[GGame.consoleplayer]
                    && AmMap.automapactive) {
                // don't die in auto map,
                // switch view prior to dying
                AmMap.amStop();
            }

        }

        if (target.health < -target.info.spawnhealth
                && target.info.xdeathstate != 0) {
            PMobj.pSetMobjState(target, target.info.xdeathstate);
        } else {
            PMobj.pSetMobjState(target, target.info.deathstate);
        }
        target.tics -= MRandom.pRandom() & 3;

        if (target.tics < 1) {
            target.tics = 1;
        }

        // I_StartSound (&actor->r, actor->info->deathsound);


        // Drop stuff.
        // This determines the kind of object spawned
        // during the death frame of a thing.
        switch (target.type) {
            case Info.MT_WOLFSS:
            case Info.MT_POSSESSED:
                item = Info.MT_CLIP;
                break;

            case Info.MT_SHOTGUY:
                item = Info.MT_SHOTGUN;
                break;

            case Info.MT_CHAINGUY:
                item = Info.MT_CHAINGUN;
                break;

            default:
                return;
        }

        mo = PMobj.pSpawnMobj(target.x, target.y, PMobj.ONFLOORZ, item);
        mo.flags |= Mobj.MF_DROPPED;    // special versions of items
    }


    //
    // P_DamageMobj
    // Damages both enemies and players.
    // "inflictor" is the thing that caused the damage
    //  creature or missile, can be NULL (slime, etc).
    // "source" is the thing to target after taking damage
    //  creature or NULL.
    // Source and inflictor are the same for melee attacks.
    // Source can be NULL for slime, barrel explosions
    // and other environmental stuff.
    //
    // p_inter.c lines 774–917.
    public static void pDamageMobj(Mobj target, Mobj inflictor,
            Mobj source, int damage) {
        int ang;
        int saved;
        Player player;
        int thrust;
        int temp;

        if ((target.flags & Mobj.MF_SHOOTABLE) == 0) {
            return;     // shouldn't happen...
        }

        if (target.health <= 0) {
            return;
        }

        if ((target.flags & Mobj.MF_SKULLFLY) != 0) {
            target.momx = target.momy = target.momz = 0;
        }

        player = target.player;
        if (player != null && Doomstat.gameskill == Doomstat.sk_baby) {
            damage >>= 1;       // take half damage in trainer mode
        }


        // Some close combat weapons should not
        // inflict thrust and push the victim out of reach,
        // thus kick away unless using the chainsaw.
        if (inflictor != null
                && (target.flags & Mobj.MF_NOCLIP) == 0
                && (source == null
                    || source.player == null
                    || source.player.readyweapon != Doomdef.wp_chainsaw)) {
            ang = RMain.rPointToAngle2(inflictor.x,
                    inflictor.y,
                    target.x,
                    target.y);

            thrust = damage * (Fixed.FRACUNIT >> 3) * 100 / target.info.mass;

            // make fall forwards sometimes
            if (damage < 40
                    && damage > target.health
                    && target.z - inflictor.z > 64 * Fixed.FRACUNIT
                    && (MRandom.pRandom() & 1) != 0) {
                ang += Tables.ANG180;
                thrust *= 4;
            }

            ang = (ang >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;
            target.momx += Fixed.mul(thrust, Tables.finecosine(ang));
            target.momy += Fixed.mul(thrust, Tables.finesine[ang]);
        }

        // player specific
        if (player != null) {
            // end of game hell hack
            if (target.subsector.sector.special == 11
                    && damage >= target.health) {
                damage = target.health - 1;
            }


            // Below certain threshold,
            // ignore damage in GOD mode, or with INVUL power.
            if (damage < 1000
                    && ((player.cheats & Player.CF_GODMODE) != 0
                        || player.powers[Doomdef.pw_invulnerability] != 0)) {
                return;
            }

            if (player.armortype != 0) {
                if (player.armortype == 1) {
                    saved = damage / 3;
                } else {
                    saved = damage / 2;
                }

                if (player.armorpoints <= saved) {
                    // armor is used up
                    saved = player.armorpoints;
                    player.armortype = 0;
                }
                player.armorpoints -= saved;
                damage -= saved;
            }
            player.health -= damage;    // mirror mobj health here for Dave
            if (player.health < 0) {
                player.health = 0;
            }

            player.attacker = source;
            player.damagecount += damage;   // add damage after armor / invuln

            if (player.damagecount > 100) {
                player.damagecount = 100;   // teleport stomp does 10k points...
            }

            temp = damage < 100 ? damage : 100;

            if (player == GGame.players[GGame.consoleplayer]) {
                ISystem.iTactile(40, 10, 40 + temp * 2);
            }
        }

        // do the damage
        target.health -= damage;
        if (target.health <= 0) {
            pKillMobj(source, target);
            return;
        }

        if ((MRandom.pRandom() < target.info.painchance)
                && (target.flags & Mobj.MF_SKULLFLY) == 0) {
            target.flags |= Mobj.MF_JUSTHIT;    // fight back!

            PMobj.pSetMobjState(target, target.info.painstate);
        }

        target.reactiontime = 0;        // we're awake now...

        if ((target.threshold == 0 || target.type == Info.MT_VILE)
                && source != null && source != target
                && source.type != Info.MT_VILE) {
            // if not intent on another player,
            // chase after this one
            target.target = source;
            target.threshold = Doomdef.BASETHRESHOLD;
            if (target.state == Info.states[target.info.spawnstate]
                    && target.info.seestate != Info.S_NULL) {
                PMobj.pSetMobjState(target, target.info.seestate);
            }
        }
    }

    // C: `p - players` to convert a player_t pointer back into a
    // 0..MAXPLAYERS-1 index. Java needs a linear scan; with
    // MAXPLAYERS=4 the cost is a single comparison most of the time.
    private static int playerIndex(Player p) {
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            if (GGame.players[i] == p) {
                return i;
            }
        }
        return -1;
    }
}
