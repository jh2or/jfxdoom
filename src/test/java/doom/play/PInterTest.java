package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.render.Sector;
import doom.render.Subsector;
import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.GGame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PInterTest {

    private Sector sec;
    private Subsector ss;

    @BeforeEach
    void resetWorld() {
        sec = new Sector();
        sec.floorheight = 0;
        sec.ceilingheight = 200 * Fixed.FRACUNIT;
        sec.special = 0;
        sec.thinglist = null;

        ss = new Subsector();
        ss.sector = sec;

        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;
        PSetup.subsectors = new Subsector[] { ss };
        PSetup.numsubsectors = 1;
        PSetup.bmaporgx = -64 * Fixed.FRACUNIT;
        PSetup.bmaporgy = -64 * Fixed.FRACUNIT;
        PSetup.bmapwidth = 1;
        PSetup.bmapheight = 1;
        PSetup.blocklinks = new Mobj[1];
        PSetup.blockmaplump = new int[] { -64, -64, 1, 1, 5, -1 };
        PSetup.blockmap = PSetup.blockmaplump;

        PTick.pInitThinkers();
        Doomstat.gameskill = Doomstat.sk_medium;
        Doomstat.deathmatch = 0;
        Doomstat.netgame = false;
        Doomstat.gamemode = Doomstat.GameMode.shareware;

        // Refresh roster to clean players (no leftover state across tests).
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            GGame.players[i] = new Player();
            GGame.playeringame[i] = false;
        }
        GGame.consoleplayer = 0;
    }

    private Player makePlayer() {
        Player p = GGame.players[0];
        GGame.playeringame[0] = true;
        p.health = 100;
        p.armorpoints = 0;
        p.armortype = 0;
        p.readyweapon = Doomdef.wp_pistol;
        p.pendingweapon = Doomdef.wp_nochange;
        for (int i = 0; i < Doomdef.NUMAMMO; i++) {
            p.ammo[i] = 0;
            p.maxammo[i] = PInter.maxammo[i];
        }
        for (int i = 0; i < Doomdef.NUMWEAPONS; i++) {
            p.weaponowned[i] = false;
        }
        p.weaponowned[Doomdef.wp_fist] = true;
        p.weaponowned[Doomdef.wp_pistol] = true;
        // Spawn a player mobj for armor / damage tests.
        Mobj mo = new Mobj();
        mo.x = 0; mo.y = 0; mo.z = 0;
        mo.health = 100;
        mo.flags = Mobj.MF_SOLID | Mobj.MF_SHOOTABLE | Mobj.MF_DROPOFF | Mobj.MF_PICKUP;
        mo.subsector = ss;
        mo.player = p;
        mo.info = Info.mobjinfo[Info.MT_PLAYER];
        mo.height = 56 * Fixed.FRACUNIT;
        mo.type = Info.MT_PLAYER;
        p.mo = mo;
        return p;
    }

    // --- P_GiveAmmo ---

    @Test
    void giveAmmoReturnsFalseForNoAmmoType() {
        Player p = makePlayer();
        assertFalse(PInter.pGiveAmmo(p, Doomdef.am_noammo, 1));
    }

    @Test
    void giveAmmoFullClipRefuses() {
        Player p = makePlayer();
        p.ammo[Doomdef.am_clip] = p.maxammo[Doomdef.am_clip];
        assertFalse(PInter.pGiveAmmo(p, Doomdef.am_clip, 1));
    }

    // 1 clip load = clipammo[am_clip] = 10 bullets at sk_medium.
    @Test
    void giveAmmoOneLoadAddsClipammo() {
        Player p = makePlayer();
        assertTrue(PInter.pGiveAmmo(p, Doomdef.am_clip, 1));
        assertEquals(PInter.clipammo[Doomdef.am_clip], p.ammo[Doomdef.am_clip]);
    }

    // num=0 → half a clip.
    @Test
    void giveAmmoZeroLoadGivesHalfClip() {
        Player p = makePlayer();
        assertTrue(PInter.pGiveAmmo(p, Doomdef.am_clip, 0));
        assertEquals(PInter.clipammo[Doomdef.am_clip] / 2, p.ammo[Doomdef.am_clip]);
    }

    // sk_baby doubles ammo.
    @Test
    void giveAmmoBabyDifficultyDoubles() {
        Doomstat.gameskill = Doomstat.sk_baby;
        Player p = makePlayer();
        assertTrue(PInter.pGiveAmmo(p, Doomdef.am_clip, 1));
        assertEquals(2 * PInter.clipammo[Doomdef.am_clip], p.ammo[Doomdef.am_clip]);
    }

    // Going from 0 → first ammo with chaingun owned auto-switches to it.
    @Test
    void giveAmmoFromZeroAutoSwitchesPendingWeapon() {
        Player p = makePlayer();
        p.readyweapon = Doomdef.wp_fist;
        p.weaponowned[Doomdef.wp_chaingun] = true;
        p.ammo[Doomdef.am_clip] = 0;

        assertTrue(PInter.pGiveAmmo(p, Doomdef.am_clip, 1));
        assertEquals(Doomdef.wp_chaingun, p.pendingweapon);
    }

    // --- P_GiveBody ---

    @Test
    void giveBodyAtMaxHealthRefuses() {
        Player p = makePlayer();
        p.health = Doomdef.MAXHEALTH;
        assertFalse(PInter.pGiveBody(p, 25));
    }

    @Test
    void giveBodyClampsAtMaxHealth() {
        Player p = makePlayer();
        p.health = 90;
        assertTrue(PInter.pGiveBody(p, 25));
        assertEquals(Doomdef.MAXHEALTH, p.health);
        assertEquals(Doomdef.MAXHEALTH, p.mo.health);
    }

    // --- P_GiveArmor ---

    @Test
    void giveArmorWeakerRefuses() {
        Player p = makePlayer();
        p.armorpoints = 200;       // mega
        p.armortype = 2;
        assertFalse(PInter.pGiveArmor(p, 1));
    }

    @Test
    void giveArmorBetterAccepts() {
        Player p = makePlayer();
        assertTrue(PInter.pGiveArmor(p, 2));
        assertEquals(200, p.armorpoints);
        assertEquals(2, p.armortype);
    }

    // --- P_GiveWeapon ---

    @Test
    void giveWeaponNewWeaponSetsPending() {
        Player p = makePlayer();
        assertTrue(PInter.pGiveWeapon(p, Doomdef.wp_chaingun, false));
        assertTrue(p.weaponowned[Doomdef.wp_chaingun]);
        assertEquals(Doomdef.wp_chaingun, p.pendingweapon);
    }

    // --- P_GivePower ---

    @Test
    void givePowerInvulSetsTicCount() {
        Player p = makePlayer();
        assertTrue(PInter.pGivePower(p, Doomdef.pw_invulnerability));
        assertEquals(Doomdef.INVULNTICS, p.powers[Doomdef.pw_invulnerability]);
    }

    // pw_invisibility flips MF_SHADOW on the mobj.
    @Test
    void givePowerInvisibilitySetsShadowFlag() {
        Player p = makePlayer();
        assertTrue(PInter.pGivePower(p, Doomdef.pw_invisibility));
        assertTrue((p.mo.flags & Mobj.MF_SHADOW) != 0);
    }

    // pw_strength gives 100 health and 1 strength tic.
    @Test
    void givePowerStrengthHealsToFull() {
        Player p = makePlayer();
        p.health = 50;
        assertTrue(PInter.pGivePower(p, Doomdef.pw_strength));
        assertEquals(Doomdef.MAXHEALTH, p.health);
        assertEquals(1, p.powers[Doomdef.pw_strength]);
    }

    // --- P_GiveCard ---

    @Test
    void giveCardSetsBitAndBonusCount() {
        Player p = makePlayer();
        PInter.pGiveCard(p, Doomdef.it_bluecard);
        assertTrue(p.cards[Doomdef.it_bluecard]);
        assertEquals(PInter.BONUSADD, p.bonuscount);
    }

    // --- P_TouchSpecialThing ---

    private Mobj makePickup(int type) {
        Mobj m = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, type);
        return m;
    }

    @Test
    void touchSpecialThingArmorBonusBumpsArmorAndItemcount() {
        Player p = makePlayer();
        // SPR_BON2 = armor bonus. doomednum 2015 = MT_MISC1 (armor bonus).
        Mobj armorBonus = makePickup(searchByDoomednum(2015));
        int beforeItems = p.itemcount;

        PInter.pTouchSpecialThing(armorBonus, p.mo);
        assertEquals(1, p.armorpoints);
        assertEquals(1, p.armortype);
        // The bonus is MF_COUNTITEM.
        assertEquals(beforeItems + 1, p.itemcount);
        // Mobj was removed.
        assertTrue(armorBonus.removed);
        assertEquals(PInter.GOTARMBONUS, p.message);
    }

    // Out-of-reach z-delta → no pickup, no message, no removal.
    @Test
    void touchSpecialThingOutOfReachSkips() {
        Player p = makePlayer();
        Mobj armor = makePickup(searchByDoomednum(2018)); // SPR_ARM1
        // Shove the pickup way above reach.
        armor.z = p.mo.z + p.mo.height + 100 * Fixed.FRACUNIT;

        PInter.pTouchSpecialThing(armor, p.mo);
        assertFalse(armor.removed);
        assertEquals(0, p.armorpoints);
    }

    // --- P_DamageMobj / P_KillMobj ---

    // GOD mode absorbs sub-1000 damage (returns before health subtract).
    @Test
    void damageMobjGodModeAbsorbsDamage() {
        Player p = makePlayer();
        p.cheats |= Player.CF_GODMODE;
        int before = p.mo.health;
        PInter.pDamageMobj(p.mo, null, null, 50);
        assertEquals(before, p.mo.health);
    }

    // 1000+ damage punches through GOD mode.
    @Test
    void damageMobjMassiveDamagePunchesThroughGod() {
        Player p = makePlayer();
        p.cheats |= Player.CF_GODMODE;
        PInter.pDamageMobj(p.mo, null, null, 1500);
        assertTrue(p.mo.health <= 0);
    }

    // Normal damage with armor type 1 absorbs 1/3.
    @Test
    void damageMobjArmor1AbsorbsThird() {
        Player p = makePlayer();
        p.armortype = 1;
        p.armorpoints = 100;
        PInter.pDamageMobj(p.mo, null, null, 30);
        // saved = 30/3 = 10. damage_after = 20.
        assertEquals(100 - 20, p.mo.health);
        assertEquals(100 - 10, p.armorpoints);
    }

    // Killing a player flips PST_DEAD and clears MF_SOLID.
    // Note: only Player.health is clamped to 0 — Mobj.health goes
    // negative (the kill check is `<= 0`).
    @Test
    void damageMobjLethalKillsPlayer() {
        Player p = makePlayer();
        PInter.pDamageMobj(p.mo, null, null, 200);
        assertTrue(p.mo.health <= 0,
                "expected mo.health <= 0, got " + p.mo.health);
        assertEquals(0, p.health);
        assertEquals(Player.PST_DEAD, p.playerstate);
        assertEquals(0, p.mo.flags & Mobj.MF_SOLID);
    }

    // P_KillMobj of a counted-monster bumps the source's killcount.
    @Test
    void killMobjCountkillCreditsSource() {
        Player p = makePlayer();
        // Spawn a possessed (MF_COUNTKILL).
        Mobj target = PMobj.pSpawnMobj(100 * Fixed.FRACUNIT, 0,
                PMobj.ONFLOORZ, Info.MT_POSSESSED);
        int before = p.killcount;

        PInter.pKillMobj(p.mo, target);

        assertEquals(before + 1, p.killcount);
        // Target flags rewritten.
        assertEquals(0, target.flags & Mobj.MF_SHOOTABLE);
        assertTrue((target.flags & Mobj.MF_CORPSE) != 0);
    }

    // Possessed drops a clip on death.
    @Test
    void killMobjPossessedDropsClip() {
        Player p = makePlayer();
        Mobj target = PMobj.pSpawnMobj(100 * Fixed.FRACUNIT, 0,
                PMobj.ONFLOORZ, Info.MT_POSSESSED);
        // Last-spawned thinker before kill is the possessed; kill adds
        // a death animation which doesn't add a thinker, then a CLIP
        // drop which does.
        int before = countMobjThinkers();
        PInter.pKillMobj(p.mo, target);
        // CLIP added → +1 thinker.
        assertEquals(before + 1, countMobjThinkers());
        Mobj last = (Mobj) PTick.thinkercap.prev;
        assertEquals(Info.MT_CLIP, last.type);
        assertTrue((last.flags & Mobj.MF_DROPPED) != 0);
    }

    // --- helpers ---

    private static int searchByDoomednum(int dn) {
        for (int i = 0; i < Info.NUMMOBJTYPES; i++) {
            if (Info.mobjinfo[i].doomednum == dn) {
                return i;
            }
        }
        throw new IllegalArgumentException("no mobjtype with doomednum " + dn);
    }

    private static int countMobjThinkers() {
        int n = 0;
        for (Thinker t = PTick.thinkercap.next;
                t != PTick.thinkercap;
                t = t.next) {
            if (t instanceof Mobj && !t.removed) {
                n++;
            }
        }
        return n;
    }
}
